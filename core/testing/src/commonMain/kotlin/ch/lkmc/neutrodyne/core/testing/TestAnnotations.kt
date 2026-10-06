// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

/**
 * JUnit-style lifecycle annotations for shared test bases (09 Shared helpers). `kotlin.test`'s
 * `BeforeTest`/`AfterTest` are only typealiases to `org.junit.Before`/`After`, which live in
 * `kotlin-test-junit` — an artifact KMP test compilations get automatically but `commonMain` never
 * sees. Expect/actual typealiases inside `:core:testing` produce the real JUnit annotations in the
 * compiled classes, so runners pick them up on both targets with no extra dependency.
 */
@Target(AnnotationTarget.FUNCTION)
expect annotation class BeforeTest()

/** See [BeforeTest]. */
@Target(AnnotationTarget.FUNCTION)
expect annotation class AfterTest()
