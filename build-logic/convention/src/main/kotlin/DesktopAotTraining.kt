// SPDX-License-Identifier: Unlicense
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * The PO-42 AOT cache wiring (11 AOT cache): the packaged launcher reads the assembly-phase
 * archive through `-XX:AOTCache=$APPDIR/neutrodyne.aot`, and one training launch of the
 * packaged image — its own launcher, so the bundled runtime, classpath and JVM options are
 * exactly the shipped ones — writes it through `-XX:AOTCacheOutput`. The flag ships only on
 * packaging invocations (`-Pneutrodyne.installKind=…`); `run`, tests and the assemble image
 * carry neither flag nor archive.
 */

/** The archive's file name inside the image's app dir ($APPDIR). */
internal const val AOT_CACHE_FILE = "neutrodyne.aot"

/** The production read flag in every packaged .cfg (PO-42). */
internal const val AOT_CACHE_OPTION = "-XX:AOTCache=\$APPDIR/$AOT_CACHE_FILE"

/** The two options that replace the read flag inside the training .cfg. */
internal const val AOT_CACHE_OUTPUT_OPTION = "-XX:AOTCacheOutput=\$APPDIR/$AOT_CACHE_FILE"
internal const val AOT_TRAINING_PROPERTY_OPTION = "-Dcompose.aot.training-run=true"

/** The training run's one-line report (desktopApp's `AotTrainingMode`). */
internal const val AOT_TRAINING_REPORT_PREFIX = "AOT-TRAINING "

/** The per-OS paths of the jpackage app image the training launch needs (11 Resources layout). */
internal data class AotImageLayout(
    /** The packaged launcher, relative to the image root. */
    val launcher: String,
    /** The launcher's configuration file (its java-options list), relative to the image root. */
    val cfg: String,
    /** $APPDIR — the .cfg's directory, where the archive is written and read. */
    val appDir: String,
    /** The bundled runtime's `release` file: the input binding the pinned runtime's identity. */
    val runtimeRelease: String,
)

internal fun aotImageLayout(osWire: String): AotImageLayout =
    when (osWire) {
        "windows" -> {
            AotImageLayout(
                launcher = "Neutrodyne.exe",
                cfg = "app/Neutrodyne.cfg",
                appDir = "app",
                runtimeRelease = "runtime/release",
            )
        }

        "macos" -> {
            AotImageLayout(
                launcher = "Contents/MacOS/Neutrodyne",
                cfg = "Contents/app/Neutrodyne.cfg",
                appDir = "Contents/app",
                runtimeRelease = "Contents/runtime/Contents/Home/release",
            )
        }

        else -> {
            AotImageLayout(
                launcher = "bin/Neutrodyne",
                cfg = "lib/app/Neutrodyne.cfg",
                appDir = "lib/app",
                runtimeRelease = "lib/runtime/release",
            )
        }
    }

/**
 * The training .cfg: the production read flag must not reach the training JVM — it names a
 * file the launch is about to write — so it is dropped and the write flag plus the
 * training-mode property are appended inside the `[JavaOptions]` section. Every other line
 * survives verbatim and in order. A .cfg without the read flag fails closed: the image's
 * launcher would ignore the trained archive.
 */
internal fun aotTrainingCfg(cfg: String): String {
    val readLine = "java-options=$AOT_CACHE_OPTION"
    if (readLine !in cfg.lines()) {
        throw GradleException(
            "the packaged .cfg carries no $AOT_CACHE_OPTION option; " +
                "package with -Pneutrodyne.installKind=… so the launcher reads the cache",
        )
    }
    val out = mutableListOf<String>()
    var inJavaOptions = false
    var injected = false

    fun injectTrainingOptions() {
        if (injected) return
        out += "java-options=$AOT_CACHE_OUTPUT_OPTION"
        out += "java-options=$AOT_TRAINING_PROPERTY_OPTION"
        injected = true
    }
    for (line in cfg.lines()) {
        if (line.startsWith("[")) {
            if (inJavaOptions) injectTrainingOptions()
            inJavaOptions = line == "[JavaOptions]"
        }
        if (line != readLine) out += line
    }
    injectTrainingOptions()
    // The swapped file keeps the image's own line endings — a Windows image's .cfg uses CRLF.
    val newline = if ("\r\n" in cfg) "\r\n" else "\n"
    return out.joinToString(newline, postfix = newline)
}

/**
 * The training launcher's argv (no shell, so the path needs no quoting): the image's own
 * launcher, wrapped under `xvfb-run`/`dbus-run-session` on a headless Linux runner so the
 * windowed journey can run there too. A headless Linux without xvfb-run still launches
 * bare — the app's training mode then reports the window skip as a failure instead of
 * masquerading as a completed run.
 */
internal fun aotLauncherCommand(
    osWire: String,
    launcherPath: String,
    hasDisplay: Boolean,
    xvfbAvailable: Boolean,
    dbusAvailable: Boolean,
): List<String> {
    if (osWire != "linux" || hasDisplay || !xvfbAvailable) return listOf(launcherPath)
    return if (dbusAvailable) {
        listOf("xvfb-run", "-a", "dbus-run-session", "--", launcherPath)
    } else {
        listOf("xvfb-run", "-a", launcherPath)
    }
}

/** The Mach-O magics `codesign` cares about: fat and thin, both endians. */
private val MACHO_MAGICS =
    setOf(
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
        byteArrayOf(0xBE.toByte(), 0xBA.toByte(), 0xFE.toByte(), 0xCA.toByte()),
        byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte()),
        byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCF.toByte()),
        byteArrayOf(0xCE.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()),
        byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()),
    )

/**
 * The ad-hoc reseal order after the archive lands in `Contents/app/` (11 macOS DMG, ad-hoc
 * signing): every Mach-O inside the bundle, deepest first, then the bundle itself last —
 * `codesign` seals code inside out. Data (JARs, the new archive, resources) is not signed.
 */
internal fun macAdHocResignTargets(app: File): List<File> {
    val machos =
        app
            .walkTopDown()
            .filter { it.isFile && it.isMacho() }
            .sortedByDescending { it.relativeTo(app).path.count { c -> c == '/' } }
            .toList()
    return machos + app
}

private fun File.isMacho(): Boolean {
    if (!isFile || length() < 4) return false
    val head = inputStream().use { it.readNBytes(4) }
    return MACHO_MAGICS.any { head.contentEquals(it) }
}

/**
 * One training launch of the packaged app image (PO-42, 11 AOT cache). Registered against
 * `createDistributable`'s image — the same image every `package*` task consumes — so the
 * archive is part of every installer's payload. Runs only when `neutrodyne.installKind`
 * is set; a dev/assemble image stays flag-free and untrained.
 *
 * Declared inputs are the files that identify the training configuration — the launcher
 * (classpath + main class), its .cfg (the JVM options) and the bundled runtime's `release`
 * file (the pinned JDK); the declared output is the archive itself. The .cfg is swapped for
 * the training options for the duration of the launch and restored byte-exact afterwards —
 * it is an input, not an output.
 */
abstract class TrainAotCacheTask : DefaultTask() {
    /** The app image root (`Neutrodyne[.app]`) — internal: the launch inputs below identify it. */
    @get:Internal
    abstract val appImage: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val launcher: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val launcherCfg: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeRelease: RegularFileProperty

    @get:Input
    abstract val targetOs: Property<String>

    @get:Input
    abstract val installKind: Property<String>

    @get:Input
    abstract val expectedInstallKinds: ListProperty<String>

    @get:OutputFile
    abstract val aotArchive: RegularFileProperty

    @TaskAction
    fun train() {
        val kind = installKind.get()
        val expected = expectedInstallKinds.get()
        if (kind !in expected) {
            throw GradleException(
                "trainAotCache: -Pneutrodyne.installKind=$kind is not one of ${expected.joinToString("/")} " +
                    "(11 install kinds)",
            )
        }
        val image = appImage.get().asFile
        val launcherFile = launcher.get().asFile
        if (!launcherFile.isFile) {
            throw GradleException("packaged launcher missing: $launcherFile")
        }
        val cfgFile = launcherCfg.get().asFile
        if (!cfgFile.isFile) {
            throw GradleException("packaged launcher config missing: $cfgFile")
        }
        if (!runtimeRelease.get().asFile.isFile) {
            throw GradleException("bundled runtime missing in the image: ${runtimeRelease.get().asFile}")
        }

        val archive = aotArchive.get().asFile
        archive.delete()

        // The training launch needs the write flag where the read flag sits; the file's
        // original bytes go back on every path — the .cfg is a declared input of this task.
        val originalBytes = cfgFile.readBytes()
        val trainingBytes = aotTrainingCfg(String(originalBytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        cfgFile.writeBytes(trainingBytes)
        try {
            runTrainingLaunch(launcherFile, image)
        } finally {
            cfgFile.writeBytes(originalBytes)
        }
        if (!archive.isFile || archive.length() == 0L) {
            throw GradleException("the training run left no usable AOT archive at $archive")
        }
        logger.lifecycle("trainAotCache: ${archive.length()} bytes at $archive")

        if (targetOs.get() == "macos") {
            resignAdHoc(image)
        }
    }

    private fun runTrainingLaunch(
        launcherFile: File,
        image: File,
    ) {
        val argv =
            aotLauncherCommand(
                targetOs.get(),
                launcherFile.absolutePath,
                hasDisplay(),
                onPath("xvfb-run"),
                onPath("dbus-run-session"),
            )
        logger.lifecycle("trainAotCache: ${argv.joinToString(" ")}")
        // ProcessBuilder inherits this process's environment, so a pre-existing
        // JAVA_TOOL_OPTIONS reaches the training JVM unchanged; the training options
        // travel through the swapped .cfg like every other launcher option. Output
        // goes to a file, not a pipe: a stalled child cannot deadlock the waitFor.
        val logFile = File.createTempFile("aot-training", ".log")
        val process =
            try {
                ProcessBuilder(argv)
                    .directory(image)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .start()
            } catch (t: Throwable) {
                logFile.delete()
                throw t
            }
        val exit =
            try {
                if (!process.waitFor(TRAINING_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    throw GradleException(
                        "the AOT training run did not finish within $TRAINING_TIMEOUT_MINUTES min",
                    )
                }
                process.exitValue()
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        val output = logFile.readText().also { logFile.delete() }
        output
            .lineSequence()
            .filter { it.startsWith(AOT_TRAINING_REPORT_PREFIX) }
            .forEach { logger.lifecycle(it) }
        if (exit != 0) {
            throw GradleException(
                "the AOT training run exited $exit:\n${output.takeLast(LOG_TAIL_CHARS)}",
            )
        }
        val report =
            output.lineSequence().firstOrNull { it.startsWith(AOT_TRAINING_REPORT_PREFIX) }
                ?: throw GradleException(
                    "the AOT training run produced no $AOT_TRAINING_REPORT_PREFIX line:\n" +
                        output.takeLast(LOG_TAIL_CHARS),
                )
        if ("\"failed\"" in report) {
            throw GradleException("the AOT training journey failed: $report")
        }
    }

    /** A display is always reachable on macOS/Windows runners; Linux needs DISPLAY or Wayland. */
    private fun hasDisplay(): Boolean =
        targetOs.get() != "linux" ||
            !System.getenv("DISPLAY").isNullOrBlank() ||
            !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()

    private fun onPath(executable: String): Boolean =
        System
            .getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.any { dir -> File(dir, executable).canExecute() } == true

    /**
     * The archive inside `Contents/app/` breaks jpackage's ad-hoc seal (11 macOS DMG, ad-hoc
     * signing): re-sign every Mach-O inside out and the bundle itself, then verify before
     * `packageDmg`/`mac-zip.sh` archives it — the old signature is never presumed valid.
     */
    private fun resignAdHoc(image: File) {
        for (target in macAdHocResignTargets(image)) {
            runChecked("codesign", "--force", "--sign", "-", target.absolutePath)
        }
        runChecked("codesign", "--verify", "--deep", "--strict", image.absolutePath)
    }

    private fun runChecked(vararg argv: String) {
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (process.waitFor() != 0) {
            throw GradleException("${argv.joinToString(" ")} failed:\n$output")
        }
    }

    private companion object {
        /** The app's own watchdog halts a stuck run after 60 s; this is the outer bound. */
        private const val TRAINING_TIMEOUT_MINUTES = 3L
        private const val LOG_TAIL_CHARS = 4000
    }
}
