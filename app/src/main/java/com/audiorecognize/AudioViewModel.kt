package com.audiorecognize

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiorecognize.audio.AudioFingerprintGenerator
import com.audiorecognize.audio.MusicRecognizerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecognitionStatus {
    Idle,
    Loading,
    Success,
    Error
}

enum class RecognitionStep {
    Idle,
    LoadingFile,
    GeneratingFingerprint,
    Recognizing,
    Completed,
    Failed
}

data class RecognitionUiState(
    val status: RecognitionStatus = RecognitionStatus.Idle,
    val currentStep: RecognitionStep = RecognitionStep.Idle,
    val stepProgress: Float = 0f,
    val stepMessage: String = "",
    val results: List<MusicRecognizerApi.SongResult> = emptyList(),
    val errorMessage: String? = null
)

class AudioViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RecognitionUiState())
    val uiState: StateFlow<RecognitionUiState> = _uiState

    private val fingerprintGenerator = AudioFingerprintGenerator(application)
    private val musicApi = MusicRecognizerApi()

    fun recognizeAudio(filePath: String) {
        viewModelScope.launch {
            _uiState.value = RecognitionUiState(
                status = RecognitionStatus.Loading,
                currentStep = RecognitionStep.LoadingFile,
                stepProgress = 0f,
                stepMessage = "正在加载音频文件..."
            )

            try {
                _uiState.value = _uiState.value.copy(
                    currentStep = RecognitionStep.GeneratingFingerprint,
                    stepProgress = 0.3f,
                    stepMessage = "正在生成音频指纹..."
                )

                val fingerprint = withContext(Dispatchers.IO) {
                    fingerprintGenerator.generateFingerprint(filePath)
                }

                if (fingerprint.startsWith("ERROR:")) {
                    _uiState.value = RecognitionUiState(
                        status = RecognitionStatus.Error,
                        currentStep = RecognitionStep.Failed,
                        stepProgress = 1f,
                        stepMessage = "指纹生成失败",
                        errorMessage = fingerprint
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    currentStep = RecognitionStep.Recognizing,
                    stepProgress = 0.6f,
                    stepMessage = "正在识别歌曲..."
                )

                val results = musicApi.recognize(fingerprint, 3)

                _uiState.value = _uiState.value.copy(
                    currentStep = RecognitionStep.Completed,
                    stepProgress = 1f,
                    stepMessage = "识别完成"
                )

                if (results != null && results.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        status = RecognitionStatus.Success,
                        results = results
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        status = RecognitionStatus.Success,
                        results = emptyList()
                    )
                }
            } catch (e: Exception) {
                _uiState.value = RecognitionUiState(
                    status = RecognitionStatus.Error,
                    currentStep = RecognitionStep.Failed,
                    stepProgress = 1f,
                    stepMessage = "识别失败",
                    errorMessage = e.message ?: "未知错误"
                )
            }
        }
    }
}