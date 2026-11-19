#!/usr/bin/env bash
# Run one test suite of a source tree inside the pinned CI images (M-159: every crate tested on every push).
# Usage: ci/test.sh <source tree> <server|client|app|e2e> [test name filter, e2e only]
#   server: cargo test of rust-server for the target it ships on (x86_64 musl, built through zig like ci/build-server.sh)
#   client: cargo test of rust-client on the host target (its sni.rs includes the tree's root domains.txt)
#   app:    the Gradle unit tests (testDebugUnitTest; AGP 9 has no release unit-test task)
#   e2e:    rust-client's #[ignore] end-to-end tests against a running server (ci/e2e-server.sh up); CSQTT_E2E_PEER (and
#           CSQTT_E2E_WEB, the other CSQTT_E2E_* knobs) are passed through, and the container shares the host network
# A failing or non-compiling test exits non-zero. Outside e2e, tests marked #[ignore] (a TUN, a running server) stay out.
# Laptop knobs, as in ci/build-apk.sh: CSQTT_DOCKER_ARGS (e.g. "--network host"), CSQTT_GRADLE_HOME (a gradle.properties with
# the proxy), CSQTT_CARGO_REGISTRY (a kept registry cache).
set -euo pipefail
CI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC=$(cd "$1" && pwd); SUITE=$2; FILTER=${3:-}
case "$SUITE" in server|client|app|e2e) ;; *) echo "unknown suite: $SUITE" >&2; exit 2;; esac
GRADLE_SHA256=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb    # gradle-9.4.1-bin.zip, as ci/build-apk.sh
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
REG=${CSQTT_CARGO_REGISTRY:-$W/registry}; GH=${CSQTT_GRADLE_HOME:-$W/gradle-home}; mkdir -p "$REG" "$GH" "$W/src"
tar -C "$SRC" --exclude=./.git --exclude=./.build --exclude=./.campaign --exclude='./rust-*/target' --exclude=./app/build \
  -cf - . | tar -C "$W/src" -xf -
read -r -a XARGS <<< "${CSQTT_DOCKER_ARGS:-}"
if [ "$SUITE" = e2e ]; then
  [ -n "${CSQTT_E2E_PEER:-}" ] || { echo "e2e needs CSQTT_E2E_PEER (ci/e2e-server.sh up prints it)" >&2; exit 2; }
  XARGS=(--network host -e "E2E_FILTER=$FILTER"); for v in $(compgen -e | grep '^CSQTT_E2E_' || true); do XARGS+=(-e "$v"); done
fi
run() {  # run <image> <script>: the tree at /w/src, caches mounted, as the calling user
  docker run --rm ${XARGS[@]+"${XARGS[@]}"} -v "$W:/w" -v "$REG:/tmp/ch/registry" -v "$GH:/tmp/gh" -v "$CI:/ci:ro" \
    -e CARGO_HOME=/tmp/ch -e GRADLE_USER_HOME=/tmp/gh -u "$(id -u):$(id -g)" -e HOME=/tmp "$1" bash -c "$2"
}
case "$SUITE" in
server)
  # rust-server/.cargo/config.toml builds for x86_64 musl, whose C parts need a musl C compiler: the zig wrappers of
  # ci/build-server.sh (a plain `cargo test` fails with "failed to find tool x86_64-linux-musl-gcc").
  run "${CSQTT_SERVER_IMAGE:-csqtt-ci-server}" '
set -euo pipefail
T=x86_64-unknown-linux-musl; Z=x86_64-linux-musl; E=X86_64_UNKNOWN_LINUX_MUSL; C=x86_64_unknown_linux_musl; WR=/tmp/zw
mkdir -p $WR
printf "#!/usr/bin/env bash\nargs=(); for a in \"\$@\"; do [[ \"\$a\" == \"--target=$T\" ]] || args+=(\"\$a\"); done\nexec zig cc -target $Z \"\${args[@]}\"\n" > $WR/zigcc
printf "#!/usr/bin/env bash\nargs=(); for a in \"\$@\"; do [[ \"\$a\" == \"--target=$T\" ]] || args+=(\"\$a\"); done\nexec zig c++ -target $Z \"\${args[@]}\"\n" > $WR/zigcxx
printf "#!/usr/bin/env bash\nexec zig ar \"\$@\"\n" > $WR/zigar; chmod +x $WR/*
export CC_$C=$WR/zigcc CXX_$C=$WR/zigcxx AR_$C=$WR/zigar CARGO_TARGET_${E}_LINKER=$WR/zigcc ZIG_GLOBAL_CACHE_DIR=/tmp/zigcache
cd /w/src/rust-server && cargo +1.97.1 test --locked --release'
  ;;
client)
  run "${CSQTT_SERVER_IMAGE:-csqtt-ci-server}" 'set -euo pipefail; cd /w/src/rust-client && cargo +1.97.1 test --locked --release'
  ;;
e2e)
  # one at a time: the cases share the server, and the throughput case measures it
  run "${CSQTT_SERVER_IMAGE:-csqtt-ci-server}" 'set -euo pipefail; unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY
cd /w/src/rust-client && cargo +1.97.1 test --locked --release -- --ignored --test-threads=1 ${E2E_FILTER:+--exact "$E2E_FILTER"}'
  ;;
app)
  P="$W/src/gradle/wrapper/gradle-wrapper.properties"
  grep -q '^distributionUrl=.*gradle-9\.4\.1-bin\.zip$' "$P" || { echo "the tree wants another Gradle than 9.4.1; pin it here" >&2; exit 1; }
  sed -i '/^distributionSha256Sum=/d' "$P"; echo "distributionSha256Sum=$GRADLE_SHA256" >> "$P"
  run "${CSQTT_ANDROID_IMAGE:-csqtt-ci-android}" '
set -euo pipefail
cd /w/src && chmod +x gradlew && printf "sdk.dir=%s\n" "$ANDROID_HOME" > local.properties
./gradlew --no-daemon --console=plain -I /ci/unsigned.init.gradle.kts testDebugUnitTest'
  ;;
esac
