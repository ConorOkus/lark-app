package xyz.lark.app.ui.screens.receive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [fakeQrCells] to the reference pattern produced by the spec's JavaScript `qrCells`
 * loop (`docs/design/lark-wallet/LARK Wallet.dc.html`), captured by running that exact
 * script under Node. The port must match cell-for-cell — including JS double rounding of
 * the LCG products, which diverges from exact integer math after the first few cells.
 */
class FakeQrCellsTest {

    // 21 rows of 21 cells, row-major, '1' = on.
    private val reference = (
        "111111100010001111111" +
            "100001100100111001011" +
            "101111101000101111111" +
            "101111101000001011111" +
            "101110101001001111101" +
            "110100100110111110111" +
            "111111111001011111111" +
            "111110101000000010100" +
            "011010110001110101110" +
            "110110011100011110101" +
            "001101110011110101011" +
            "011100100101011011110" +
            "110001110010000001110" +
            "000100000010110000111" +
            "111111100001000000011" +
            "100001100000011011000" +
            "101111100101100001001" +
            "101110110010101010000" +
            "101111100110100111101" +
            "100110110011100001111" +
            "111111101110100010010"
        ).map { it == '1' }

    private val cells = fakeQrCells()

    @Test
    fun matchesTheSpecScriptCellForCell() {
        assertEquals(QR_GRID * QR_GRID, cells.size)
        assertEquals(reference, cells)
    }

    @Test
    fun finderSquaresReadAsRingsWithSolidCenters() {
        for ((fx, fy) in listOf(0 to 0, 14 to 0, 0 to 14)) {
            // Four ring corners are always on.
            assertTrue(cellAt(fx, fy))
            assertTrue(cellAt(fx + 6, fy))
            assertTrue(cellAt(fx, fy + 6))
            assertTrue(cellAt(fx + 6, fy + 6))
            // The 3x3 center is solid.
            for (dx in 2..4) {
                for (dy in 2..4) {
                    assertTrue(cellAt(fx + dx, fy + dy), "center ($fx+$dx, $fy+$dy)")
                }
            }
        }
    }

    @Test
    fun doubleRoundingIsPreserved() {
        // Exact-integer LCG math turns this cell on; the JS reference (double math)
        // leaves it off. Guards against "simplifying" the port to Long arithmetic.
        assertFalse(cellAt(x = 8, y = 0))
    }

    private fun cellAt(x: Int, y: Int): Boolean = cells[y * QR_GRID + x]
}
