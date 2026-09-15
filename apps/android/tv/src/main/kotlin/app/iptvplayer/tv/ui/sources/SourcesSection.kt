package app.iptvplayer.tv.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.TransportSecurity
import app.iptvplayer.ingestion.GuideSummary
import app.iptvplayer.storage.SourceRecord
import app.iptvplayer.storage.UnitStateRecord
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SourcesTags {
    fun refresh(id: PlaylistId) = "source-refresh-${id.value}"

    fun delete(id: PlaylistId) = "source-delete-${id.value}"

    fun watch(id: PlaylistId) = "source-watch-${id.value}"

    fun guideLink(id: PlaylistId) = "source-guide-link-${id.value}"

    fun guide(id: PlaylistId) = "source-guide-${id.value}"
}

private data class SourceRow(
    val record: SourceRecord,
    val channels: Long,
    val liveStatus: ImportStatus?,
    val guide: UnitStateRecord?,
    val customGuide: Boolean,
)

/** Configured sources with channel counts and import status; refresh and remove (DESIGN_SYSTEM.md §5 Playlists). */
@Composable
fun SourcesList(focus: FocusMemory, onEditGuideLink: (PlaylistId) -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val revision by graph.revision.collectAsState()
    val activity by graph.activity.collectAsState()
    var rows by remember { mutableStateOf<List<SourceRow>>(emptyList()) }
    var current by remember { mutableStateOf<PlaylistId?>(null) }
    var confirmDelete by remember { mutableStateOf<PlaylistId?>(null) }

    LaunchedEffect(revision) {
        current = graph.currentSource()?.playlistId
        rows = graph.sources().map {
            SourceRow(
                it,
                graph.channelCount(it.playlistId),
                graph.unitStatus(it.playlistId, ImportUnit.LIVE),
                graph.guideState(it.playlistId),
                graph.hasGuideLink(it.playlistId),
            )
        }
    }
    LaunchedEffect(confirmDelete) {
        if (confirmDelete != null) {
            delay(4_000)
            confirmDelete = null
        }
    }

    if (rows.isEmpty()) return
    Text(stringResource(R.string.sources_title), style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
    for (row in rows) {
        val id = row.record.playlistId
        val state = activity[id]
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.space2), modifier = Modifier.padding(vertical = Tokens.space2)) {
            Text(row.record.name, style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
            Text(
                listOfNotNull(
                    row.record.endpointDisplay,
                    pluralStringResource(R.plurals.sources_channels, row.channels.toInt(), row.channels.toInt()),
                    if (row.record.transportSecurity == TransportSecurity.CLEARTEXT) stringResource(R.string.sources_cleartext) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textSecondary,
            )
            val status = when {
                state?.liveRunning == true -> R.string.sources_status_importing
                state?.guideRunning == true -> R.string.sources_status_guide
                row.liveStatus == ImportStatus.FAILED -> R.string.sources_status_failed
                else -> null
            }
            if (rows.size > 1 && id == current) {
                Text(stringResource(R.string.sources_watching), style = MaterialTheme.typography.bodySmall, color = Tokens.stateLive)
            }
            guideText(row.guide)?.let {
                Text(
                    if (row.customGuide) stringResource(R.string.sources_guide_custom, it) else it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textSecondary,
                    modifier = Modifier.testTag(SourcesTags.guide(id)),
                )
            }
            status?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it ==
                        R.string.sources_status_failed
                    ) {
                        Tokens.stateWarning
                    } else {
                        Tokens.textTertiary
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3)) {
                if (rows.size > 1 && id != current) {
                    ActionButton(
                        stringResource(R.string.sources_watch),
                        { scope.launch { graph.selectSource(id) } },
                        Modifier.rememberedFocus(focus, SourcesTags.watch(id)),
                    )
                }
                ActionButton(
                    stringResource(R.string.sources_refresh),
                    { graph.refresh(id) },
                    Modifier.rememberedFocus(focus, SourcesTags.refresh(id)),
                )
                ActionButton(
                    stringResource(R.string.sources_guide_link),
                    { onEditGuideLink(id) },
                    Modifier.rememberedFocus(focus, SourcesTags.guideLink(id)),
                )
                ActionButton(
                    stringResource(if (confirmDelete == id) R.string.sources_confirm_delete else R.string.sources_delete),
                    {
                        if (confirmDelete == id) {
                            confirmDelete = null
                            scope.launch { graph.delete(id) }
                        } else {
                            confirmDelete = id
                        }
                    },
                    Modifier.rememberedFocus(focus, SourcesTags.delete(id)),
                )
            }
        }
    }
}

/** Guide result in plain language (EPG unit state): programmes kept, or why none were. */
@Composable
private fun guideText(guide: UnitStateRecord?): String? {
    if (guide == null || guide.status == ImportStatus.RUNNING) return null
    return when {
        guide.status == ImportStatus.FAILED -> stringResource(R.string.sources_guide_failed)
        guide.errorCode == GuideSummary.EMPTY -> stringResource(R.string.sources_guide_empty)
        guide.errorCode == GuideSummary.OUTSIDE_WINDOW -> stringResource(R.string.sources_guide_outside)
        else -> pluralStringResource(R.plurals.sources_guide_programmes, guide.itemCount.toInt(), guide.itemCount.toInt())
    }
}
