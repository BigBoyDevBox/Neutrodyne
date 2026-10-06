// SPDX-License-Identifier: Unlicense
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

/**
 * Shared test-task settings for every JVM test task (09 Gradle test configuration): a fixed locale and an
 * awkward time zone so locale and offset bugs surface on every machine.
 */
internal fun Project.configureNeutrodyneTestTasks() {
    tasks.withType<Test>().configureEach {
        systemProperty("user.language", "de")
        systemProperty("user.country", "DE")
        systemProperty("user.timezone", "America/St_Johns")
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        // Robolectric's instrumented Android classes reach into JDK internals (SharedSecrets and
        // friends); without these opens every host test dies with IllegalAccessException on JDK 21.
        jvmArgs(
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens", "java.base/java.text=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "--add-opens", "java.base/java.net=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
            "--add-opens", "java.base/java.security=ALL-UNNAMED",
            "--add-opens", "java.base/java.time=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.zip=ALL-UNNAMED",
            "--add-opens", "java.base/jdk.internal.loader=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens", "java.base/sun.security.ssl=ALL-UNNAMED",
            "--add-opens", "java.base/sun.security.util=ALL-UNNAMED",
            "--add-opens", "java.base/sun.util.calendar=ALL-UNNAMED",
            "--add-opens", "java.base/sun.util.locale=ALL-UNNAMED",
            "--add-opens", "java.desktop/java.awt.font=ALL-UNNAMED",
            "--add-opens", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED",
        )
    }
}
