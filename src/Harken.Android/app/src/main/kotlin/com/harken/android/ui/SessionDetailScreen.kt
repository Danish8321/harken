package com.harken.android.ui

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.data.AppSettings
import com.harken.android.network.HarkenApi
import com.harken.android.network.NetworkModule
import com.harken.android.network.SessionDetail
import com.harken.android.network.TranscriptionStatus
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionDetailUiState(
    val detail: SessionDetail? = null,
    val isLoading: Boolean = true,
    val isSummarizing: Boolean = false,
    val error: String? = null,
)

class SessionDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private var cachedBaseUrl: String = AppSettings.DefaultBaseUrl
    private val api: HarkenApi = NetworkModule.create { cachedBaseUrl }

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { settings.baseUrl.collect { cachedBaseUrl = it } }
    }

    fun load(id: UUID) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            fetchOnce(id)

            // Transcription runs async on the backend (a POST /sessions upload returns before
            // Whisper finishes) — poll while still Pending/Running so the screen doesn't look
            // stuck; stop once it settles into Succeeded/Failed or the segments show up.
            while (_uiState.value.detail?.transcriptionStatus.let {
                    it == TranscriptionStatus.Pending || it == TranscriptionStatus.Running
                }
            ) {
                kotlinx.coroutines.delay(3000)
                fetchOnce(id)
            }
        }
    }

    private suspend fun fetchOnce(id: UUID) {
        try {
            val response = api.getSession(id)
            if (response.isSuccessful) {
                _uiState.value = _uiState.value.copy(detail = response.body(), isLoading = false, error = null)
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        }
    }

    fun summarize(id: UUID) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSummarizing = true, error = null)
            try {
                val response = api.summarize(id)
                if (response.isSuccessful) {
                    // Re-fetch rather than patching the cached detail locally: Gson populates
                    // this data class via reflection and can leave a Kotlin non-null field
                    // holding null, which only surfaces (as a crash) the moment something
                    // calls .copy() on it. A full re-fetch sidesteps that entirely.
                    fetchOnce(id)
                    _uiState.value = _uiState.value.copy(isSummarizing = false)
                } else {
                    _uiState.value = _uiState.value.copy(isSummarizing = false, error = "HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSummarizing = false, error = e.message)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: UUID,
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Transcript") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Crossfade(targetState = state.detail != null, label = "detail-crossfade") { hasDetail ->
                if (hasDetail) {
                    SessionDetailContent(
                        detail = state.detail!!,
                        isSummarizing = state.isSummarizing,
                        onSummarize = { viewModel.summarize(sessionId) },
                    )
                } else if (state.error != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            AnimatedVisibility(
                visible = state.error != null && state.detail != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionDetailContent(detail: SessionDetail, isSummarizing: Boolean, onSummarize: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(formatSessionTimestamp(detail.startedAt), style = MaterialTheme.typography.titleLarge)

        Row(
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Summary", style = MaterialTheme.typography.labelLarge)
            if (detail.summary == null) {
                OutlinedButton(onClick = onSummarize, enabled = !isSummarizing) {
                    if (isSummarizing) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(if (isSummarizing) "Summarizing…" else "Summarize")
                }
            }
        }

        detail.summary?.let { summary ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(summary.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
        } ?: run {
            if (detail.transcriptionStatus != TranscriptionStatus.Succeeded) {
                Text(
                    "Summary available once transcription finishes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Text("Transcript", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp))

        when (detail.transcriptionStatus) {
            TranscriptionStatus.Pending, TranscriptionStatus.Running -> Text(
                "Transcribing…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            TranscriptionStatus.Failed -> Text(
                "Transcription failed: ${detail.transcriptionFailureReason ?: "unknown error"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            else -> {}
        }

        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            items(detail.segments, key = { it.id }) { segment ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        segment.offset,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(segment.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
