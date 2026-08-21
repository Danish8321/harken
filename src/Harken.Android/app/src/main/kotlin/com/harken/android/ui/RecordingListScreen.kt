package com.harken.android.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.harken.android.ui.theme.PillShape
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recordings", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = viewModel::refresh) {
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
                    CircularProgressIndicator()
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
                LazyColumn(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.sessions, key = { it.id }) { session ->
                        RecordingCard(
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

@Composable
private fun RecordingCard(
    session: SessionListItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onPurge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(formatSessionTimestamp(session.startedAt), style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        "${session.segmentCount} segments",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (session.hasSummary) {
                        Row(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(PillShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                "Summarized",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPurge) {
                Icon(Icons.Filled.DeleteForever, contentDescription = "Delete permanently", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
