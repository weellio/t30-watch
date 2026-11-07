package com.t30watch.util

import com.t30watch.data.settings.Units
import kotlin.math.roundToInt

/**
 * Unit conversion + display. The database and the watch always use metric (cm, kg, metres);
 * only what the user sees or types is converted.
 */
object Fmt {
    private const val CM_PER_IN = 2.54
    private const val KG_PER_LB = 0.45359237
    private const val M_PER_MI = 1609.344

    fun distance(meters: Int?, units: Units): String? = meters?.let {
        if (units == Units.IMPERIAL) "%.2f mi".format(it / M_PER_MI) else "%.2f km".format(it / 1000.0)
    }

    fun weight(kg: Int, units: Units): String =
        if (units == Units.IMPERIAL) "${kgToLb(kg)} lb" else "$kg kg"

    fun height(cm: Int, units: Units): String =
        if (units == Units.IMPERIAL) cmToFtIn(cm).let { (ft, inch) -> "$ft′ $inch″" } else "$cm cm"

    fun kcal(k: Double?): String? = k?.let { "${it.roundToInt()} kcal" }

    fun kgToLb(kg: Int): Int = (kg / KG_PER_LB).roundToInt()
    fun lbToKg(lb: Int): Int = (lb * KG_PER_LB).roundToInt()

    /** cm → (feet, inches), inches 0..11. */
    fun cmToFtIn(cm: Int): Pair<Int, Int> {
        val totalIn = (cm / CM_PER_IN).roundToInt()
        return totalIn / 12 to totalIn % 12
    }

    fun ftInToCm(ft: Int, inch: Int): Int = ((ft * 12 + inch) * CM_PER_IN).roundToInt()
}
