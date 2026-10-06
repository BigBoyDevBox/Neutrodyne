// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.jvm.island)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    api(platform(libs.okhttp.bom))
    api(libs.okhttp)
    implementation(libs.okhttp.coroutines)
    implementation(libs.okio)
}
