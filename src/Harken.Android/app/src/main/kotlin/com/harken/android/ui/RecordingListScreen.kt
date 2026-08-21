package com.harken.android.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harken.android.ui.theme.Organic
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.data.AppSettings
import com.harken.android.network.HarkenApi
import com.harken.android.network.NetworkModule
import com.harken.android.network.SessionListItem
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecordingListUiState(
    val sessions: List<SessionListItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val pendingPurge: SessionListItem? = null,
)

// New scope beyond MAUI parity (plan task 9): client-side refresh against GET /sessions,
// and both soft delete (default action, hides the row, keeps the file) and hard
// delete/purge (explicit, confirmed, irreversible) against the endpoints added in
// tasks 1-4.
class RecordingListViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private var cachedBaseUrl: String = AppSettings.DefaultBaseUrl
    private val api: HarkenApi = NetworkModule.create { cachedBaseUrl }

    private val _uiState = MutableStateFlow(RecordingListUiState())
    val uiState: StateFlow<RecordingListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { settings.baseUrl.collect { cachedBaseUrl = it } }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val response = api.listSessions()
                if (response.isSuccessful) {
                    _uiState.value = _uiState.value.copy(
                        sessions = response.body().orEmpty(),
                        isLoading = false,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun softDelete(id: UUID) {
        viewModelScope.launch {
            try {
                api.deleteSession(id)
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun requestPurge(session: SessionListItem) {
        _uiState.value = _uiState.value.copy(pendingPurge = session)
    }

    fun cancelPurge() {
        _uiState.value = _uiState.value.copy(pendingPurge = null)
    }

    fun confirmPurge() {
        val session = _uiState.value.pendingPurge ?: return
        _uiState.value = _uiState.value.copy(pendingPurge = null)
        viewModelScope.launch {
            try {
                api.purgeSession(session.id)
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}

@Composable
fun RecordingListScreen(
    onOpenSession: (UUID) -> Unit = {},
    viewModel: RecordingListViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp)) {
        // Centered title matching Capture's wordmark; refresh sits in its own 44dp
        // tap target on the right so it doesn't shift the title off-center.
        Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                "Recordings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center),
            )
            IconButton(onClick = viewModel::refresh, modifier = Modifier.align(Alignment.CenterEnd).size(44.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        state.error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (state.isLoading && state.sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else if (state.sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No recordings yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(top = 4.dp)) {
                items(state.sessions, key = { it.id }) { session ->
                    RecordingRow(
                        session = session,
                        onOpen = { onOpenSession(session.id) },
                        onDelete = { viewModel.softDelete(session.id) },
                        onPurge = { viewModel.requestPurge(session) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    state.pendingPurge?.let { session ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPurge,
            title = { Text("Delete permanently?") },
            text = { Text("This permanently deletes the audio file for the recording started at ${formatSessionTimestamp(session.startedAt)}. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPurge) { Text("Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelPurge) { Text("Cancel") }
            },
        )
    }
}

// Editorial index row, not a card: hairline divider, large mono timestamp as the
// anchor, a real full-width waveform as the row's own content, status carried by
// typography (dot + label) rather than a colored pill.
@Composable
private fun RecordingRow(
    session: SessionListItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onPurge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val (dateLabel, timeLabel) = remember(session.startedAt) { splitSessionTimestamp(session.startedAt) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column {
                Text(
                    timeLabel,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    dateLabel,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; onDelete() })
                    DropdownMenuItem(
                        text = { Text("Delete permanently", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onPurge() },
                    )
                }
            }
        }

        RecordingWaveform(seed = session.id.hashCode(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${session.segmentCount} segments",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (session.hasSummary) {
                Text(
                    "Summarized",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Organic.Accent600,
                )
            }
        }
    }
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

// Deterministic per-session bar pattern (from the session id, not audio amplitude —
// the API doesn't expose waveform data) so each row reads as a distinct fingerprint
// rather than identical decoration repeated down the list.
@Composable
private fun RecordingWaveform(seed: Int, modifier: Modifier = Modifier) {
    val bars = remember(seed) {
        val random = kotlin.random.Random(seed)
        List(28) { 0.2f + random.nextFloat() * 0.75f }
    }
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier = modifier.height(20.dp)) {
        val barWidth = size.width / (bars.size * 1.6f)
        val gap = barWidth * 0.6f
        bars.forEachIndexed { index, fraction ->
            val barHeight = size.height * fraction
            drawRect(
                color = color.copy(alpha = 0.42f),
                topLeft = androidx.compose.ui.geometry.Offset(index * (barWidth + gap), size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
        }
    }
}

// Splits an ISO timestamp into a large-mono time anchor and a small date caption,
// matching how the row reads it — time first, date secondary.
private fun splitSessionTimestamp(iso: String): Pair<String, String> {
    val formatted = formatSessionTimestamp(iso)
    val lastComma = formatted.lastIndexOf(',')
    return if (lastComma >= 0) {
        formatted.substring(lastComma + 1).trim() to formatted.substring(0, lastComma).trim()
    } else {
        formatted to ""
    }
}
