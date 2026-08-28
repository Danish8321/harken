package com.harken.android.ui

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.harken.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.data.AppSettings
import com.harken.android.speech.ModelDownloadManager
import com.harken.android.ui.components.HarkenCard
import com.harken.android.ui.components.StatusChip
import com.harken.android.ui.theme.HarkenMotion
import com.harken.android.ui.theme.LocalProtoColors
import com.harken.android.ui.theme.PillShape
import com.harken.android.ui.theme.ProtoBodyFont
import com.harken.android.ui.theme.ProtoHeadingFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class ConnectionCheck { None, Checking, Connected, Failed }

enum class ModelDownloadState { NotStarted, Downloading, Ready, Failed }

data class OnboardingUiState(
    val step: Int = 1,
    val baseUrl: String = AppSettings.DefaultBaseUrl,
    val connectionCheck: ConnectionCheck = ConnectionCheck.None,
    val connectionMessage: String? = null,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.NotStarted,
    val modelDownloadProgress: Int = 0,
    val modelDownloadError: String? = null,
)

// Ports src/Harken.Mobile/Pages/OnboardingPage.xaml.cs — same wizard shape, same
// test-connection-before-save flow (a bad URL never gets persisted). Step 4 (model
// download) is new: on-device transcription (ADR-0011) needs the whisper model fetched
// once, and doing it here — explicit, with progress — beats the old silent
// first-recording download the user found confusing ("nothing was happening").
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val modelDownloadManager = ModelDownloadManager(application)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            modelDownloadState = if (modelDownloadManager.isModelPresent()) ModelDownloadState.Ready else ModelDownloadState.NotStarted,
        ),
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.baseUrl.collect { url ->
                _uiState.value = _uiState.value.copy(baseUrl = url)
            }
        }
    }

    fun downloadModel() {
        if (_uiState.value.modelDownloadState == ModelDownloadState.Downloading) return
        _uiState.value = _uiState.value.copy(modelDownloadState = ModelDownloadState.Downloading, modelDownloadError = null)
        viewModelScope.launch {
            modelDownloadManager.downloadProgress()
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        modelDownloadState = ModelDownloadState.Failed,
                        modelDownloadError = e.message ?: "Download failed",
                    )
                }
                .onCompletion { failure ->
                    if (failure == null && _uiState.value.modelDownloadState != ModelDownloadState.Failed) {
                        _uiState.value = _uiState.value.copy(modelDownloadState = ModelDownloadState.Ready, modelDownloadProgress = 100)
                    }
                }
                .collect { percent ->
                    _uiState.value = _uiState.value.copy(modelDownloadProgress = percent)
                }
        }
    }

    fun onBaseUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(baseUrl = value, connectionCheck = ConnectionCheck.None)
    }

    fun testConnection() {
        val url = _uiState.value.baseUrl
        val res = getApplication<Application>().resources
        if (!AppSettings.isValid(url)) {
            _uiState.value = _uiState.value.copy(
                connectionCheck = ConnectionCheck.Failed,
                connectionMessage = res.getString(com.harken.android.R.string.settings_invalid_url),
            )
            return
        }

        _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Checking, connectionMessage = res.getString(com.harken.android.R.string.settings_check_in_progress))
        viewModelScope.launch {
            try {
                val request = Request.Builder().url("${url.trimEnd('/')}/health").build()
                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }
                if (response.isSuccessful) {
                    settings.setBaseUrl(url)
                    _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Connected, connectionMessage = res.getString(com.harken.android.R.string.settings_connected))
                } else {
                    _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Failed, connectionMessage = res.getString(com.harken.android.R.string.settings_bad_status, response.code))
                }
                response.close()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(connectionCheck = ConnectionCheck.Failed, connectionMessage = res.getString(com.harken.android.R.string.settings_unreachable_reason, e.message ?: ""))
            }
        }
    }

    fun back() {
        _uiState.value = _uiState.value.copy(step = (_uiState.value.step - 1).coerceAtLeast(1))
    }

    fun next() {
        _uiState.value = _uiState.value.copy(step = (_uiState.value.step + 1).coerceAtMost(4))
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingComplete(true)
            onDone()
        }
    }
}

@Composable
private fun OnboardingExplainer(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    val c = LocalProtoColors.current
    Column {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
        Text(title, color = c.text, fontFamily = ProtoHeadingFont, fontSize = 28.sp)
        Text(
            body,
            color = c.textSecondary,
            fontFamily = ProtoBodyFont,
            fontSize = 14.5f.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingViewModel = viewModel()) {
    val c = LocalProtoColors.current
    val state by viewModel.uiState.collectAsState()

    Column(Modifier.fillMaxSize().background(c.screenBg).padding(24.dp)) {
        LinearProgressIndicator(
            progress = { state.step / 4f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(PillShape),
            color = c.accent,
            trackColor = c.cardBorder,
        )
        Text(
            stringResource(R.string.onboarding2_step_of, state.step),
            color = c.textSecondary,
            fontFamily = ProtoBodyFont,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 12.dp),
        )

        val spatial = HarkenMotion.spatialDefault<androidx.compose.ui.unit.IntOffset>()
        val effects = HarkenMotion.effectsDefault<Float>()
        AnimatedContent(
            targetState = state.step,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val forward = targetState >= initialState
                (
                    slideInHorizontally(spatial) { w -> if (forward) w / 3 else -w / 3 } +
                        fadeIn(effects)
                ).togetherWith(
                    slideOutHorizontally(spatial) { w -> if (forward) -w / 3 else w / 3 } +
                        fadeOut(effects),
                )
            },
            label = "onboardingStep",
        ) { step ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                when (step) {
                    1 -> Column {
                        Text(stringResource(R.string.onboarding2_step1_title), color = c.text, fontFamily = ProtoHeadingFont, fontSize = 27.sp)
                        Text(
                            stringResource(R.string.onboarding2_step1_body),
                            color = c.textSecondary,
                            fontFamily = ProtoBodyFont,
                            fontSize = 14.5f.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        HarkenCard(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                            OutlinedTextField(
                                value = state.baseUrl,
                                onValueChange = viewModel::onBaseUrlChanged,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = PillShape,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = c.pillTrack,
                                    unfocusedContainerColor = c.pillTrack,
                                ),
                                label = { Text(stringResource(R.string.onboarding2_url_hint)) },
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = viewModel::testConnection,
                                    enabled = state.connectionCheck != ConnectionCheck.Checking,
                                    shape = PillShape,
                                    modifier = Modifier.height(44.dp),
                                ) { Text(stringResource(if (state.connectionCheck == ConnectionCheck.Checking) R.string.onboarding2_testing else R.string.onboarding2_test_connection)) }
                                if (state.connectionCheck == ConnectionCheck.Connected) {
                                    StatusChip(
                                        label = stringResource(R.string.onboarding2_connected_chip),
                                        container = c.stateDone,
                                        content = c.stateDoneFg,
                                        leading = {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = null,
                                                tint = c.stateDoneFg,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                    )
                                }
                            }
                            state.connectionMessage?.takeIf { state.connectionCheck == ConnectionCheck.Failed }?.let {
                                Text(it, color = c.stateError, fontFamily = ProtoBodyFont, fontSize = 12.sp)
                            }
                        }
                        TextButton(
                            onClick = viewModel::next,
                            modifier = Modifier.padding(top = 4.dp),
                        ) { Text(stringResource(R.string.onboarding2_skip_for_now)) }
                    }

                    2 -> OnboardingExplainer(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.onboarding2_step2_title),
                        body = stringResource(R.string.onboarding2_step2_body),
                    )

                    3 -> OnboardingExplainer(
                        icon = Icons.Filled.GraphicEq,
                        title = stringResource(R.string.onboarding2_step3_title),
                        body = stringResource(R.string.onboarding2_step3_body),
                    )

                    4 -> Column {
                        Text(stringResource(R.string.onboarding2_step4_title), color = c.text, fontFamily = ProtoHeadingFont, fontSize = 27.sp)
                        Text(
                            stringResource(R.string.onboarding2_step4_body),
                            color = c.textSecondary,
                            fontFamily = ProtoBodyFont,
                            fontSize = 14.5f.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        HarkenCard(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                            when (state.modelDownloadState) {
                                ModelDownloadState.NotStarted -> Button(
                                    onClick = viewModel::downloadModel,
                                    shape = PillShape,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                ) { Text(stringResource(R.string.onboarding2_download_model)) }

                                ModelDownloadState.Downloading -> Column {
                                    LinearProgressIndicator(
                                        progress = { state.modelDownloadProgress / 100f },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(PillShape),
                                    )
                                    Text(
                                        stringResource(R.string.onboarding2_downloading, state.modelDownloadProgress),
                                        color = c.textSecondary,
                                        fontFamily = ProtoBodyFont,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }

                                ModelDownloadState.Ready -> StatusChip(
                                    label = stringResource(R.string.onboarding2_model_ready),
                                    container = c.stateDone,
                                    content = c.stateDoneFg,
                                    leading = {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = c.stateDoneFg,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                )

                                ModelDownloadState.Failed -> Column {
                                    Text(
                                        state.modelDownloadError ?: stringResource(R.string.settings_model_download_failed),
                                        color = c.stateError,
                                        fontFamily = ProtoBodyFont,
                                        fontSize = 12.sp,
                                    )
                                    OutlinedButton(
                                        onClick = viewModel::downloadModel,
                                        shape = PillShape,
                                        modifier = Modifier.padding(top = 8.dp),
                                    ) { Text(stringResource(R.string.onboarding2_retry)) }
                                }
                            }
                        }
                        TextButton(
                            onClick = { viewModel.finish(onFinished) },
                            modifier = Modifier.padding(top = 4.dp),
                        ) { Text(stringResource(R.string.onboarding2_skip_for_now)) }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.step > 1) {
                TextButton(
                    onClick = viewModel::back,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(stringResource(R.string.onboarding2_back)) }
            }
            Button(
                onClick = { if (state.step < 4) viewModel.next() else viewModel.finish(onFinished) },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = PillShape,
            ) { Text(stringResource(if (state.step < 4) R.string.onboarding2_continue else R.string.onboarding2_start_recording)) }
        }
    }
}
