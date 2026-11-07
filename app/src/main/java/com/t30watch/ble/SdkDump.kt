package com.t30watch.ble

import com.veepoo.protocol.model.enums.EFunctionStatus
import java.lang.reflect.Modifier

/**
 * Turns any SDK model into a list of fields by calling its public getters.
 * The SDK models have 20–130 getters each; listing them by hand would go stale with every SDK update.
 */
internal object SdkDump {
    fun section(title: String, obj: Any?): DumpSection {
        if (obj == null) return DumpSection(title, emptyList(), missing = true)
        val fields = obj.javaClass.methods
            .filter { m ->
                m.parameterCount == 0 &&
                    !Modifier.isStatic(m.modifiers) &&
                    m.returnType != Void.TYPE &&
                    m.name != "getClass" &&
                    (m.name.startsWith("get") || m.name.startsWith("is"))
            }
            .mapNotNull { m ->
                runCatching {
                    val value = m.invoke(obj)
                    DumpField(name = propertyName(m.name), value = value?.toString() ?: "null", supported = supported(value))
                }.getOrNull()
            }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
        return DumpSection(title, fields)
    }

    fun supported(value: Any?): Boolean? = when (value) {
        is EFunctionStatus -> when (value) {
            EFunctionStatus.SUPPORT, EFunctionStatus.SUPPORT_OPEN, EFunctionStatus.SUPPORT_CLOSE -> true
            EFunctionStatus.UNSUPPORT -> false
            else -> null
        }
        else -> null
    }

    private fun propertyName(getter: String): String {
        val raw = when {
            getter.startsWith("get") -> getter.removePrefix("get")
            else -> getter.removePrefix("is")
        }
        // SDK quirk: PwdData uses getmStatus()
        return raw.removePrefix("m").replaceFirstChar { it.lowercase() }.ifEmpty { getter }
    }

    fun toText(sections: List<DumpSection>): String = buildString {
        for (s in sections) {
            appendLine("== ${s.title}" + if (s.missing) " (not reported)" else "")
            for (f in s.fields) {
                val mark = when (f.supported) {
                    true -> "[yes]"
                    false -> "[no] "
                    null -> "     "
                }
                appendLine("  $mark ${f.name} = ${f.value}")
            }
        }
    }
}
