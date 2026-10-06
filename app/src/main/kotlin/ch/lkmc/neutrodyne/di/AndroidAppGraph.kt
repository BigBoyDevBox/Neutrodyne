// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.di

import android.app.Application
import ch.lkmc.neutrodyne.core.common.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

/**
 * The Android main process's Metro graph (01 "Dependency injection", D82). Minimal M0a skeleton after
 * spike S8: the YouTube binding containers, ViewModelGraph, worker factory, member-injection functions
 * and `CoreBindings` arrive with M0a step 17.
 */
@DependencyGraph(AppScope::class)
interface AndroidAppGraph {
    /** The graph's application instance, so framework code can reach it without a cast. */
    val application: Application

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application): AndroidAppGraph
    }
}
