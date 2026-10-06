// SPDX-License-Identifier: Unlicense
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * The Android shell `:app` (01 Convention plugins, Build variants and ABIs): build types `release` (published) and
 * `debug` (local, `.debug`), every build type signed by the committed public keystore, per-ABI APKs, Compose.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        assertKotlinPluginVersion()

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            namespace = BASE_PACKAGE
            testBuildType = "debug"
            defaultConfig {
                applicationId = BASE_PACKAGE
                targetSdk = TARGET_SDK
                versionCode = providers.gradleProperty("neutrodyne.versionCode").get().toInt()
                versionName = providers.gradleProperty("neutrodyne.versionName").get()
            }
            buildFeatures {
                buildConfig = true
                compose = true
            }
            androidResources { generateLocaleConfig = true }
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }
            signingConfigs {
                // Committed and public on purpose (D61, 01 Signing config)
                create(SIGNING_CONFIG) {
                    storeFile = rootProject.file("signing/neutrodyne-public.keystore")
                    storeType = "pkcs12"
                    storePassword = PUBLIC_PASSWORD
                    keyAlias = PUBLIC_ALIAS
                    keyPassword = PUBLIC_PASSWORD
                    enableV1Signing = false
                    enableV2Signing = true
                    enableV3Signing = true
                }
            }
            splits {
                abi {
                    isEnable = true
                    reset()
                    include(*PUBLISHED_ABIS)
                    isUniversalApk = false
                }
            }
            buildTypes {
                getByName("release") {
                    optimization { enable = true }
                }
                getByName("debug") {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-debug"
                    isPseudoLocalesEnabled = true
                }
                // Every build type, including those androidx.baselineprofile creates, uses the public key
                configureEach { signingConfig = signingConfigs.getByName(SIGNING_CONFIG) }
            }
            packaging { jniLibs { useLegacyPackaging = false } }
        }

        dependencies {
            val bom = platform(libs.lib("androidx-compose-bom"))
            add("implementation", bom)
            add("debugImplementation", bom)
            add("androidTestImplementation", bom)
        }

        configureLicensee()
        pluginManager.apply("neutrodyne.metro")
        pluginManager.apply("neutrodyne.android.lint")
        pluginManager.apply("neutrodyne.android.testing")
    }

    private companion object {
        const val SIGNING_CONFIG = "neutrodynePublic"
        const val PUBLIC_PASSWORD = "neutrodyne"
        const val PUBLIC_ALIAS = "neutrodyne"
        val PUBLISHED_ABIS = arrayOf("arm64-v8a", "x86_64", "armeabi-v7a")
    }
}
