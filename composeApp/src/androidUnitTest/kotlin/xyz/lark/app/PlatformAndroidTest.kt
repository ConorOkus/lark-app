package xyz.lark.app

import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs against real Build.VERSION values via Robolectric. The plain unit-test
 * environment stubs Build.VERSION.RELEASE/SDK_INT to null/0, which the shared
 * smoke test cannot distinguish from a working implementation.
 */
@RunWith(RobolectricTestRunner::class)
class PlatformAndroidTest {

    @Test
    fun platformNameReportsRealAndroidVersion() {
        val name = platformName()
        assertTrue(
            name.matches(Regex("""Android [\d.]+ \(API [1-9]\d*\)""")),
            "platformName() leaked stubbed Build values: $name",
        )
    }
}
