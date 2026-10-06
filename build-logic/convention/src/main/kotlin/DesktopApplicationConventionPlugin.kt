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
    override fun apply(target: Project) =
        with(target) {
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
                // Compose resolves ProGuard (GPL-2.0, D3) through a detached configuration inside the release
                // task actions, so it never lands on a named configuration to scan. Disabling the release
                // build type's ProGuard is the enforceable gate; verifyDependencyPolicy asserts that no
                // ProGuard task stays enabled (2026-10-06).
                buildTypes.release.proguard.isEnabled
                    .set(false)
            }

            // Compose desktop's *Release* tasks are the packaging path that can run ProGuard (D3)
            tasks.matching { it.name.contains("Release") }.configureEach { enabled = false }

            configureLicensee()
            registerDependencyPolicy()
            configureModuleGraphAssert()
            configureNeutrodyneTestTasks()
        }
}
