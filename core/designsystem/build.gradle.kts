// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(libs.cmp.material3.navigationSuite)
            implementation(libs.graphics.shapes)
            implementation(project.dependencies.platform(libs.coil.bom))
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.collections.immutable)
        }
    }
}
