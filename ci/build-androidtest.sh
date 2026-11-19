#!/usr/bin/env bash
# Build the debug app and its instrumented-test APK (app/src/androidTest) for an x86_64 emulator, in the pinned image.
# Usage: ci/build-androidtest.sh <source tree> <output dir>   → <output dir>/app-debug.apk, app-debug-androidTest.apk
# No libclient.so and no server cores are built in: the instrumented tests drive the app's UI and store, never a tunnel.
# Run them on a device with:
#   adb install -r -g app-debug.apk && adb install -r app-debug-androidTest.apk
#   adb shell am instrument -w net.sanityprotocol.csqtt.forktest.test/androidx.test.runner.AndroidJUnitRunner
# Laptop knobs as in ci/test.sh: CSQTT_DOCKER_ARGS, CSQTT_GRADLE_HOME, CSQTT_ANDROID_IMAGE.
set -euo pipefail
CI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SRC=$(cd "$1" && pwd); OUT=$2; mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
GRADLE_SHA256=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb    # gradle-9.4.1-bin.zip, as ci/build-apk.sh
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
GH=${CSQTT_GRADLE_HOME:-$W/gradle-home}; mkdir -p "$GH" "$W/src"
tar -C "$SRC" --exclude=./.git --exclude=./.build --exclude=./.campaign --exclude='./rust-*/target' --exclude=./app/build \
  -cf - . | tar -C "$W/src" -xf -
P="$W/src/gradle/wrapper/gradle-wrapper.properties"
grep -q '^distributionUrl=.*gradle-9\.4\.1-bin\.zip$' "$P" || { echo "the tree wants another Gradle than 9.4.1; pin it here" >&2; exit 1; }
sed -i '/^distributionSha256Sum=/d' "$P"; echo "distributionSha256Sum=$GRADLE_SHA256" >> "$P"
read -r -a XARGS <<< "${CSQTT_DOCKER_ARGS:-}"
docker run --rm ${XARGS[@]+"${XARGS[@]}"} -v "$W:/w" -v "$GH:/tmp/gh" -v "$CI:/ci:ro" -e GRADLE_USER_HOME=/tmp/gh \
  -u "$(id -u):$(id -g)" -e HOME=/tmp "${CSQTT_ANDROID_IMAGE:-csqtt-ci-android}" bash -c '
set -euo pipefail
cd /w/src && chmod +x gradlew && printf "sdk.dir=%s\n" "$ANDROID_HOME" > local.properties
./gradlew --no-daemon --console=plain -Pcsqtt.abis=x86_64 assembleDebug assembleDebugAndroidTest'
cp "$W"/src/app/build/outputs/apk/debug/*x86_64*.apk "$OUT/app-debug.apk"
cp "$W"/src/app/build/outputs/apk/androidTest/debug/*.apk "$OUT/app-debug-androidTest.apk"
ls -l "$OUT"
