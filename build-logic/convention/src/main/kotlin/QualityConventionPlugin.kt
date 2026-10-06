// SPDX-License-Identifier: Unlicense
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Root-only quality plugin: Spotless, `checkSpdxHeaders`, `checkBannedApis`, the brand-asset tasks
 * (01 Convention plugins). Filled in M0a step 21/22 and M0b step 31.
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        check(target == target.rootProject) { "neutrodyne.quality is applied to the root project only" }
    }
}
