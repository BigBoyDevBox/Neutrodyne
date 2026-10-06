// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:designsystem"))
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:navigation"))
            implementation(project(":download:api"))
            implementation(project.dependencies.platform(libs.coil.bom))
            implementation(libs.coil.compose)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.navigation3.ui.jb)
            implementation(libs.cmp.material3.adaptive.navigation3)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.metrox.viewmodel.compose)
        }
    }
}
