// SPDX-License-Identifier: Unlicense
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Applies Metro and nothing else (01 Dependency injection). */
class MetroConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("dev.zacsweers.metro")
    }
}
