// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// deploy v3's engine (CSQTT-APP-PLAN §4.1): pure JVM, no Android imports, so the app and deploy-cli run the same code. The
// scripts of deploy-bundle/ ship inside it as resources under com/csqtt/deploy/bundle/, and the engine uploads them from there.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val bundleDir = rootProject.layout.projectDirectory.dir("deploy-bundle")

tasks.processResources {
    from(bundleDir) { into("com/csqtt/deploy/bundle") }
}

tasks.test {
    // BundleTest holds the shipped file list equal to the directory itself, and checks every file's bytes
    systemProperty("csqtt.bundle.dir", bundleDir.asFile.absolutePath)
    inputs.dir(bundleDir)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
