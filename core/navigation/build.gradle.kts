// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.library)
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.navigation3.runtime)
            api(libs.cmp.runtime)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
