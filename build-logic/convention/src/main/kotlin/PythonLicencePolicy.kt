// SPDX-License-Identifier: Unlicense
import com.chaquo.python.ChaquopyExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.tomlj.Toml
import org.tomlj.TomlTable
import java.io.File
import java.util.TreeSet

/**
 * The licence policy for `python-components.lock` of `:youtube:ytdlp` and `:youtube:ytdlp-desktop`
 * (01 Python and native components). Pure logic lives in [PythonLicencePolicy] so build-logic unit tests cover it;
 * [CheckPythonLicencesTask] wires it to the lockfile, the Chaquopy extension and the AboutLibraries definitions.
 */
internal object PythonLicencePolicy {
    const val SCHEMA = 1L

    /**
     * The Python allow-list of 01: `MPL-2.0` is *not* here — it is only ever legal for `kind = "data"`
     * (unmodified data, both locks) or the desktop-only `kind = "pbs-patches"` case, enforced in code below.
     */
    val ALLOWED_LICENCES: Set<String> =
        setOf(
            "Unlicense",
            "MIT",
            "ISC",
            "Apache-2.0",
            "Apache-2.0 WITH LLVM-exception",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "0BSD",
            "PSF-2.0",
            "Python-2.0",
            "Unicode-3.0",
            "Zlib",
            "bzip2-1.0.6",
            "blessing",
            "LicenseRef-PublicDomain",
        )

    val KINDS: Set<String> = setOf("runtime", "native", "python", "data", "pbs-patches")

    /** Anything matching this is rejected outright unless it is an allowed OR-elected alternative. */
    val BANNED_LICENCE = Regex("GPL|LGPL|AGPL|Sleepycat")

    data class Component(
        val name: String,
        val version: String?,
        val origin: String?,
        val licence: String,
        val elected: String?,
        val kind: String,
        val aboutLibrariesId: String,
    )

    data class PbsSource(
        val tag: String,
        val name: String?,
        val url: String?,
        val sha256: String?,
    )

    data class Lock(
        val chaquopy: String?,
        val python: String?,
        val pip: List<String>,
        val pbs: String?,
        val pbsSource: PbsSource?,
        val components: List<Component>,
    )

    fun parse(text: String): Lock {
        val parsed = Toml.parse(text)
        val schema =
            parsed.getLong("schema")
                ?: throw GradleException("python-components.lock: missing `schema`")
        if (schema != SCHEMA) throw GradleException("python-components.lock: unsupported schema $schema")

        val pbsTable = parsed.getTable("pbsSource")
        val components = parsed.getArray("component")?.toList() ?: emptyList()
        return Lock(
            chaquopy = parsed.getString("chaquopy"),
            python = parsed.getString("python"),
            pip = parsed.getArray("pip")?.toList()?.map { it.toString() } ?: emptyList(),
            pbs = parsed.getString("pbs"),
            pbsSource =
                pbsTable?.let {
                    PbsSource(
                        tag = it.getString("tag") ?: "",
                        name = it.getString("name"),
                        url = it.getString("url"),
                        sha256 = it.getString("sha256"),
                    )
                },
            components =
                components.filterIsInstance<TomlTable>().map { c ->
                    val name = c.getString("name") ?: throw GradleException("lock component without `name`")
                    Component(
                        name = name,
                        version = c.getString("version"),
                        origin = c.getString("origin"),
                        licence =
                            c.getString("licence")
                                ?: throw GradleException("lock component $name without `licence`"),
                        elected = c.getString("elected"),
                        kind = c.getString("kind") ?: "runtime",
                        aboutLibrariesId =
                            c.getString("aboutLibrariesId")
                                ?: throw GradleException("lock component $name without `aboutLibrariesId`"),
                    )
                },
        )
    }

    /**
     * Returns the list of policy violations. `desktopLock` gates the `kind = "pbs-patches"` MPL-2.0 exception.
     * `expectedChaquopy`/`expectedPython`/`expectedPip` carry the build-side values while `:youtube:ytdlp`
     * applies Chaquopy (S7); null means "not checked".
     */
    fun violations(
        lock: Lock,
        desktopLock: Boolean,
        expectedChaquopy: String?,
        expectedPython: String?,
        expectedPip: List<String>?,
        aboutLibrariesIds: Set<String>,
    ): List<String> {
        val problems = mutableListOf<String>()

        if (expectedChaquopy != null && lock.chaquopy != expectedChaquopy) {
            problems += "lockfile chaquopy = ${lock.chaquopy} but the build applies $expectedChaquopy"
        }
        if (expectedPython != null &&
            (lock.python == null || (lock.python != expectedPython && !lock.python.startsWith("$expectedPython.")))
        ) {
            problems += "lockfile python = ${lock.python} but chaquopy.defaultConfig.version = $expectedPython"
        }
        if (expectedPip != null && lock.pip.sorted() != expectedPip.sorted()) {
            problems += "lockfile pip = ${lock.pip} but the build's pip requirements are $expectedPip"
        }

        for (c in lock.components) {
            if (c.version.isNullOrBlank()) problems += "component ${c.name} lacks `version`"
            if (c.origin.isNullOrBlank()) problems += "component ${c.name} lacks `origin`"
            if (c.kind !in KINDS) problems += "component ${c.name} has unknown kind ${c.kind}"
            if (c.aboutLibrariesId !in aboutLibrariesIds) {
                problems += "component ${c.name} has no AboutLibraries definition for '${c.aboutLibrariesId}'"
            }
            problems += licenceViolations(c, desktopLock, lock)
        }
        return problems
    }

    private fun licenceViolations(
        c: Component,
        desktopLock: Boolean,
        lock: Lock,
    ): List<String> {
        val problems = mutableListOf<String>()
        val alternatives = c.licence.split(Regex("\\s+OR\\s+")).map { it.trim().removePrefix("(").removeSuffix(")") }
        if (alternatives.size > 1) {
            // An OR expression is accepted only with an elected, allowed alternative (01); banned identifiers
            // are still checked so `MIT OR GPL-3.0` cannot pass silently.
            val elected = c.elected
            if (elected == null) {
                problems += "component ${c.name}: OR licence '${c.licence}' has no `elected`"
            } else if (elected !in alternatives) {
                problems += "component ${c.name}: elected '$elected' is not an alternative of '${c.licence}'"
            } else {
                problems += singleLicenceViolations(c, elected, desktopLock, lock)
            }
        } else {
            problems += singleLicenceViolations(c, c.licence.trim(), desktopLock, lock)
        }
        return problems
    }

    private fun singleLicenceViolations(
        c: Component,
        licence: String,
        desktopLock: Boolean,
        lock: Lock,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (BANNED_LICENCE.containsMatchIn(licence)) {
            problems += "component ${c.name}: '$licence' contains a banned licence " +
                "(GPL/LGPL/AGPL/Sleepycat never ships, D3)"
        }
        when {
            licence in ALLOWED_LICENCES -> Unit

            licence == "MPL-2.0" && c.kind == "data" -> Unit

            // unmodified data only (D3)
            licence == "MPL-2.0" && c.kind == "pbs-patches" -> problems += pbsViolations(c, desktopLock, lock)

            else -> problems += "component ${c.name}: licence '$licence' is not on the Python allow-list (01)"
        }
        if (c.kind == "data" && licence != "MPL-2.0" && licence !in ALLOWED_LICENCES) {
            problems += "component ${c.name}: kind=data still needs an allow-listed licence ('$licence')"
        }
        if (c.kind == "pbs-patches" && !desktopLock) {
            problems += "component ${c.name}: kind=pbs-patches exists only in the desktop lock (PO-48)"
        }
        if (c.kind == "pbs-patches" && licence == "MPL-2.0" && !desktopLock) {
            problems += "component ${c.name}: MPL-2.0 patches are never allowed in the Android lock"
        }
        return problems
    }

    private fun pbsViolations(
        c: Component,
        desktopLock: Boolean,
        lock: Lock,
    ): List<String> {
        if (!desktopLock) return listOf("component ${c.name}: pbs-patches belongs to the desktop lock only")
        val problems = mutableListOf<String>()
        val pbsSource =
            lock.pbsSource
                ?: return listOf("component ${c.name}: pbs-patches needs the lock's [pbsSource] entry")
        if (pbsSource.tag.isBlank() || pbsSource.tag != lock.pbs) {
            problems += "component ${c.name}: pbsSource.tag '${pbsSource.tag}' differs from " +
                "the lock's PBS release tag '${lock.pbs}'"
        }
        if (pbsSource.sha256.isNullOrBlank() || !pbsSource.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            problems += "component ${c.name}: pbsSource lacks the source tarball SHA-256"
        }
        return problems
    }
}

/** `checkPythonLicences` of one engine host (01 Python and native components). */
abstract class CheckPythonLicencesTask : DefaultTask() {
    @get:Input
    abstract val modulePath: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    /** true for `:youtube:ytdlp-desktop` — enables the `pbs-patches` MPL-2.0 exception. */
    @get:Input
    abstract val desktopLock: Property<Boolean>

    /** The shell's `config/libraries` manual definitions — one `.json` file stem per `aboutLibrariesId`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val aboutLibrariesDefinitions: org.gradle.api.file.ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val expectedChaquopy: Property<String>

    @get:Input
    @get:Optional
    abstract val expectedPython: Property<String>

    @get:Input
    @get:Optional
    abstract val expectedPip: ListProperty<String>

    init {
        group = "verification"
        description = "Python lockfile against the licence allow-list and the build's engine stack (01)."
    }

    @TaskAction
    fun check() {
        val lock = PythonLicencePolicy.parse(lockFile.get().asFile.readText())
        val definedIds =
            aboutLibrariesDefinitions.files
                .mapTo(TreeSet()) { it.nameWithoutExtension }
        val problems =
            PythonLicencePolicy.violations(
                lock,
                desktopLock = desktopLock.get(),
                expectedChaquopy = expectedChaquopy.orNull,
                expectedPython = expectedPython.orNull,
                expectedPip = if (expectedPip.isPresent) expectedPip.get() else null,
                aboutLibrariesIds = definedIds,
            )
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Python licence policy violations in ${lockFile.get().asFile.name}:\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("checkPythonLicences: ${modulePath.get()} lock clean (${lock.components.size} components)")
    }
}

/**
 * `verifyBundledYtDlp` — the OpenPGP + SHA-256 verification of the vendored `youtube/ytdlp/engine/` release.
 * A no-op until M9a vendors yt-dlp (01 Python and native components, 04 Trust chain).
 */
abstract class VerifyBundledYtDlpTask : DefaultTask() {
    @get:Internal
    abstract val engineDir: DirectoryProperty

    init {
        group = "verification"
        description = "Verifies the vendored yt-dlp release (signature + hash); no-op until M9a vendors it."
    }

    @TaskAction
    fun verify() {
        val bundled = engineDir.orNull?.asFile?.let { File(it, "bundled.json") }
        if (bundled == null || !bundled.isFile) {
            logger.lifecycle("verifyBundledYtDlp: no vendored yt-dlp yet (M9a); nothing to check")
            return
        }
        throw GradleException(
            "youtube/ytdlp/engine/ holds a vendored release but verifyBundledYtDlp is still a stub — implement the " +
                "OpenPgpSignatureCheck + SHA-256 verification before vendoring (04 Trust chain)",
        )
    }
}

/**
 * Registers `checkPythonLicences` and `verifyBundledYtDlp` for one engine host. `aboutLibrariesDir` is the
 * owning shell's manual-definition directory (`app/config/libraries`, `desktopApp/config/libraries`).
 */
internal fun Project.registerPythonPolicy(desktopLock: Boolean) {
    val checkTask =
        tasks.register<CheckPythonLicencesTask>("checkPythonLicences") {
            group = "verification"
            modulePath.set(path)
            lockFile.set(layout.projectDirectory.file("python-components.lock"))
            this.desktopLock.set(desktopLock)
            val shell = if (desktopLock) "desktopApp" else "app"
            val defsDir = rootProject.layout.projectDirectory.dir("$shell/config/libraries")
            aboutLibrariesDefinitions.from(fileTree(defsDir) { include("*.json") })
        }

    if (!desktopLock) {
        pluginManager.withPlugin("com.chaquo.python") {
            afterEvaluate {
                val chaquopy = extensions.getByType<ChaquopyExtension>()
                val expected = chaquopy.defaultConfig

                @Suppress("UNCHECKED_CAST")
                val pipReqs =
                    expected.pip.javaClass
                        .getMethod("getReqs\$gradle") // internal Chaquopy API; no public getter exists
                        .invoke(expected.pip) as? List<*> ?: emptyList<Any>()
                checkTask.configure {
                    expectedChaquopy.set(libs.version("chaquopy"))
                    expected.version?.let { expectedPython.set(it) }
                    expectedPip.set(pipReqs.map { it.toString() })
                }
            }
        }
    }

    tasks.register<VerifyBundledYtDlpTask>("verifyBundledYtDlp") {
        engineDir.set(layout.projectDirectory.dir("engine"))
    }

    tasks.matching { it.name == "check" }.configureEach { dependsOn(checkTask) }
}
