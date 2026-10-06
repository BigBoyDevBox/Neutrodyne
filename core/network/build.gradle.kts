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
            implementation(project(":core:network:okhttp"))
            implementation(libs.ktor.client.okhttp)
        }
        desktopMain.dependencies {
            implementation(project.dependencies.platform(libs.ktor.bom))
            implementation(project(":core:network:okhttp"))
            implementation(libs.ktor.client.okhttp)
        }
    }
}
