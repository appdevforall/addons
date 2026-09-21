package com.itsaky.androidide.plugins.aicore.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for [AgentState.Executing] — the step counter and the timing estimate behind the
 * status line. The words around those figures are string resources rendered by the fragment, so
 * what is testable here is the arithmetic.
 */
class AgentStateExecutingTest {

    @Test
    fun givenAFreshState_whenCreated_thenItCarriesTheStepAndNoElapsedTime() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file"
        )

        assertEquals(0, state.currentStepIndex)
        assertEquals(5, state.totalSteps)
        assertEquals("Reading file", state.description)
        assertNotNull(state.startTime)
        assertEquals(0, state.elapsedMillis)
    }

    @Test
    fun givenAStepIndex_whenCounted_thenTheStepNumberIsOneBased() {
        assertEquals(1, AgentState.Executing(0, 5, "First").stepNumber)
        assertEquals(3, AgentState.Executing(2, 5, "Middle").stepNumber)
        assertEquals(5, AgentState.Executing(4, 5, "Last").stepNumber)
    }

    @Test
    fun givenNoElapsedTime_whenEstimating_thenTheTotalIsZero() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = 0
        )

        assertEquals(0L, state.estimatedTotalMillis)
    }

    @Test
    fun givenTheFirstStep_whenEstimating_thenItProjectsThatStepAcrossAllOfThem() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = 500
        )

        assertEquals(2500L, state.estimatedTotalMillis)
    }

    @Test
    fun givenTwoStepsDone_whenEstimating_thenItProjectsTheAveragePerStep() {
        // 2000ms over 2 steps is 1000ms a step, so 5 steps is 5000ms.
        val state = AgentState.Executing(
            currentStepIndex = 1,
            totalSteps = 5,
            description = "Processing",
            elapsedMillis = 2000
        )

        assertEquals(5000L, state.estimatedTotalMillis)
    }

    @Test
    fun givenAClockThatRanBackwards_whenEstimating_thenTheTotalIsZeroRatherThanNegative() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = -100
        )

        assertEquals(0L, state.estimatedTotalMillis)
    }

    @Test
    fun givenAnExistingState_whenCopiedWithNewElapsedTime_thenTheOriginalIsUntouched() {
        val originalState = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = 0
        )

        val copiedState = originalState.copy(elapsedMillis = 1000)

        assertEquals(0, originalState.elapsedMillis)
        assertEquals(1000, copiedState.elapsedMillis)
        assertEquals(originalState.currentStepIndex, copiedState.currentStepIndex)
        assertEquals(originalState.totalSteps, copiedState.totalSteps)
        assertEquals(originalState.description, copiedState.description)
    }

    @Test
    fun givenACustomStartTime_whenCreated_thenItIsKeptAsPassed() {
        val customStartTime = System.currentTimeMillis() - 10000
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            startTime = customStartTime,
            elapsedMillis = 5000
        )

        assertEquals(customStartTime, state.startTime)
        assertEquals(5000, state.elapsedMillis)
    }

    @Test
    fun givenADescriptionWithPunctuation_whenRead_thenItIsPassedThroughUnchanged() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 2,
            description = "Read: app/src/main.kt"
        )

        assertEquals("Read: app/src/main.kt", state.description)
        assertEquals(1, state.stepNumber)
    }
}
