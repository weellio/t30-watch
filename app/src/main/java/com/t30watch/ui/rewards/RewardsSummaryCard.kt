package com.t30watch.ui.rewards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Compact Progress card for the Today dashboard: level + XP bar, the best live streak and today's
 * three quests as rings. Tapping opens the Progress screen. Also shows the new-badge celebration.
 */
@Composable
fun RewardsSummaryCard(onClick: () -> Unit, modifier: Modifier = Modifier, vm: RewardsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val celebrate by vm.toCelebrate.collectAsStateWithLifecycle()

    if (celebrate.isNotEmpty()) CelebrationDialog(celebrate) { vm.markSeen(celebrate) }

    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        val s = state
        if (s == null) {
            Text("Progress", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            return@Card
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Level ${s.level.level}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "  ${s.level.name}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
                val top = s.topStreak
                if (top != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${top.kind.emoji} ${top.count}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(top.kind.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            XpBar(s.level.fraction)
            Text(
                "${fmt(s.level.xpInLevel)} / ${fmt(s.level.xpForNext)} XP" + if (s.xpToday > 0) "  ·  +${fmt(s.xpToday)} today" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.quests.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    s.quests.forEach { q ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                            QuestRing(q, 48.dp)
                            Text(
                                q.title,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
