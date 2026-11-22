package com.prism.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.dedup.DedupScope
import com.prism.music.data.dedup.DedupSettingsLogic
import com.prism.music.tr
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.PrismSheet
import com.prism.music.ui.theme.LocalContainer

/**
 * The detailed duplicate-song settings: one global default, and a per-page choice of On, Off or "Inherit global
 * setting". Changing the default leaves every stored override alone. In its own sheet so the Home & Library page keeps
 * its six groups and its row budget.
 */
@Composable
internal fun DedupSettingsSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val settings by c.settings.flow.collectAsState()
    PrismSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(tr(R.string.dedup_settings), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.dedup_global_default), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = settings.behavior.dedup.enabled, onCheckedChange = { c.settings.setDedupEnabled(it) })
            }
            DedupScope.entries.forEach { scope ->
                val choice = DedupSettingsLogic.override(scope, DedupSettingsLogic.parse(settings.behavior.dedup.overrides))
                Text(scopeLabel(scope), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                Row(Modifier.fillMaxWidth()) {
                    listOf(null, true, false).forEach { value ->
                        val label = when (value) { null -> tr(R.string.dedup_inherit); true -> tr(R.string.dedup_on); false -> tr(R.string.dedup_off) }
                        PrismTextButton(onClick = { c.settings.setDedupOverride(scope, value) }) { Text(label) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.dedup_scan_cellular), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = settings.behavior.dedup.cellular, onCheckedChange = { c.settings.setDedupScanCellular(it) })
            }
        }
    }
}

@Composable
private fun scopeLabel(scope: DedupScope): String = when (scope) {
    DedupScope.SONGS -> tr(R.string.dedup_scope_songs)
    DedupScope.ALBUMS -> tr(R.string.dedup_scope_albums)
    DedupScope.PLAYLISTS -> tr(R.string.dedup_scope_playlists)
    DedupScope.LOCAL_FILES -> tr(R.string.dedup_scope_local_files)
    DedupScope.DOWNLOADS -> tr(R.string.dedup_scope_downloads)
}
