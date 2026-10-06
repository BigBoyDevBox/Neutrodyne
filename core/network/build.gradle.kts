// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    alias(libs.plugins.neutrodyne.metro)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            api(project.dependencies.platform(libs.ktor.bom))
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.ktor.bom))
            // api, not implementation: the island's Metro contributions only reach a shell graph when
            // the island is on the shell's compile classpath (S8, 2026-10-06)
            api(project(":core:network:okhttp"))
            implementation(libs.ktor.client.okhttp)
        }
        desktopMain.dependencies {
            implementation(project.dependencies.platform(libs.ktor.bom))
            api(project(":core:network:okhttp"))
            implementation(libs.ktor.client.okhttp)
        }
    }
}
