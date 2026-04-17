package com.audiorecognize

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiorecognize.audio.AudioFingerprintGenerator
import com.audiorecognize.audio.MusicRecognizerApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

enum class RecordingStatus {
    Idle,
    Recording,
    Recognizing,
    Success,
    Error,
    Stopped
}

data class RecordingUiState(
    val status: RecordingStatus = RecordingStatus.Idle,
    val currentStep: String = "",
    val stepProgress: Float = 0f,
    val elapsedTime: Int = 0,
    val results: List<MusicRecognizerApi.SongResult> = emptyList(),
    val errorMessage: String? = null,
    val isManualStopped: Boolean = false
)

class LiveRecognizeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState

    private val fingerprintGenerator = AudioFingerprintGenerator(application)
    private val musicApi = MusicRecognizerApi()

    private var recordingJob: Job? = null
    private var recognizeJob: Job? = null
    private var tempAudioFile: File? = null

    companion object {
        private const val TAG = "LiveRecognize"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val TIMEOUT_SECONDS = 10
        private const val RECOGNIZE_INTERVAL_MS = 3000L
    }

    fun startRecording() {
        if (_uiState.value.status == RecordingStatus.Recording) {
            return
        }

        tempAudioFile = File(getApplication<Application>().cacheDir, "live_recording_${System.currentTimeMillis()}.wav")

        _uiState.value = RecordingUiState(
            status = RecordingStatus.Recording,
            currentStep = "正在录音...",
            stepProgress = 0f,
            elapsedTime = 0,
            results = emptyList(),
            errorMessage = null,
            isManualStopped = false
        )

        recordingJob = viewModelScope.launch {
            startAudioRecordingAndRecognize()
        }
    }

    private suspend fun startAudioRecordingAndRecognize() = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size")
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    status = RecordingStatus.Error,
                    errorMessage = "录音初始化失败"
                )
            }
            return@withContext
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    status = RecordingStatus.Error,
                    errorMessage = "录音初始化失败"
                )
            }
            return@withContext
        }

        try {
            audioRecord.startRecording()
            val outputStream = FileOutputStream(tempAudioFile)
            writeWavHeader(outputStream, SAMPLE_RATE, 1, 16)

            val buffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()
            val timeoutMillis = TIMEOUT_SECONDS * 1000L

            while (coroutineContext.isActive) {
                val elapsed = (System.currentTimeMillis() - startTime).toInt()
                withContext(Dispatchers.Main) {
                    if (_uiState.value.status == RecordingStatus.Recording) {
                        _uiState.value = _uiState.value.copy(
                            elapsedTime = elapsed / 1000,
                            stepProgress = elapsed.toFloat() / timeoutMillis,
                            currentStep = "正在录音... ${elapsed / 1000}s / ${TIMEOUT_SECONDS}s"
                        )
                    }
                }

                if (elapsed >= timeoutMillis) {
                    Log.d(TAG, "Recording timeout")
                    break
                }

                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    outputStream.write(buffer, 0, read)
                }
            }

            outputStream.close()
            audioRecord.stop()
            audioRecord.release()

            Log.d(TAG, "Recording finished, file: ${tempAudioFile?.absolutePath}")

            withContext(Dispatchers.Main) {
                if (_uiState.value.status == RecordingStatus.Recording) {
                    _uiState.value = _uiState.value.copy(
                        status = RecordingStatus.Recognizing,
                        currentStep = "正在识别..."
                    )
                }
            }

            val tempFile = tempAudioFile
            if (tempFile != null && tempFile.exists() && tempFile.length() > 0 && _uiState.value.status != RecordingStatus.Stopped) {
                try {
                    Log.d(TAG, "Starting recognition, file size: ${tempFile.length()}")
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            currentStep = "正在生成指纹..."
                        )
                    }
                    
                    val fingerprint = fingerprintGenerator.generateFingerprint(tempFile.absolutePath)
                    Log.d(TAG, "Fingerprint generated, result: ${fingerprint.take(50)}...")

                    if (!fingerprint.startsWith("ERROR:") && _uiState.value.status != RecordingStatus.Stopped) {
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                currentStep = "正在查询..."
                            )
                        }
                        Log.d(TAG, "Calling API...")
                        val results = musicApi.recognize(fingerprint, 3)
                        Log.d(TAG, "API returned: ${results?.size ?: 0} results")

                        if (results != null && results.isNotEmpty()) {
                            Log.d(TAG, "Recognition success: ${results.size} results")
                            withContext(Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(
                                    status = RecordingStatus.Success,
                                    currentStep = "识别成功",
                                    results = results
                                )
                            }
                            return@withContext
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recognition error: ${e.message}", e)
                }
            }

            withContext(Dispatchers.Main) {
                if (_uiState.value.status != RecordingStatus.Stopped) {
                    _uiState.value = _uiState.value.copy(
                        status = RecordingStatus.Error,
                        currentStep = "未识别到歌曲",
                        errorMessage = "未能识别到歌曲"
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    status = RecordingStatus.Error,
                    errorMessage = e.message ?: "录音出错"
                )
            }
        }
    }

    fun stopRecording() {
        Log.d(TAG, "stopRecording called")
        _uiState.value = _uiState.value.copy(
            status = RecordingStatus.Stopped,
            currentStep = "已手动停止",
            isManualStopped = true
        )
        recordingJob?.cancel()
        recognizeJob?.cancel()
    }

    private suspend fun startAudioRecording() = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size")
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    status = RecordingStatus.Error,
                    errorMessage = "录音初始化失败"
                )
            }
            return@withContext
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    status = RecordingStatus.Error,
                    errorMessage = "录音初始化失败"
                )
            }
            return@withContext
        }

        try {
            audioRecord.startRecording()
            val outputStream = FileOutputStream(tempAudioFile)

            writeWavHeader(outputStream, SAMPLE_RATE, 1, 16)

            val buffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()
            val timeoutMillis = TIMEOUT_SECONDS * 1000L

            while (coroutineContext.isActive) {
                val elapsed = (System.currentTimeMillis() - startTime).toInt()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        elapsedTime = elapsed / 1000,
                        stepProgress = elapsed.toFloat() / timeoutMillis,
                        currentStep = "正在录音... ${elapsed / 1000}s / ${TIMEOUT_SECONDS}s"
                    )
                }

                if (elapsed >= timeoutMillis) {
                    Log.d(TAG, "Recording timeout")
                    break
                }

                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    outputStream.write(buffer, 0, read)
                }
            }

            outputStream.close()
            audioRecord.stop()
            audioRecord.release()

            Log.d(TAG, "Recording finished, file: ${tempAudioFile?.absolutePath}")

            withContext(Dispatchers.Main) {
                if (_uiState.value.status != RecordingStatus.Stopped &&
                    _uiState.value.status != RecordingStatus.Success) {
                    if (_uiState.value.results.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            status = RecordingStatus.Error,
                            currentStep = "未识别到歌曲",
                            errorMessage = "未能识别到歌曲"
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    status = RecordingStatus.Error,
                    errorMessage = e.message ?: "录音出错"
                )
            }
        }
    }

    private fun writeWavHeader(out: FileOutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(0)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * bitsPerSample / 8)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(0)
        out.write(header.array())
    }

    override fun onCleared() {
        super.onCleared()
        recordingJob?.cancel()
        tempAudioFile?.delete()
    }
}