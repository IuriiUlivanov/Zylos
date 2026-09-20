package rs.zylos.app.ui.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rs.zylos.app.viewmodel.SheetMode

class SheetAnchorsTest {
    @Test
    fun swipeUpStopsAtFull() {
        assertEquals(SheetStep.Half, SheetAnchors.next(SheetStep.Minimal))
        assertEquals(SheetStep.Full, SheetAnchors.next(SheetStep.Half))
        assertEquals(SheetStep.Full, SheetAnchors.next(SheetStep.Full))
    }

    @Test
    fun swipeDownStopsAtMinimal() {
        assertEquals(SheetStep.Half, SheetAnchors.previous(SheetStep.Full))
        assertEquals(SheetStep.Minimal, SheetAnchors.previous(SheetStep.Half))
        assertEquals(SheetStep.Minimal, SheetAnchors.previous(SheetStep.Minimal))
    }

    @Test
    fun openStartsAtStepTwo() {
        assertEquals(SheetStep.Half, SheetAnchors.initialStep())
    }

    @Test
    fun stepOneHidesBuildingBodyAndSubtitle() {
        assertFalse(SheetAnchors.bodyVisible(SheetMode.Building, SheetStep.Minimal))
        assertFalse(SheetAnchors.headerSubtitleVisible(SheetMode.Building, SheetStep.Minimal))
        assertTrue(SheetAnchors.bodyVisible(SheetMode.Building, SheetStep.Half))
        assertTrue(SheetAnchors.headerSubtitleVisible(SheetMode.Building, SheetStep.Half))
        assertTrue(SheetAnchors.bodyVisible(SheetMode.Building, SheetStep.Full))
    }

    @Test
    fun orgHidesCategoryOnStepOne() {
        assertFalse(SheetAnchors.headerSubtitleVisible(SheetMode.Organization, SheetStep.Minimal))
        assertFalse(SheetAnchors.bodyVisible(SheetMode.Organization, SheetStep.Minimal))
        assertTrue(SheetAnchors.bodyVisible(SheetMode.Organization, SheetStep.Half))
        assertTrue(SheetAnchors.headerSubtitleVisible(SheetMode.Organization, SheetStep.Full))
    }

    @Test
    fun peekHasNoBody() {
        assertFalse(SheetAnchors.headerSubtitleVisible(SheetMode.Peek, SheetStep.Minimal))
        assertTrue(SheetAnchors.headerSubtitleVisible(SheetMode.Peek, SheetStep.Half))
        assertFalse(SheetAnchors.bodyVisible(SheetMode.Peek, SheetStep.Half))
        assertFalse(SheetAnchors.bodyVisible(SheetMode.Peek, SheetStep.Full))
    }

    @Test
    fun searchListShowsBodyFromStepTwo() {
        assertFalse(SheetAnchors.bodyVisible(SheetMode.SearchList, SheetStep.Minimal))
        assertTrue(SheetAnchors.bodyVisible(SheetMode.SearchList, SheetStep.Half))
        assertTrue(SheetAnchors.headerSubtitleVisible(SheetMode.SearchList, SheetStep.Half))
    }
}
