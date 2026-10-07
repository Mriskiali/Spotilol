package com.project.lol.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.project.lol.R
import com.project.lol.offline.SyncScheduler
import com.project.lol.stats.ScrobbleManager
import com.project.lol.util.DataSaverImageQuality
import com.project.lol.util.DataSaverManager
import com.project.lol.util.SleepTimerManager
import compose.icons.TablerIcons
import compose.icons.tablericons.Ad
import compose.icons.tablericons.Bolt
import compose.icons.tablericons.ChartBar
import compose.icons.tablericons.CloudDownload
import compose.icons.tablericons.Dice
import compose.icons.tablericons.Moon
import compose.icons.tablericons.Photo

/*
 * CREDIT: Spotilol - Enhanced features settings section.
 */

/* ── internal setting rows (keeps file self-contained) ── */

@Composable
private fun ExtSettingTile(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExtSwitchTile(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/* ── crossfade (gapless when 0, true overlap when >0) ── */

@Composable
fun rememberCrossfadeSetting(): Pair<Int, (Int) -> Unit> {
    val ctx = LocalContext.current
    var ms by remember {
        mutableIntStateOf(
            runCatching {
                ctx.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE)
                    .getInt("crossfade_ms", 0)
            }.getOrDefault(0)
        )
    }
    return ms to { v: Int ->
        ms = v
        ctx.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE)
            .edit().putInt("crossfade_ms", v).apply()
    }
}

@Composable
fun SettingCrossfadeTile(crossfadeMs: Int, onChange: (Int) -> Unit) {
    val options = listOf(0, 2_000, 4_000, 6_000, 8_000, 12_000)
    var showDialog by remember { mutableStateOf(false) }
    val label = if (crossfadeMs == 0) {
        stringResource(R.string.ext_crossfade_gapless)
    } else {
        stringResource(R.string.ext_crossfade_seconds, crossfadeMs / 1000)
    }
    ExtSettingTile(
        title = stringResource(R.string.ext_crossfade),
        subtitle = label,
        icon = TablerIcons.Bolt,
        onClick = { showDialog = true }
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.ext_crossfade)) },
            text = {
                Column {
                    options.forEach { ms ->
                        val text = if (ms == 0) stringResource(R.string.ext_crossfade_gapless)
                        else stringResource(R.string.ext_crossfade_seconds, ms / 1000)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onChange(ms); showDialog = false
                            }.padding(vertical = 10.dp)
                        ) {
                            Text(text,
                                color = if (ms == crossfadeMs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
fun SettingDataSaverTile(ctx: Context) {
    var enabled by remember { mutableStateOf(DataSaverManager.isEnabled(ctx)) }
    var quality by remember { mutableStateOf(DataSaverManager.imageQuality(ctx)) }
    ExtSwitchTile(
        title = stringResource(R.string.ext_data_saver),
        subtitle = stringResource(R.string.ext_data_saver_subtitle),
        icon = TablerIcons.Photo,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            DataSaverManager.setEnabled(ctx, it)
        }
    )
    if (enabled) {
        ExtSettingTile(
            title = stringResource(R.string.ext_data_saver_quality),
            subtitle = quality.name.lowercase().replaceFirstChar { it.uppercase() } +
                " (${quality.name})",
            icon = TablerIcons.Photo,
            onClick = {
                val next = when (quality) {
                    DataSaverImageQuality.HIGH -> DataSaverImageQuality.MEDIUM
                    DataSaverImageQuality.MEDIUM -> DataSaverImageQuality.LOW
                    DataSaverImageQuality.LOW -> DataSaverImageQuality.HIGH
                }
                quality = next
                DataSaverManager.setImageQuality(ctx, next)
            }
        )
    }
}

@Composable
fun SettingAutoSyncTile(ctx: Context) {
    val prefs = remember { ctx.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("AutoSyncOn", false)) }
    ExtSwitchTile(
        title = stringResource(R.string.ext_auto_sync),
        subtitle = stringResource(R.string.ext_auto_sync_subtitle),
        icon = TablerIcons.CloudDownload,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            prefs.edit().putBoolean("AutoSyncOn", it).apply()
            SyncScheduler.setEnabled(ctx, it)
        }
    )
}

@Composable
fun SettingScrobbleTile(ctx: Context) {
    var enabled by remember { mutableStateOf(ScrobbleManager.isEnabled(ctx)) }
    ExtSwitchTile(
        title = stringResource(R.string.ext_scrobble),
        subtitle = stringResource(R.string.ext_scrobble_subtitle),
        icon = TablerIcons.ChartBar,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            ScrobbleManager.setEnabled(ctx, it)
        }
    )
}

@Composable
fun SettingSmartShuffleTile(ctx: Context) {
    val prefs = remember { ctx.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("SmartShuffle", false)) }
    ExtSwitchTile(
        title = stringResource(R.string.ext_smart_shuffle),
        subtitle = stringResource(R.string.ext_smart_shuffle_subtitle),
        icon = TablerIcons.Dice,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            prefs.edit().putBoolean("SmartShuffle", it).apply()
        }
    )
}

@Composable
fun SettingSleepTimerTile(ctx: Context) {
    var active by remember { mutableStateOf(SleepTimerManager.isActive(ctx)) }
    val remaining = remember(active) { SleepTimerManager.remainingMs(ctx) }
    ExtSettingTile(
        title = stringResource(R.string.ext_sleep_timer),
        subtitle = if (active) {
            stringResource(R.string.ext_sleep_timer_active, remaining / 60000)
        } else {
            stringResource(R.string.ext_sleep_timer_subtitle)
        },
        icon = TablerIcons.Moon,
        onClick = {
            if (active) {
                SleepTimerManager.cancel(ctx)
            } else {
                SleepTimerManager.setTimer(ctx, 30)
            }
            active = SleepTimerManager.isActive(ctx)
        }
    )
}

@Composable
fun SettingSponsorBlockTile(ctx: Context) {
    val prefs = remember { ctx.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("SponsorBlock", false)) }
    ExtSwitchTile(
        title = stringResource(R.string.ext_sponsorblock),
        subtitle = stringResource(R.string.ext_sponsorblock_subtitle),
        icon = TablerIcons.Ad,
        checked = enabled,
        onCheckedChange = {
            enabled = it
            prefs.edit().putBoolean("SponsorBlock", it).apply()
        }
    )
}
