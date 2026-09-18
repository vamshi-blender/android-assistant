package com.vamshi.aiassistant.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveCaptionsTest {
    @Test fun overlappingSpeakersKeepIndependentRows() {
        val captions = LiveCaptions()
        val user = captions.append(true, "Hello", 100, 300)
        val assistant = captions.append(false, "Hi", 200, 400)
        val updated = captions.append(true, " there", 300, 500)
        assertNotEquals(user.id, assistant.id)
        assertEquals(user.id, updated.id)
        assertEquals("Hello there", updated.text)
    }

    @Test fun lateFragmentsUpdateEarlierRowsInTimestampOrder() {
        val captions = LiveCaptions()
        val first = captions.append(true, "world", 500, 800)
        val second = captions.append(true, "Another turn", 4000, 5000)
        val late = captions.append(true, "Hello ", 100, 500)
        assertNotEquals(first.id, second.id)
        assertEquals(first.id, late.id)
        assertEquals("Hello world", late.text)
    }
}
