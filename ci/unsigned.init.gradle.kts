// CI builds the release APKs unsigned (CSQTT-APP-PLAN §3: the signing key never leaves the laptop). The app's own
// verifyReleaseSigning gate refuses a release build without a key; this init script switches that one gate off, so the same
// tree builds `*-release-unsigned.apk` in CI and a signed APK on the laptop, with no change to the app's build files.
gradle.allprojects {
    tasks.matching { it.name == "verifyReleaseSigning" }.configureEach { enabled = false }
}
