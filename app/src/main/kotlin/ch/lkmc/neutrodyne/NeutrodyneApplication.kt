// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne

import android.app.Application
import ch.lkmc.neutrodyne.di.AndroidAppGraph
import dev.zacsweers.metro.createGraphFactory

/**
 * Minimal M0a application: creates the Metro graph lazily on first access (01 "Application start-up"),
 * so nothing is constructed before something needs it. Process roles, ACRA, the initializer runner and
 * WorkManager arrive with M0a step 17.
 */
class NeutrodyneApplication : Application() {
    val graph: AndroidAppGraph by lazy {
        createGraphFactory<AndroidAppGraph.Factory>().create(this)
    }
}
