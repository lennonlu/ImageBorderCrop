package com.lennon.imagebordercrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchSessionTest {
    @Test
    fun savedAndSkippedItemsAdvanceInOrder() {
        val session = BatchSession(listOf("a", "b", "c"))

        assertEquals(1, session.position)
        assertEquals("a", session.currentItem)
        assertEquals("b", session.saveAndAdvance())
        assertEquals(1, session.savedCount)
        assertEquals("c", session.skipAndAdvance())
        assertEquals(1, session.skippedCount)
        assertTrue(session.isLast)
        assertNull(session.saveAndAdvance())
        assertEquals(2, session.savedCount)
    }

    @Test
    fun maximumOfTwentyItemsIsAccepted() {
        val session = BatchSession((1..20).toList())
        assertEquals(20, session.total)
        assertFalse(session.isLast)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oneItemDoesNotCreateBatchMode() {
        BatchSession(listOf("only"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun moreThanTwentyItemsAreRejected() {
        BatchSession((1..21).toList())
    }
}
