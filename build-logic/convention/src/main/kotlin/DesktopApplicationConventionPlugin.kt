// SPDX-License-Identifier: Unlicense
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension

/**
 * The desktop shell `:desktopApp`: Kotlin/JVM on JDK 25 with the Compose application plugin. The ProGuard
 * `*Release*` tasks never run (GPL-2.0, D3). Packaging (`nativeDistributions`) arrives in M0b (11).
 */
class DesktopApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        installPluginGuards()
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("neutrodyne.metro")
        assertKotlinPluginVersion()
        forbidDynamicVersions()
        configureDesktopJvm()

        val compose = extensions.getByType<ComposeExtension>()
        val desktop = (compose as org.gradle.api.plugins.ExtensionAware).extensions.getByType<DesktopExtension>()
        desktop.application {
            mainClass = "$BASE_PACKAGE.desktop.MainKt"
            jvmArgs += "--enable-native-access=ALL-UNNAMED"
        }

        // Compose desktop's *Release* tasks run ProGuard (GPL-2.0); they are never part of any build (D3)
        tasks.matching { it.name.contains("Release") }.configureEach { enabled = false }

        configureLicensee()
        configureNeutrodyneTestTasks()
    }
}
