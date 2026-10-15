package com.carenotes.app

import java.time.LocalDate

internal val breakfastStages = listOf("wake", "before", "with", "after")
internal val breakfastLabels = listOf("После пробуждения", "Перед завтраком", "Во время завтрака", "После завтрака")
internal fun Prescription.breakfastLabel(): String = breakfastLabels.getOrElse(breakfastStages.indexOf(breakfastStage)) { "" }

/** Actual confirmations, local to this device. Opening a reminder never confirms a dose. */
internal data class BreakfastState(
    val day: LocalDate,
    val wokeAt: Long,
    val ids: Set<String>,
    val taken: Map<String, Long> = emptyMap(),
    val mealAt: Long? = null,
    val finishedAt: Long? = null,
    val snapshot: List<Prescription>? = null
)

internal class BreakfastPlan(items: List<Prescription>, val state: BreakfastState) {
    // Keep this morning's doses/intervals stable. Deletion and notification switches apply immediately.
    private val current = items.associateBy { it.id }
    val items = (state.snapshot ?: items).filter { it.id in state.ids && it.id in current && it.breakfastStage in breakfastStages && it.isDoseDay(state.day) }
        .map { it.copy(remindersEnabled = current.getValue(it.id).remindersEnabled) }
    fun group(stage: String) = items.filter { it.breakfastStage == stage }
    private fun confirmed(stage: String) = group(stage).all { it.id in state.taken }
    fun wakeReady(): Long? = if (!confirmed("wake")) null else group("wake").fold(state.wokeAt) { result, p -> maxOf(result, state.taken.getValue(p.id) + p.breakfastMinutes * 60_000L) }
    fun beforeDue(p: Prescription): Long? = wakeReady()?.let { maxOf(state.wokeAt, it - p.breakfastMinutes * 60_000L) }
    fun mealReady(): Long? {
        val ready = wakeReady() ?: return null
        if (!confirmed("before")) return null
        return group("before").fold(ready) { result, p -> maxOf(result, state.taken.getValue(p.id) + p.breakfastMinutes * 60_000L) }
    }
    fun due(p: Prescription): Long? = when (p.breakfastStage) {
        "wake" -> state.wokeAt
        "before" -> beforeDue(p)
        "with" -> state.mealAt
        "after" -> state.finishedAt?.plus(p.breakfastMinutes * 60_000L)
        else -> null
    }
    fun canTake(p: Prescription, now: Long): Boolean = p in items && p.id !in state.taken && due(p)?.let { now >= it } == true && when (p.breakfastStage) {
        "wake", "before" -> state.mealAt == null
        "with" -> state.mealAt != null && state.finishedAt == null
        "after" -> state.finishedAt != null
        else -> false
    }
    fun canStartMeal(now: Long) = state.mealAt == null && mealReady()?.let { now >= it } == true
    fun canFinishMeal() = state.mealAt != null && state.finishedAt == null && confirmed("with")
    fun complete() = state.finishedAt != null && items.all { it.id in state.taken }
}
