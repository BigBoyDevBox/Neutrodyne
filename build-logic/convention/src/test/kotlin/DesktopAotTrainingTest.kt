// SPDX-License-Identifier: Unlicense
import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The PO-42 AOT-cache training wiring of 11 (AOT cache): the launcher's `-XX:AOTCache`
 * option ships only on packaging invocations, the training run swaps it for the
 * `-XX:AOTCacheOutput` + journey pair inside the image's .cfg and restores the file's
 * exact bytes, the Linux argv wraps headless runners under xvfb-run, and the macOS
 * bundle is re-signed inside out before the package tasks archive it.
 */
class DesktopAotTrainingTest {
    @Test
    fun `the aot flag joins the launcher options only for packaged builds`() {
        for (target in DesktopPackagingTarget.entries) {
            assertTrue(
                AOT_CACHE_OPTION in desktopJvmOptions(target, packaged = true),
                "$target packaged",
            )
            assertTrue(
                desktopJvmOptions(target, packaged = false).none { "AOTCache" in it },
                "$target unpackaged",
            )
        }
        // Off the packaging matrix there is no flag even on a packaging invocation.
        assertTrue(desktopJvmOptions(null, packaged = true).none { "AOTCache" in it })
        assertTrue(desktopJvmOptions(null, packaged = false).none { "AOTCache" in it })
    }

    @Test
    fun `the image layout resolves launcher cfg and runtime release per target`() {
        assertEquals(
            AotImageLayout(
                launcher = "Neutrodyne.exe",
                cfg = "app/Neutrodyne.cfg",
                appDir = "app",
                runtimeRelease = "runtime/release",
            ),
            aotImageLayout("windows"),
        )
        assertEquals(
            AotImageLayout(
                launcher = "Contents/MacOS/Neutrodyne",
                cfg = "Contents/app/Neutrodyne.cfg",
                appDir = "Contents/app",
                runtimeRelease = "Contents/runtime/Contents/Home/release",
            ),
            aotImageLayout("macos"),
        )
        assertEquals(
            AotImageLayout(
                launcher = "bin/Neutrodyne",
                cfg = "lib/app/Neutrodyne.cfg",
                appDir = "lib/app",
                runtimeRelease = "lib/runtime/release",
            ),
            aotImageLayout("linux"),
        )
    }

    @Test
    fun `the training cfg swap removes the cache flag and keeps every other line`() {
        val original =
            "[Application]\n" +
                "app.name=Neutrodyne\n" +
                "app.mainclass=ch.lkmc.neutrodyne.desktop.MainKt\n" +
                "app.classpath=app-one.jar\n" +
                "app.classpath=app-two.jar\n" +
                "[JavaOptions]\n" +
                "java-options=--enable-native-access=ALL-UNNAMED\n" +
                "java-options=-XX:AOTCache=\$APPDIR/neutrodyne.aot\n" +
                "java-options=-Xmx768m\n"

        val training = aotTrainingCfg(original)

        val lines = training.lines()
        // The production read flag is gone — an option naming a not-yet-written file
        // must not reach the training JVM — and exactly the two training options were
        // appended at the end.
        assertFalse(lines.any { "-XX:AOTCache=" in it })
        assertEquals(
            "java-options=-XX:AOTCacheOutput=\$APPDIR/neutrodyne.aot",
            lines[lines.size - 3],
        )
        assertEquals("java-options=-Dcompose.aot.training-run=true", lines[lines.size - 2])
        // Every other line survives verbatim and in order.
        assertEquals(
            original
                .lines()
                .filter { it != "java-options=-XX:AOTCache=\$APPDIR/neutrodyne.aot" }
                .dropLastWhile { it.isEmpty() },
            lines.dropLast(3).dropLastWhile { it.isEmpty() },
        )
    }

    @Test
    fun `the training cfg swap keeps a windows cfg's crlf endings`() {
        val original =
            "[JavaOptions]\r\n" +
                "java-options=-XX:AOTCache=\$APPDIR/neutrodyne.aot\r\n" +
                "java-options=-Xmx768m\r\n"

        val training = aotTrainingCfg(original)

        assertFalse("\n" in training.replace("\r\n", ""), "no stray LF-only line endings")
        assertTrue(
            training.endsWith(
                "java-options=-Dcompose.aot.training-run=true\r\n",
            ),
        )
    }

    @Test
    fun `the training cfg swap fails closed without the cache flag`() {
        // A .cfg without the production flag means the launcher never carried it:
        // shipping the archive then would pair it with a launcher that ignores it.
        val cfg =
            "[JavaOptions]\n" +
                "java-options=--enable-native-access=ALL-UNNAMED\n" +
                "java-options=-Xmx768m\n"
        assertFailsWith<GradleException> { aotTrainingCfg(cfg) }
    }

    @Test
    fun `the training launcher argv wraps headless linux under xvfb only`() {
        val launcher = "/img/Neutrodyne/bin/Neutrodyne"
        // A headed runner launches the image's own launcher directly; a headless
        // Linux without xvfb-run still launches — the app's training mode reports
        // the window skip instead of masquerading. The launcher path always travels
        // as one argv entry, never through shell interpolation.
        assertEquals(
            listOf("xvfb-run", "-a", "dbus-run-session", "--", launcher),
            aotLauncherCommand("linux", launcher, hasDisplay = false, xvfbAvailable = true, dbusAvailable = true),
        )
        assertEquals(
            listOf("xvfb-run", "-a", launcher),
            aotLauncherCommand("linux", launcher, hasDisplay = false, xvfbAvailable = true, dbusAvailable = false),
        )
        assertEquals(
            listOf(launcher),
            aotLauncherCommand("linux", launcher, hasDisplay = true, xvfbAvailable = true, dbusAvailable = true),
        )
        assertEquals(
            listOf(launcher),
            aotLauncherCommand("linux", launcher, hasDisplay = false, xvfbAvailable = false, dbusAvailable = true),
        )
        assertEquals(
            listOf(launcher),
            aotLauncherCommand("macos", launcher, hasDisplay = false, xvfbAvailable = true, dbusAvailable = true),
        )
        assertEquals(
            listOf(launcher),
            aotLauncherCommand("windows", launcher, hasDisplay = false, xvfbAvailable = true, dbusAvailable = true),
        )
    }

    @Test
    fun `the mac resign plan signs macho files inside out then the bundle`() {
        val root = Files.createTempDirectory("resign").toFile()
        try {
            val app = File(root, "Neutrodyne.app")
            val macho = File(app, "Contents/MacOS/Neutrodyne")
            val dylib = File(app, "Contents/runtime/Contents/Home/lib/libjava.dylib")
            val nested = File(app, "Contents/runtime/Contents/Home/lib/server/libjvm.dylib")
            val jar = File(app, "Contents/app/app.jar")
            val aot = File(app, "Contents/app/neutrodyne.aot")
            listOf(macho, dylib, nested, jar, aot).forEach {
                it.parentFile.mkdirs()
                it.createNewFile()
            }
            // Mach-O magics (thin LE/BE + fat); JAR and the archive stay unsigned.
            macho.writeBytes(byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte(), 1))
            dylib.writeBytes(byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte(), 1))
            nested.writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 1))
            jar.writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 1))

            val plan = macAdHocResignTargets(app)

            assertEquals(app, plan.last(), "the bundle seals last")
            assertEquals(
                setOf(macho, dylib, nested),
                plan.dropLast(1).toSet(),
                "every Mach-O inside the bundle is re-signed, data is not",
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
