// SPDX-License-Identifier: Unlicense
import app.cash.licensee.LicenseeExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** The one Licensee allow-list shared by `:app`, `:desktopApp` and `:sync:server` (01 Licensee allow-list). */
internal fun Project.configureLicensee() {
    pluginManager.apply("app.cash.licensee")
    val jna = libs.version("jna")
    extensions.configure<LicenseeExtension> {
        for (spdx in ALLOWED_SPDX) allow(spdx)
        allowDependency("net.java.dev.jna", "jna", jna) { because("dual-licensed; used under Apache-2.0 (D3)") }
        allowDependency("net.java.dev.jna", "jna-platform", jna) { because("dual-licensed; used under Apache-2.0 (D3)") }
        // No GPL, AGPL, LGPL or MPL artifact is otherwise allowed, not even scoped (D3).
    }
}

private val ALLOWED_SPDX = listOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "Unlicense", "CC0-1.0")
