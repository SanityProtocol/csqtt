// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "CSQTT"
include(":app")
// deploy v3 (CSQTT-APP-PLAN §4.1): the engine, pure JVM so the app and the command line run the same code, and that
// command line, which drives the test zoo
include(":deploy")
include(":deploy-cli")
