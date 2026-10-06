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
