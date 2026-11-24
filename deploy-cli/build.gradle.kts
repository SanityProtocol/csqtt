// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// deploy v3's engine as a command line (CSQTT-APP-PLAN §4.1): it drives the test zoo, so the zoo runs what users will run.
// `installDist` lays it out as bin/deploy-cli plus lib/*.jar; a single fat jar would break BouncyCastle's signed jars (B1).

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.csqtt.deploy.cli.MainKt")
    applicationName = "deploy-cli"
}

dependencies {
    implementation(project(":deploy"))
}
