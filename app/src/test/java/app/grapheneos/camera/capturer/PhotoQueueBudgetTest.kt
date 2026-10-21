package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class PhotoQueueBudgetTest {
    @Test fun acquisitionCannotOverbookInputsWhileOlderPhotosProcess() {
        val budget = PhotoQueueBudget(8, 500)
        assertTrue(budget.reserve("night1", 240))
        assertTrue(budget.reserve("night2", 240))
        assertFalse(budget.reserve("night3", 240))
        budget.commit("night1", 200)
        assertTrue(budget.reserve("portrait", 20))
        budget.release("night1")
        assertTrue(budget.reserve("night3", 240))
    }

    @Test fun smallJpegsStillHaveABoundedJobCountAndCancellationReleasesOneSlot() {
        val budget = PhotoQueueBudget(2, 1000)
        assertTrue(budget.reserve("one", 10)); assertTrue(budget.reserve("two", 10))
        assertFalse(budget.reserve("three", 10))
        budget.release("one"); budget.release("one")
        assertTrue(budget.reserve("three", 10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun capturedInputCannotGrowPastItsAcquisitionReservation() {
        val budget = PhotoQueueBudget(2, 1000)
        budget.reserve("one", 100); budget.commit("one", 101)
    }
}
