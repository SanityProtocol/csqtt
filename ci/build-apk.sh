#!/usr/bin/env bash
# Build the csqtt APKs from a source tree, inside the pinned image ci/Dockerfile.android (csqtt-ci-android).
# Usage: ci/build-apk.sh <source tree> <cores dir> <output dir>
#   <cores dir>: csqtt-linux-{amd64,arm64,armv7} + csqtt.server-provenance.json from ci/provenance.py, built in the same run;
#                they become the APK's assets (deploy installs them on the user's VPS).
#   Unsigned by default (CI): ci/unsigned.init.gradle.kts switches off the app's signing gate → *-release-unsigned.apk.
#   Signed (the laptop only): CSQTT_SIGNING_DIR=<dir with csqtt-app-signing.env and the keystore>, mounted read-only.
# The in-container paths (/w/src, /w/t-<abi>, CARGO_HOME=/tmp/ch, GRADLE_USER_HOME=/tmp/gh) are those 2.5.3 was built with.
set -euo pipefail
CI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC=$(cd "$1" && pwd); CORES=$(cd "$2" && pwd); OUT=$3; IMAGE=${CSQTT_ANDROID_IMAGE:-csqtt-ci-android}
GRADLE_SHA256=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb    # gradle-9.4.1-bin.zip
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
REG=${CSQTT_CARGO_REGISTRY:-$W/registry}; GH=${CSQTT_GRADLE_HOME:-$W/gradle-home}; mkdir -p "$REG" "$GH" "$W/src"
tar -C "$SRC" --exclude=./.git --exclude=./.build --exclude=./.campaign -cf - . | tar -C "$W/src" -xf -
A="$W/src/app/src/main/assets"
for a in amd64 arm64 armv7; do install -m 0755 "$CORES/csqtt-linux-$a" "$A/csqtt-linux-$a"; done
install -m 0644 "$CORES/csqtt.server-provenance.json" "$A/csqtt.server-provenance.json"
( cd "$CORES" && sha256sum -c --quiet SHA256SUMS )
P="$W/src/gradle/wrapper/gradle-wrapper.properties"                 # the wrapper checks the Gradle it downloads
grep -q '^distributionUrl=.*gradle-9\.4\.1-bin\.zip$' "$P" || { echo "the tree wants another Gradle than 9.4.1; pin it here" >&2; exit 1; }
sed -i '/^distributionSha256Sum=/d' "$P"; echo "distributionSha256Sum=$GRADLE_SHA256" >> "$P"
SIGN=(); MODE=unsigned
if [ -n "${CSQTT_SIGNING_DIR:-}" ]; then SIGN=(-v "$(cd "$CSQTT_SIGNING_DIR" && pwd):/s:ro"); MODE=signed; fi
docker run --rm -v "$W:/w" -v "$REG:/tmp/ch/registry" -v "$GH:/tmp/gh" -v "$CI:/ci:ro" ${SIGN[@]+"${SIGN[@]}"} \
  -e CARGO_HOME=/tmp/ch -e GRADLE_USER_HOME=/tmp/gh -e MODE=$MODE -u "$(id -u):$(id -g)" -e HOME=/tmp "$IMAGE" bash -c '
set -euo pipefail
cp -a /usr/local/cargo/bin /tmp/ch/ 2>/dev/null || true; export PATH=/tmp/ch/bin:/usr/local/cargo/bin:$PATH
cd /w/src/rust-client
for abi in arm64-v8a armeabi-v7a x86_64; do
  case $abi in arm64-v8a) t=aarch64-linux-android;; armeabi-v7a) t=armv7-linux-androideabi;; x86_64) t=x86_64-linux-android;; esac
  cargo +1.97.1 ndk -t $abi -P 26 build --locked --release --target-dir /w/t-$abi 2>&1 | grep -E "^(error|warning: unused)|Finished" || true
  mkdir -p /w/src/app/src/main/jniLibs/$abi && cp /w/t-$abi/$t/release/client /w/src/app/src/main/jniLibs/$abi/libclient.so
done
cd /w/src && chmod +x gradlew && printf "sdk.dir=%s\n" "$ANDROID_HOME" > local.properties
if [ "$MODE" = signed ]; then
  set -a; . /s/csqtt-app-signing.env; set +a; export KEYSTORE_FILE=/s/$KEYSTORE_FILE; INIT=()
else
  INIT=(-I /ci/unsigned.init.gradle.kts)
fi
./gradlew --no-daemon --console=plain "${INIT[@]}" assembleRelease 2>&1 | grep -vE "^w: |warning:|^> Task |^$" | tail -40
mkdir -p /w/apk && cp app/build/outputs/apk/release/*.apk /w/apk/
'
cp "$W"/apk/*.apk "$OUT/"
( cd "$OUT" && sha256sum *.apk | tee SHA256SUMS.apk )
