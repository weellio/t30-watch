package com.t30watch.ui.rewards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t30watch.game.Badge
import com.t30watch.game.QuestProgress
import java.text.NumberFormat

/** Badge gold that reads on both light and dark surfaces of any theme. */
@Composable
internal fun badgeGold(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFFD54F) else Color(0xFF9A6B00)

internal fun fmt(n: Int): String = NumberFormat.getIntegerInstance().format(n)

@Composable
internal fun XpBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth().height(height),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeCap = StrokeCap.Round,
    )
}

/** A progress ring with the quest emoji inside, or a check mark once done. */
@Composable
internal fun QuestRing(q: QuestProgress, size: Dp = 44.dp) {
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { q.fraction },
            modifier = Modifier.size(size),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
        if (q.done) {
            Text("✓", fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        } else {
            Text(q.emoji, fontSize = (size.value * 0.4f).sp)
        }
    }
}

/** Progress line under a quest title. */
internal fun questStatus(q: QuestProgress): String = when {
    q.done -> "Done · +${q.xp} XP"
    q.checksTomorrow -> "Checked after tomorrow's sync · +${q.xp} XP"
    q.unit.isEmpty() -> "Not yet · +${q.xp} XP"
    else -> "${fmt(q.current)} / ${fmt(q.target)} ${q.unit} · +${q.xp} XP"
}

/** Shown once when badges unlock. Dismissing marks them seen. */
@Composable
internal fun CelebrationDialog(badges: List<Badge>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Nice!") } },
        title = { Text(if (badges.size == 1) "New badge!" else "${badges.size} new badges!") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                badges.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(b.emoji, fontSize = 40.sp)
                        Column(Modifier.fillMaxWidth()) {
                            Text(b.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = badgeGold())
                            Text(b.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
    )
}
