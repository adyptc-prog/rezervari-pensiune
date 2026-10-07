package com.example.management_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundStartTest {
    @Test
    fun `marcile Xiaomi sunt recunoscute`() {
        assertTrue(BackgroundStart.isXiaomi("Xiaomi"))
        assertTrue(BackgroundStart.isXiaomi("Redmi"))
        assertTrue(BackgroundStart.isXiaomi("POCO"))
        assertFalse(BackgroundStart.isXiaomi("samsung"))
        assertFalse(BackgroundStart.isXiaomi("Google"))
    }
}
