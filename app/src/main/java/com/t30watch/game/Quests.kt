package com.t30watch.game

import java.time.LocalDate

/** What a quest can look at: the day itself, the day before, and the day after (for "tonight" quests). */
class QuestContext(val day: DaySummary, val prev: DaySummary?, val next: DaySummary?, val stepGoal: Int)

class QuestDef(
    val id: String,
    val emoji: String,
    val title: String,
    val unit: String,
    /** At most one quest per group on a day, so the three stay varied. */
    val group: String,
    val checksTomorrow: Boolean = false,
    val target: (QuestContext) -> Int,
    val value: (QuestContext) -> Int,
)

/** Three daily quests, picked from the pool by a seed made from the date. Same date → same quests, always. */
object Quests {
    const val PER_DAY = 3
    const val XP_EACH = 25
    const val XP_ALL_BONUS = 25
    /** "Asleep by 11:30 PM" as bedtime minutes (see [DaySummary.bedtimeMin]). */
    const val BEDTIME_TARGET_MIN = 23 * 60 + 30

    val pool: List<QuestDef> = listOf(
        QuestDef("morning_steps", "🌅", "2,000 steps before noon", "steps", "steps",
            target = { 2_000 }, value = { it.day.stepsBeforeNoon }),
        QuestDef("step_goal", "🎯", "Hit your step goal", "steps", "steps",
            target = { it.stepGoal.coerceAtLeast(1) }, value = { it.day.steps }),
        QuestDef("beat_yesterday", "📈", "Beat yesterday's steps", "steps", "steps",
            target = { ((it.prev?.steps ?: 0) + 1).coerceIn(2_000, 15_000) }, value = { it.day.steps }),
        QuestDef("evening_steps", "🌆", "1,000 steps after 6 PM", "steps", "steps",
            target = { 1_000 }, value = { it.day.stepsAfter18 }),
        QuestDef("active_30", "⚡", "30 active minutes", "min", "active",
            target = { 30 }, value = { it.day.activeMinutes }),
        QuestDef("moved_hours", "🕐", "Move in 8 different hours", "h", "active",
            target = { 8 }, value = { it.day.movedHours }),
        QuestDef("bp_reading", "🩺", "Take a BP reading", "", "measure",
            target = { 1 }, value = { it.day.bpChecks }),
        QuestDef("hr_reading", "❤️", "Check your heart rate", "", "measure",
            target = { 1 }, value = { it.day.hrChecks }),
        QuestDef("workout", "🏃", "Do a workout", "", "workout",
            target = { 1 }, value = { it.day.workouts.size }),
        QuestDef("wear_10h", "⌚", "Wear it 10 hours", "h", "wear",
            target = { 10 }, value = { it.day.heartSamples / GameRules.SAMPLES_PER_HOUR }),
        QuestDef("bed_2330", "🌙", "Asleep by 11:30 PM", "", "bed", checksTomorrow = true,
            target = { 1 }, value = { c -> c.next?.bedtimeMin?.let { if (it <= BEDTIME_TARGET_MIN) 1 else 0 } ?: 0 }),
    )

    private val byId = pool.associateBy { it.id }

    fun def(id: String): QuestDef? = byId[id]

    /** The quest ids for [date]: a seeded shuffle of the pool, keeping the first one from each group. */
    fun idsFor(date: LocalDate): List<String> {
        val rnd = java.util.Random(date.toEpochDay() * 1_000_003L + 17L)
        val order = pool.indices.toMutableList()
        // Fisher–Yates with java.util.Random: its sequence is fixed by the Java spec, so picks never change.
        for (i in order.indices.reversed()) {
            val j = rnd.nextInt(i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        val groups = HashSet<String>()
        val out = ArrayList<String>(PER_DAY)
        for (i in order) {
            val q = pool[i]
            if (groups.add(q.group)) out += q.id
            if (out.size == PER_DAY) break
        }
        return out
    }

    fun progressFor(date: LocalDate, days: Map<LocalDate, DaySummary>, stepGoal: Int): List<QuestProgress> {
        val ctx = QuestContext(
            day = days[date] ?: DaySummary(date),
            prev = days[date.minusDays(1)],
            next = days[date.plusDays(1)],
            stepGoal = stepGoal,
        )
        return idsFor(date).mapNotNull { def(it) }.map { q ->
            QuestProgress(q.id, q.emoji, q.title, q.value(ctx).coerceAtLeast(0), q.target(ctx), q.unit, XP_EACH, q.checksTomorrow)
        }
    }

    fun xpFor(quests: List<QuestProgress>): Int {
        val done = quests.count { it.done }
        return done * XP_EACH + if (quests.isNotEmpty() && done == quests.size) XP_ALL_BONUS else 0
    }
}
