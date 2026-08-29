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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

enum class ModelDownloadState { NotStarted, Downloading, Ready, Failed }

data class OnboardingUiState(
    val step: Int = 1,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.NotStarted,
    val modelDownloadProgress: Int = 0,
    val modelDownloadError: String? = null,
)

// Every recording is transcribed entirely on-device (ADR-0011): no backend to connect to,
// so the wizard is privacy explainer -> on-device speech explainer -> model download, with
// progress shown explicitly (the old silent first-recording download left users confused —
// "nothing was happening").
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val modelDownloadManager = ModelDownloadManager(application)

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            modelDownloadState = if (modelDownloadManager.isModelPresent()) ModelDownloadState.Ready else ModelDownloadState.NotStarted,
        ),
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

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

    fun back() {
        _uiState.value = _uiState.value.copy(step = (_uiState.value.step - 1).coerceAtLeast(1))
    }

    fun next() {
        _uiState.value = _uiState.value.copy(step = (_uiState.value.step + 1).coerceAtMost(2))
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingComplete(true)
            onDone()
        }
    }
}

@Composable
fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingViewModel = viewModel()) {
    val c = LocalProtoColors.current
    val state by viewModel.uiState.collectAsState()

    Column(
        Modifier.fillMaxSize().background(c.screenBg).statusBarsPadding().navigationBarsPadding().padding(24.dp),
    ) {
        LinearProgressIndicator(
            progress = { state.step / 2f },
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
            // Top-anchored, not Center: step 3 is taller (adds the download card and Skip
            // link) than steps 1-2, so centering each step's own block made its
            // icon/title sit at a different height than the others — every step needs
            // the same fixed distance from the top so the headline doesn't jump around.
            Column(Modifier.fillMaxSize().padding(top = 64.dp)) {
                when (step) {
                    1 -> Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(88.dp).background(c.accent, androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(32.dp))
                        }
                        Text(
                            stringResource(R.string.onboarding2_step1_title),
                            color = c.text,
                            fontFamily = ProtoHeadingFont,
                            fontSize = 28.sp,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                        Text(
                            stringResource(R.string.onboarding2_step1_body),
                            color = c.textSecondary,
                            fontFamily = ProtoBodyFont,
                            fontSize = 14.5f.sp,
                            lineHeight = 22.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }

                    2 -> Column {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
                        Text(
                            stringResource(R.string.onboarding2_step4_title),
                            color = c.text,
                            fontFamily = ProtoHeadingFont,
                            fontSize = 28.sp,
                        )
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
                                        color = c.accent,
                                        trackColor = c.cardBorder,
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
                OutlinedButton(
                    onClick = viewModel::back,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = PillShape,
                ) { Text(stringResource(R.string.onboarding2_back)) }
            }
            Button(
                onClick = { if (state.step < 2) viewModel.next() else viewModel.finish(onFinished) },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = PillShape,
            ) { Text(stringResource(if (state.step < 2) R.string.onboarding2_continue else R.string.onboarding2_start_recording)) }
        }
    }
}
