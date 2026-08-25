package com.lennon.imagebordercrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropAdjustmentStateTest {
    private val result = BorderResult(10, 20, 30, 40, BorderType.AUTO, 30)

    @Test
    fun eachSideIsLimitedByTheOppositeSideAndKeepsOnePixel() {
        val state = CropAdjustmentState(width = 100, height = 80, initial = result)

        state.update(CropSide.TOP, 999)
        assertEquals(59, state.value(CropSide.TOP))
        assertEquals(20, state.maximum(CropSide.BOTTOM))

        state.update(CropSide.LEFT, 999)
        assertEquals(59, state.value(CropSide.LEFT))
        assertEquals(40, state.maximum(CropSide.RIGHT))
    }

    @Test
    fun negativeValuesClampToZero() {
        val state = CropAdjustmentState(width = 100, height = 80, initial = result)
        state.update(CropSide.BOTTOM, -5)
        state.update(CropSide.RIGHT, -1)

        assertEquals(0, state.value(CropSide.BOTTOM))
        assertEquals(0, state.value(CropSide.RIGHT))
    }

    @Test
    fun resultOnlyBecomesManualWhenAValueChanges() {
        val unchanged = CropAdjustmentState(100, 80, result).toResult(result)
        assertFalse(unchanged.manuallyAdjusted)

        val state = CropAdjustmentState(100, 80, result)
        state.update(CropSide.TOP, 11)
        val changed = state.toResult(result)
        assertTrue(changed.manuallyAdjusted)
        assertEquals(11, changed.top)
    }

    @Test
    fun changingOneSideDoesNotChangeTheOppositeSideOrItsSliderRange() {
        val state = CropAdjustmentState(width = 100, height = 80, initial = result)

        state.update(CropSide.TOP, 25)

        assertEquals(25, state.value(CropSide.TOP))
        assertEquals(20, state.value(CropSide.BOTTOM))
        assertEquals(79, state.rangeMaximum(CropSide.TOP))
        assertEquals(79, state.rangeMaximum(CropSide.BOTTOM))

        state.update(CropSide.LEFT, 35)
        assertEquals(35, state.value(CropSide.LEFT))
        assertEquals(40, state.value(CropSide.RIGHT))
        assertEquals(99, state.rangeMaximum(CropSide.LEFT))
        assertEquals(99, state.rangeMaximum(CropSide.RIGHT))
    }

    @Test
    fun resetReturnsToAutomaticDetectionInsteadOfTheLastManualResult() {
        val automatic = result
        val manual = result.copy(top = 15, right = 35, manuallyAdjusted = true)
        val state = CropAdjustmentState(100, 80, manual, automatic)

        state.update(CropSide.BOTTOM, 12)
        state.resetToAutomatic()
        val reset = state.toResult(manual)

        assertEquals(CropInsets(10, 20, 30, 40), state.insets)
        assertFalse(reset.manuallyAdjusted)
    }

    @Test
    fun zeroInsetDetectionCanStillBeAdjustedManually() {
        val noBorder = result.copy(top = 0, bottom = 0, left = 0, right = 0)
        val state = CropAdjustmentState(100, 80, noBorder, noBorder)

        state.update(CropSide.BOTTOM, 3)

        assertEquals(3, state.value(CropSide.BOTTOM))
        assertTrue(state.toResult(noBorder).manuallyAdjusted)
    }
}
