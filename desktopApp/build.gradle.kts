// SPDX-License-Identifier: Unlicense
plugins {
    alias(libs.plugins.neutrodyne.desktop.application)
}

val youtubeEngine = providers.gradleProperty("neutrodyne.youtubeEngine").orElse("true").get().toBoolean()

dependencies {
    for (feature in listOf(
        "feeds", "library", "groups", "podcast", "episode", "player", "queue", "downloads", "discover",
        "importexport", "settings", "sync",
    )) {
        implementation(project(":feature:$feature"))
    }
    for (module in listOf(
        ":core:model", ":core:common", ":core:domain", ":core:navigation", ":core:database", ":core:datastore",
        ":core:network", ":core:data", ":core:artwork", ":core:designsystem", ":core:ui",
        ":feeds", ":playback:api", ":playback:core", ":playback:desktop", ":playback:engine", ":playback:native",
        ":desktop:system", ":download:api", ":download:impl", ":youtube:api", ":youtube:impl",
        ":sync:protocol", ":sync:api", ":sync:impl",
    )) {
        implementation(project(module))
    }
    if (youtubeEngine) implementation(project(":youtube:ytdlp-desktop"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    runtimeOnly(libs.kxml2)
}
