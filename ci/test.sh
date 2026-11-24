#!/usr/bin/env bash
# Run one test suite of a source tree inside the pinned CI images (M-159: every crate tested on every push).
# Usage: ci/test.sh <source tree> <server|client|app|deploy|bundle|e2e> [e2e only: a substring of the case names to run]
#   server: cargo test of rust-server for the target it ships on (x86_64 musl, built through zig like ci/build-server.sh)
#   client: cargo test of rust-client on the host target (its sni.rs includes the tree's root domains.txt)
#   app:    the Gradle unit tests (testDebugUnitTest; AGP 9 has no release unit-test task)
#   deploy: deploy v3's engine and command line (pure JVM): :deploy's unit tests, which also run the bundle's POSIX half on
#           the image's sh (dash), then deploy-cli's installDist and one run of the laid-out command (bundle-check)
#   bundle: deploy-bundle/'s scripts: shellcheck on each, in the dialect its `shellcheck shell=` line names, BusyBox ash's
#           parser on the POSIX ones (`shell=dash`), which must run on whatever box inspect.sh meets, and lib/marker.sh run
#           under BusyBox ash against its expected output
#   e2e:    rust-client's #[ignore] end-to-end tests against a running server (ci/e2e-server.sh up); CSQTT_E2E_PEER (and
#           CSQTT_E2E_WEB, the other CSQTT_E2E_* knobs) are passed through, and the container shares the host network;
#           CSQTT_E2E_SERVER_LOG (ci/e2e-server.sh's LOG) is mounted read-only
# A failing or non-compiling test exits non-zero. Outside e2e, tests marked #[ignore] (a TUN, a running server) stay out.
# Laptop knobs, as in ci/build-apk.sh: CSQTT_DOCKER_ARGS (e.g. "--network host"), CSQTT_GRADLE_HOME (a gradle.properties with
# the proxy), CSQTT_CARGO_REGISTRY (a kept registry cache), CSQTT_SHELLCHECK_IMAGE (the bundle suite's image).
set -euo pipefail
CI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC=$(cd "$1" && pwd); SUITE=$2; FILTER=${3:-}
case "$SUITE" in server|client|app|deploy|bundle|e2e) ;; *) echo "unknown suite: $SUITE" >&2; exit 2;; esac
GRADLE_SHA256=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb    # gradle-9.4.1-bin.zip, as ci/build-apk.sh
SHELLCHECK_IMAGE=${CSQTT_SHELLCHECK_IMAGE:-koalaman/shellcheck-alpine:v0.11.0@sha256:9955be09ea7f0dbf7ae942ac1f2094355bb30d96fffba0ec09f5432207544002}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
REG=${CSQTT_CARGO_REGISTRY:-$W/registry}; GH=${CSQTT_GRADLE_HOME:-$W/gradle-home}; mkdir -p "$REG" "$GH" "$W/src"
tar -C "$SRC" --exclude=./.git --exclude=./.build --exclude=./.campaign --exclude='./rust-*/target' --exclude=./app/build \
  --exclude=./deploy/build --exclude=./deploy-cli/build \
  -cf - . | tar -C "$W/src" -xf -
read -r -a XARGS <<< "${CSQTT_DOCKER_ARGS:-}"
if [ "$SUITE" = e2e ]; then
  [ -n "${CSQTT_E2E_PEER:-}" ] || { echo "e2e needs CSQTT_E2E_PEER (ci/e2e-server.sh up prints it)" >&2; exit 2; }
  XARGS=(--network host -e "E2E_FILTER=$FILTER"); for v in $(compgen -e | grep '^CSQTT_E2E_' || true); do XARGS+=(-e "$v"); done
  # the server's log, read-only at the same path, for the cases that check the server's own lines (M-26)
  [ -z "${CSQTT_E2E_SERVER_LOG:-}" ] || XARGS+=(-v "$CSQTT_E2E_SERVER_LOG:$CSQTT_E2E_SERVER_LOG:ro")
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
cd /w/src/rust-client && cargo +1.97.1 test --locked --release -- --ignored --test-threads=1 ${E2E_FILTER:+"$E2E_FILTER"}'
  ;;
app|deploy)
  P="$W/src/gradle/wrapper/gradle-wrapper.properties"
  grep -q '^distributionUrl=.*gradle-9\.4\.1-bin\.zip$' "$P" || { echo "the tree wants another Gradle than 9.4.1; pin it here" >&2; exit 1; }
  sed -i '/^distributionSha256Sum=/d' "$P"; echo "distributionSha256Sum=$GRADLE_SHA256" >> "$P"
  if [ "$SUITE" = app ]; then TASKS=testDebugUnitTest; AFTER=true
  else TASKS=':deploy:test :deploy-cli:installDist'; AFTER='deploy-cli/build/install/deploy-cli/bin/deploy-cli bundle-check'; fi
  run "${CSQTT_ANDROID_IMAGE:-csqtt-ci-android}" "
set -euo pipefail
cd /w/src && chmod +x gradlew && printf 'sdk.dir=%s\n' \"\$ANDROID_HOME\" > local.properties
./gradlew --no-daemon --console=plain -I /ci/unsigned.init.gradle.kts $TASKS
$AFTER"
  ;;
bundle)
  # read-only, offline, as the calling user; the image is pinned by digest like every other input
  docker run --rm --network none -u "$(id -u):$(id -g)" -v "$W/src/deploy-bundle:/b:ro" "$SHELLCHECK_IMAGE" sh -c '
set -eu
cd /b
files=$(find . -type f -name "*.sh" | sort)
[ -n "$files" ] || { echo "deploy-bundle carries no script" >&2; exit 1; }
for f in $files; do
  shellcheck --severity=warning "$f"
  if grep -q "^# shellcheck shell=dash" "$f"; then busybox sh -n "$f"; fi
done
# the parser above passes some bash-isms; this runs the codec under BusyBox ash (the deploy suite runs it under dash)
got=$(busybox sh -c ". ./lib/marker.sh; csqtt_emit INV \"a|b\" 100% \"\$(printf \"l1\\nl2\\r\")\" \"п|р\" \"\"; csqtt_end")
want="CSQTT_INV|a%7Cb|100%25|l1%0Al2%0D|п%7Cр|
CSQTT_END|1"
[ "$got" = "$want" ] || { echo "lib/marker.sh under BusyBox ash printed:"; echo "$got"; exit 1; }
echo "bundle: $(echo "$files" | wc -l) scripts checked; marker.sh runs under BusyBox ash"'
  ;;
esac
