// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.common

import dev.zacsweers.metro.Scope

/**
 * The one application scope of both app shells (`AndroidAppGraph`, `DesktopAppGraph`) and the target of
 * every module's Metro contributions (01 Dependency injection, D82). Declared here because features,
 * JVM islands and both shells must see it.
 */
@Scope
annotation class AppScope

/**
 * The Android `:ytx` process's scope (`YtxGraph`, 01 Dependency injection, D82): it aggregates only
 * `YtxScope` contributions — the yt-dlp engine side plus the network island's `CoreClients` and its
 * inputs — so no database, DataStore or credential binding exists in `:ytx` at all. Like [AppScope]
 * it lives here because the island has to see it and cannot depend on `:app` (S8, 2026-10-06).
 */
@Scope
annotation class YtxScope
