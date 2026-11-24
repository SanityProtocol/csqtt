#!/bin/sh
# SPDX-FileCopyrightText: 2026 SanityProtocol
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
#
# The Docker mode's root filesystem (csqtt P2 B13a; CSQTT-APP-PLAN §6.11 rule 1; P2-PLAN §1.8): Alpine's minirootfs for one
# architecture, sha256-pinned, with iptables (nft and legacy) and iproute2's ip at pinned versions. The VPS builds our image
# from it with `docker import`, never pulling from a registry or a package mirror.
#
#   ci/build-rootfs.sh <amd64|arm64|armv7> <out dir>
#
# - Trust: the two minirootfs tarballs used (the target's and x86_64's) are checked against the sha256 pinned here; the
#   target's own keys (inside its tarball) verify the signed package index, and apk verifies each package against it.
# - Nothing of the target runs: the x86_64 minirootfs's own apk, in a throwaway container, installs into the target's root
#   with --no-scripts (no emulation is needed for arm64 or armv7).
# - Reproducible: rootfs-pack.py rewrites the tree as a sorted tar, owners root, mtimes the minirootfs release's, gzip
#   without a name or a time. Its provenance (the minirootfs, every package installed, the output's sha256) is written beside.
# Output: <out dir>/csqtt-rootfs-<arch>.tar.gz and csqtt-rootfs-<arch>.provenance.json.
set -eu

ALPINE=3.24.4
BRANCH=v3.24
RELEASED=2026-11-05T09:19:04Z
MIRROR=https://dl-cdn.alpinelinux.org/alpine
PACKAGES='iptables=1.8.13-r0 iptables-legacy=1.8.13-r0 iproute2-minimal=7.0.0-r0'

mini_sha() {
	case $1 in
	x86_64) echo cdaa38cb8de3537967ee5b6c9b6cd00cc962da2b018e6eea48696bb274923012 ;;
	aarch64) echo 4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de ;;
	armv7) echo 2054061dafea7fe4cd38015701ee77119e780dd0382d0c32fad56986fe31b699 ;;
	esac
}

case ${1:-} in
amd64) A=x86_64 ;;
arm64) A=aarch64 ;;
armv7) A=armv7 ;;
*) echo "usage: ci/build-rootfs.sh <amd64|arm64|armv7> <out dir>" >&2; exit 2 ;;
esac
OUT=${2:?out dir}
CI=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT

for a in x86_64 $A; do
	[ -f "$W/mini-$a.tar.gz" ] && continue
	curl -fsSL --retry 3 --max-time 180 -o "$W/mini-$a.tar.gz" "$MIRROR/$BRANCH/releases/$a/alpine-minirootfs-$ALPINE-$a.tar.gz"
	echo "$(mini_sha $a)  $W/mini-$a.tar.gz" | sha256sum -c - >&2
done

# the installer: x86_64's minirootfs, imported (never pulled), removed after
BUILDER=csqtt-rootfs-builder:$ALPINE-$$
docker import "$W/mini-x86_64.tar.gz" "$BUILDER" >/dev/null
# shellcheck disable=SC2016
docker run --rm -i -e A="$A" -e PACKAGES="$PACKAGES" -e REPO="$MIRROR/$BRANCH/main" "$BUILDER" /bin/sh -euc '
	mkdir /target
	tar -xzf - -C /target
	apk --root /target --arch "$A" --keys-dir /target/etc/apk/keys --repository "$REPO" \
		--no-scripts --no-cache --quiet add $PACKAGES >&2
	tar -C /target -cf - .
' <"$W/mini-$A.tar.gz" >"$W/root.tar" || { docker image rm "$BUILDER" >/dev/null 2>&1; exit 1; }
docker image rm "$BUILDER" >/dev/null

python3 "$CI/rootfs-pack.py" "$W/root.tar" "$OUT/csqtt-rootfs-$1.tar.gz" "$RELEASED" "$ALPINE" "$A" \
	"$MIRROR/$BRANCH/releases/$A/alpine-minirootfs-$ALPINE-$A.tar.gz" "$(mini_sha "$A")"
