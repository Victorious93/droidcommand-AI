package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggerTest {
    @Test
    fun `NoOpLogger never throws and produces no output`() {
        NoOpLogger.debug("x")
        NoOpLogger.info("x")
        NoOpLogger.warn("x")
        NoOpLogger.error("x", cause = RuntimeException("boom"))
    }

    @Test
    fun `ConsoleLogger formats level, message, and sorted fields`() {
        val lines = mutableListOf<String>()
        val logger = ConsoleLogger(minLevel = LogLevel.DEBUG, sink = { lines += it })

        logger.info("something_happened", mapOf("b" to "2", "a" to "1"))

        assertEquals(listOf("INFO something_happened a=1 b=2"), lines)
    }

    @Test
    fun `ConsoleLogger filters out events below minLevel`() {
        val lines = mutableListOf<String>()
        val logger = ConsoleLogger(minLevel = LogLevel.WARN, sink = { lines += it })

        logger.debug("ignored")
        logger.info("ignored")
        logger.warn("kept")

        assertEquals(listOf("WARN kept"), lines)
    }

    @Test
    fun `ConsoleLogger includes the cause when one is given`() {
        val lines = mutableListOf<String>()
        val logger = ConsoleLogger(sink = { lines += it })

        logger.error("it_broke", cause = IllegalStateException("bad state"))

        assertTrue(lines.single().contains("cause=IllegalStateException(bad state)"))
    }
}
