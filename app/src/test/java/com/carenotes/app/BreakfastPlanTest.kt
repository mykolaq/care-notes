package com.carenotes.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BreakfastPlanTest {
    private val day = LocalDate.of(2026, 10, 15)
    private val wake = Prescription(id = "wake", name = "Натощак", dose = "1", schedule = "", doctor = "", notes = "", startOn = day, breakfastStage = "wake", breakfastMinutes = 30)
    private val before = wake.copy(id = "before", breakfastStage = "before", breakfastMinutes = 10)
    private val during = wake.copy(id = "with", breakfastStage = "with", breakfastMinutes = 0)
    private val after = wake.copy(id = "after", breakfastStage = "after", breakfastMinutes = 15)
    private val items = listOf(wake, before, during, after)
    private fun state(at: Long = 1_000_000L) = BreakfastState(day, at, items.map { it.id }.toSet())
    private fun min(n: Int) = n * 60_000L

    @Test fun wakingDoesNotConfirmTheFirstDose() {
        val plan = BreakfastPlan(items, state())
        assertNull(plan.wakeReady())
        assertNull(plan.due(before))
        assertFalse(plan.canStartMeal(Long.MAX_VALUE))
        assertTrue(plan.canTake(wake, state().wokeAt))
    }
    @Test fun intervalsFollowActualDoseRatherThanWakeTime() {
        val s = state().copy(taken = mapOf("wake" to 1_000_000L + min(12)))
        val plan = BreakfastPlan(items, s)
        assertEquals(s.wokeAt + min(42), plan.wakeReady())
        assertEquals(s.wokeAt + min(32), plan.beforeDue(before))
    }
    @Test fun lateDoseBeforeFoodMovesBreakfastLater() {
        val s = state().copy(taken = mapOf("wake" to 1_000_000L, "before" to 1_000_000L + min(29)))
        val plan = BreakfastPlan(items, s)
        assertEquals(s.wokeAt + min(39), plan.mealReady())
        assertFalse(plan.canStartMeal(s.wokeAt + min(30)))
        assertTrue(plan.canStartMeal(s.wokeAt + min(39)))
    }
    @Test fun severalWakeDosesUseTheLatestRequiredInterval() {
        val second = wake.copy(id = "second", breakfastMinutes = 45)
        val s = state().copy(ids = state().ids + "second", taken = mapOf("wake" to 1_000_000L, "second" to 1_000_000L + min(5)))
        assertEquals(s.wokeAt + min(50), BreakfastPlan(items + second, s).wakeReady())
    }
    @Test fun afterFoodCountsFromActualMealEnd() {
        val s = state().copy(mealAt = 2_000_000, finishedAt = 3_000_000)
        val plan = BreakfastPlan(items, s)
        assertEquals(3_000_000L + min(15), plan.due(after))
        assertFalse(plan.canTake(after, 3_000_000L + min(14)))
        assertTrue(plan.canTake(after, 3_000_000L + min(15)))
    }
    @Test fun mealCannotFinishBeforeDuringFoodDosesAreConfirmed() {
        val s = state().copy(mealAt = 2_000_000)
        assertFalse(BreakfastPlan(items, s).canFinishMeal())
        assertTrue(BreakfastPlan(items, s.copy(taken = mapOf("with" to 2_100_000))).canFinishMeal())
    }
    @Test fun confirmationCannotBeRepeated() {
        val s = state().copy(taken = mapOf("wake" to state().wokeAt))
        assertFalse(BreakfastPlan(items, s).canTake(wake, Long.MAX_VALUE))
    }
    @Test fun newAndDeletedMedicinesDoNotCreateUnexpectedDoses() {
        val extra = wake.copy(id = "new")
        val plan = BreakfastPlan(listOf(before, during, after, extra), state())
        assertFalse(plan.items.any { it.id == "new" || it.id == "wake" })
    }
    @Test fun endedCoursesAndOffDaysAreExcluded() {
        val ended = wake.copy(startOn = day.minusDays(2), courseDays = 1)
        val offDay = before.copy(startOn = day.minusDays(1), intervalDays = 2)
        assertTrue(BreakfastPlan(listOf(ended, offDay), state()).items.isEmpty())
    }
    @Test fun routineWorksForDifferentWakeTimes() {
        listOf(8, 10).forEach { hour ->
            val at = hour * 3_600_000L
            val s = state(at).copy(taken = mapOf("wake" to at, "before" to at + min(20)))
            assertEquals(at + min(30), BreakfastPlan(items, s).mealReady())
        }
    }
    @Test fun earlyStagesCannotBeTakenDuringOrAfterMeal() {
        val s = state().copy(mealAt = 2_000_000, finishedAt = 3_000_000)
        val plan = BreakfastPlan(items, s)
        assertFalse(plan.canTake(wake, Long.MAX_VALUE))
        assertFalse(plan.canTake(before, Long.MAX_VALUE))
        assertFalse(plan.canTake(during, Long.MAX_VALUE))
    }
    @Test fun emptyWakeStageAllowsBeforeFoodDoseImmediately() {
        val plan = BreakfastPlan(listOf(before), state())
        assertEquals(state().wokeAt, plan.beforeDue(before))
        assertTrue(plan.canTake(before, state().wokeAt))
    }
    @Test fun editsDoNotChangeStartedMorningOrConfirmAnotherDose() {
        val s = state().copy(snapshot = items, taken = mapOf("wake" to state().wokeAt))
        val changed = wake.copy(breakfastStage = "after", breakfastMinutes = 0, dose = "Другая доза")
        val plan = BreakfastPlan(listOf(changed, before, during, after), s)
        assertEquals(wake, plan.group("wake").single())
        assertEquals(s.wokeAt + min(30), plan.wakeReady())
        assertFalse(plan.canTake(changed, Long.MAX_VALUE))
    }
    @Test fun deletingAndDisablingNotificationsApplyDuringStartedMorning() {
        val s = state().copy(snapshot = items)
        val plan = BreakfastPlan(listOf(before.copy(remindersEnabled = true), during, after), s)
        assertTrue(plan.group("wake").isEmpty())
        assertTrue(plan.group("before").single().remindersEnabled)
    }
}
