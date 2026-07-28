package xyz.lark.app

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {

    @Test
    fun platformNameIsNotBlank() {
        assertTrue(platformName().isNotBlank())
    }

    @Test
    fun platformNameContainsAVersionNumber() {
        assertTrue(platformName().any(Char::isDigit))
    }
}
