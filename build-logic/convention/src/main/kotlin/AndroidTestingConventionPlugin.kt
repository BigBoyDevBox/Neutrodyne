// SPDX-License-Identifier: Unlicense
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hook for Android test settings; content owned by 09 Test infrastructure. */
class AndroidTestingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        dependencies {
            add("testImplementation", project(":core:testing"))
            add("testImplementation", libs.findBundle("jvm-test").get())
        }
        configureNeutrodyneTestTasks()
    }
}
