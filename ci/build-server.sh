#!/usr/bin/env bash
# Build one csqtt server core from a source tree, inside the pinned image ci/Dockerfile.server (csqtt-ci-server).
# Usage: ci/build-server.sh <source tree> <amd64|arm64|armv7> <output dir>     → <output dir>/csqtt-linux-<arch>
# The in-container paths (/w/src, CARGO_HOME=/tmp/ch, the /tmp/zw zig wrappers) are those 2.5.3 was built with: they end up
# in the binary (panic locations), so keeping them is what lets a rebuild of the same source match byte for byte.
set -euo pipefail
SRC=$(cd "$1" && pwd); ARCH=$2; OUT=$3; IMAGE=${CSQTT_SERVER_IMAGE:-csqtt-ci-server}
case "$ARCH" in amd64|arm64|armv7) ;; *) echo "unknown arch: $ARCH" >&2; exit 2;; esac
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
REG=${CSQTT_CARGO_REGISTRY:-$W/registry}                     # a kept registry cache only saves downloads
mkdir -p "$W/src" "$REG"
cp -a "$SRC/rust-server" "$SRC/shared" "$SRC/app" "$W/src/"   # what 2.5.3's build took; the server embeds app/src/main/assets/domains.txt
rm -rf "$W/src/rust-server/target"
docker run --rm -v "$W:/w" -v "$REG:/tmp/ch/registry" -e CARGO_HOME=/tmp/ch -e ARCH="$ARCH" \
  -u "$(id -u):$(id -g)" -e HOME=/tmp "$IMAGE" bash -c '
set -euo pipefail
case "$ARCH" in
  amd64) T=x86_64-unknown-linux-musl;  Z=x86_64-linux-musl;  E=X86_64_UNKNOWN_LINUX_MUSL;  C=x86_64_unknown_linux_musl ;;
  arm64) T=aarch64-unknown-linux-musl; Z=aarch64-linux-musl; E=AARCH64_UNKNOWN_LINUX_MUSL; C=aarch64_unknown_linux_musl ;;
  armv7) T=armv7-unknown-linux-musleabihf; Z="arm-linux-musleabihf -mcpu=generic+v7a"; E=ARMV7_UNKNOWN_LINUX_MUSLEABIHF; C=armv7_unknown_linux_musleabihf ;;
esac
WR=/tmp/zw; mkdir -p $WR
printf "#!/usr/bin/env bash\nargs=(); for a in \"\$@\"; do [[ \"\$a\" == \"--target=$T\" ]] || args+=(\"\$a\"); done\nexec zig cc -target $Z \"\${args[@]}\"\n" > $WR/zigcc
printf "#!/usr/bin/env bash\nargs=(); for a in \"\$@\"; do [[ \"\$a\" == \"--target=$T\" ]] || args+=(\"\$a\"); done\nexec zig c++ -target $Z \"\${args[@]}\"\n" > $WR/zigcxx
printf "#!/usr/bin/env bash\nexec zig ar \"\$@\"\n" > $WR/zigar; chmod +x $WR/*
export CC_$C=$WR/zigcc CXX_$C=$WR/zigcxx AR_$C=$WR/zigar CARGO_TARGET_${E}_LINKER=$WR/zigcc CARGO_TARGET_${E}_RUSTFLAGS="-C link-self-contained=no"
export ZIG_GLOBAL_CACHE_DIR=/tmp/zigcache CARGO_HOME_BIN=1
cd /w/src/rust-server
cargo +1.97.1 zigbuild --locked --release --target $T 2>&1 | grep -v "^warning: aws-lc\|^ *Compiling"
cp target/$T/release/csqtt /w/csqtt-linux-$ARCH
'
install -m 0755 "$W/csqtt-linux-$ARCH" "$OUT/csqtt-linux-$ARCH"
sha256sum "$OUT/csqtt-linux-$ARCH"
