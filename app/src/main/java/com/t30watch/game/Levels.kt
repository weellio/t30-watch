package com.t30watch.game

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Level curve: finishing level L costs 100 × L^1.5 XP (rounded to 10).
 * L1 100, L2 280, L3 520, L4 800, L5 1,120, L10 3,160, L20 8,940.
 * A normal day earns roughly 250–400 XP, so the first levels come in a day or two and later ones take weeks.
 */
object Levels {
    private val names = listOf(
        "Fresh Start",      // 1
        "First Steps",      // 2
        "Warming Up",       // 3
        "On the Move",      // 4
        "Step Scout",       // 5
        "Trail Rookie",     // 6
        "Pace Setter",      // 7
        "Rhythm Keeper",    // 8
        "Habit Builder",    // 9
        "Trailblazer",      // 10
        "Night Owl Tamer",  // 11
        "Momentum Maker",   // 12
        "Iron Ankles",      // 13
        "Pulse Pilot",      // 14
        "Streak Smith",     // 15
        "Summit Seeker",    // 16
        "Marathon Mind",    // 17
        "Steel Heart",      // 18
        "Peak Performer",   // 19
        "Legend",           // 20
    )

    fun name(level: Int): String = when {
        level < 1 -> names.first()
        level <= names.size -> names[level - 1]
        else -> "Legend ${level - names.size + 1}"
    }

    /** XP needed to go from [level] to level + 1. */
    fun cost(level: Int): Int {
        val raw = 100.0 * level.coerceAtLeast(1).toDouble().pow(1.5)
        return ((raw / 10.0).roundToInt() * 10).coerceAtLeast(10)
    }

    /** Total XP needed to reach [level] from zero. */
    fun totalFor(level: Int): Int = (1 until level.coerceAtLeast(1)).sumOf { cost(it) }

    fun forXp(totalXp: Int): LevelInfo {
        var level = 1
        var rest = totalXp.coerceAtLeast(0)
        while (rest >= cost(level)) {
            rest -= cost(level)
            level++
        }
        return LevelInfo(level, name(level), name(level + 1), rest, cost(level), totalXp.coerceAtLeast(0))
    }
}
