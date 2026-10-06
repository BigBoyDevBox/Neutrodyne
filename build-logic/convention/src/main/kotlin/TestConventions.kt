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
    }
}
