package com.paul.voicekeys

import org.junit.Assert.*
import org.junit.Test

class CommandPipelineTest {
    @Test fun countingBurstPreservesFourSeparateCommas() {
        val queue = KeyPacingQueue()
        defaultMappings().take(4).forEach { queue.enqueue(it.key) }
        val sent = mutableListOf<OutputKey>()
        for (time in listOf(0L, 200L, 400L, 600L)) {
            assertEquals(0L, queue.waitMillis(time))
            sent += queue.take(time)
        }
        assertEquals(List(4) { OutputKey.COMMA }, sent)
        assertNull(queue.waitMillis(1000))
    }

    @Test fun normalFivePerSecondCadenceDoesNotAccumulateDelay() {
        val queue = KeyPacingQueue()
        repeat(40) { i ->
            val now = i * 200L
            queue.enqueue(OutputKey.COMMA)
            assertEquals(0L, queue.waitMillis(now))
            queue.take(now)
            assertEquals(0, queue.size)
        }
    }

    @Test fun repeatedIdenticalCommandsAreNotDeduplicated() {
        val queue = KeyPacingQueue()
        repeat(4) { queue.enqueue(OutputKey.SPACE) }
        repeat(4) { assertEquals(OutputKey.SPACE, queue.take(it * 40L)) }
        assertEquals(0, queue.size)
    }

    @Test fun commaLimitSurvivesInterveningOtherKeys() {
        val queue = KeyPacingQueue()
        queue.enqueue(OutputKey.COMMA)
        queue.take(100)
        queue.enqueue(OutputKey.SPACE)
        assertEquals(0L, queue.waitMillis(140))
        queue.take(140)
        queue.enqueue(OutputKey.COMMA)
        assertEquals(120L, queue.waitMillis(180))
        assertEquals(OutputKey.COMMA, queue.take(300))
    }

    @Test fun stoppingDiscardsQueuedKeysWithoutBypassingCommaSpacing() {
        val queue = KeyPacingQueue()
        repeat(4) { queue.enqueue(OutputKey.COMMA) }
        queue.take(100)
        queue.clear()
        assertNull(queue.waitMillis(101))
        queue.enqueue(OutputKey.COMMA)
        assertEquals(199L, queue.waitMillis(101))
        assertEquals(OutputKey.COMMA, queue.take(300))
    }

    @Test fun fifoDoesNotLetStopOvertakePendingCountingWords() {
        val queue = KeyPacingQueue()
        queue.enqueue(OutputKey.COMMA)
        queue.enqueue(OutputKey.COMMA)
        queue.enqueue(OutputKey.SPACE)
        assertEquals(OutputKey.COMMA, queue.take(0))
        assertEquals(160L, queue.waitMillis(40))
        assertEquals(OutputKey.COMMA, queue.take(200))
        assertEquals(OutputKey.SPACE, queue.take(240))
    }

    @Test fun excessiveBacklogFailsExplicitly() {
        val queue = KeyPacingQueue()
        repeat(16) { queue.enqueue(OutputKey.COMMA) }
        assertThrows(IllegalStateException::class.java) { queue.enqueue(OutputKey.COMMA) }
    }

    @Test fun mappedKeysUseActualKeyboardUsagesAndNoModifiers() {
        assertEquals(0x36, OutputKey.COMMA.report()[2].toInt())
        assertEquals(0x2c, OutputKey.SPACE.report()[2].toInt())
        assertEquals(0x37, OutputKey.PERIOD.report()[2].toInt())
        OutputKey.entries.forEach { key ->
            assertEquals(8, key.report().size)
            assertEquals(0, key.report()[0].toInt())
            assertTrue(key.report().drop(3).all { it.toInt() == 0 })
        }
    }

    @Test fun invalidWordsCannotInjectKeywordConfiguration() {
        for (word in listOf("", "one two", "one/two", "one #0.0", "@stop", "1", "one\nstop")) {
            assertThrows(IllegalArgumentException::class.java) { validateWord(word) }
        }
        assertEquals("pause", validateWord(" PAUSE "))
    }
}
