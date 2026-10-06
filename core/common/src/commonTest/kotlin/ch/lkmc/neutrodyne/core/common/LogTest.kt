// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogTest {
    private data class Recording(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val t: Throwable?,
    )

    private val records = mutableListOf<Recording>()
    private val sink = LogSink { level, tag, message, t ->
        records += Recording(level, tag, message, t)
    }

    @AfterTest
    fun tearDown() = Log.install()

    @Test
    fun `installed sinks receive level tag message and throwable`() {
        Log.install(sink)
        val t = IllegalStateException("x")
        Log.w("Tag", t) { "hello" }
        assertEquals(listOf(Recording(LogLevel.WARN, "Tag", "hello", t)), records)
    }

    @Test
    fun `the lazy message is not evaluated without sinks`() {
        Log.install()
        var evaluated = false
        Log.d("Tag") {
            evaluated = true
            "never"
        }
        assertFalse(evaluated)
    }

    @Test
    fun `every message passes through the redactor`() {
        Log.install(sink)
        Log.i("Net") { "GET https://u:p@example.com/rss/a8F3kq09ZpLm2xQ?token=abc" }
        assertEquals(
            "GET https://***@example.com/rss/…xQ?token=…",
            records.single().message,
        )
    }

    @Test
    fun `install replaces earlier sinks`() {
        val second = mutableListOf<String>()
        Log.install(sink)
        Log.install(LogSink { _, _, message, _ -> second += message })
        Log.d("Tag") { "hi" }
        assertTrue(records.isEmpty())
        assertEquals(listOf("hi"), second)
    }

    @Test
    fun `a throwing sink does not break other sinks or callers`() {
        val seen = mutableListOf<String>()
        Log.install(
            LogSink { _, _, _, _ -> throw RuntimeException("sink boom") },
            LogSink { _, _, message, _ -> seen += message },
        )
        Log.e("Tag") { "still delivered" }
        assertEquals(listOf("still delivered"), seen)
    }
}
