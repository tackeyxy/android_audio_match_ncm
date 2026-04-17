package com.audiorecognize.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AudioFingerprintGenerator(private val context: Context) {

    companion object {
        private const val TARGET_SAMPLE_RATE = 8000
        private const val DURATION_SECONDS = 3
        private const val TARGET_SAMPLES = DURATION_SECONDS * TARGET_SAMPLE_RATE
        private const val MAX_DECODE_SAMPLES = 3 * 44100 * 2
    }

    suspend fun generateFingerprint(audioFilePath: String): String = withContext(Dispatchers.IO) {
        val samples = runCatching {
            loadAndResampleAudio(audioFilePath)
        }.getOrElse {
            return@withContext "ERROR: ${it.message}"
        }

        suspendCancellableCoroutine { continuation ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var webView: WebView? = null

            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    continuation.resume("ERROR: Fingerprint generation timeout")
                    webView?.destroy()
                }
            }
            handler.postDelayed(timeoutRunnable, 60000)

            handler.post {
                try {
                    webView = WebView(context)
                    webView?.settings?.javaScriptEnabled = true
                    webView?.settings?.domStorageEnabled = true

                    val samplesJson = "[" + samples.joinToString(",") + "]"

                    webView?.addJavascriptInterface(FingerprintCallback(continuation), "Android")

                    val htmlWithSamples = createHtmlWithFingerprintLogic(samplesJson)
                    webView?.loadDataWithBaseURL("file:///android_asset/", htmlWithSamples, "text/html", "UTF-8", null)

                    continuation.invokeOnCancellation {
                        handler.removeCallbacks(timeoutRunnable)
                        webView?.destroy()
                    }
                } catch (e: Exception) {
                    handler.removeCallbacks(timeoutRunnable)
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun loadAndResampleAudio(filePath: String): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)

        var audioTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                audioTrackIndex = i
                break
            }
        }

        if (audioTrackIndex == -1) {
            throw IllegalArgumentException("No audio track found")
        }

        val format = extractor.getTrackFormat(audioTrackIndex)
        extractor.selectTrack(audioTrackIndex)
        val originalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val pcmData = extractPcmData(extractor, format)

        val resampledData = resampleAudio(pcmData, originalSampleRate, channelCount, TARGET_SAMPLE_RATE)

        val paddedData = if (resampledData.size < TARGET_SAMPLES) {
            resampledData + FloatArray(TARGET_SAMPLES - resampledData.size)
        } else {
            resampledData.copyOf(TARGET_SAMPLES)
        }

        return paddedData
    }

    private fun extractPcmData(extractor: MediaExtractor, format: MediaFormat): FloatArray {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
        val samples = mutableListOf<Float>()

        val mediaCodec = android.media.MediaCodec.createDecoderByType(mime)
        
        try {
            mediaCodec.configure(format, null, null, 0)
            mediaCodec.start()

            val inputBuffers = mediaCodec.inputBuffers
            val outputBuffers = mediaCodec.outputBuffers
            var inputDone = false
            var outputDone = false
            var samplesCollected = 0

            while (!outputDone && samplesCollected < MAX_DECODE_SAMPLES) {
                if (!inputDone) {
                    val inputBufferIndex = mediaCodec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = inputBuffers[inputBufferIndex]
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        
                        if (sampleSize < 0) {
                            mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            if (presentationTimeUs > DURATION_SECONDS * 1_000_000L) {
                                mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                mediaCodec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val bufferInfo = android.media.MediaCodec.BufferInfo()
                val outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000)

                when {
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = outputBuffers[outputBufferIndex]
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            val shortArray = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            while (shortArray.hasRemaining() && samplesCollected < MAX_DECODE_SAMPLES) {
                                samples.add(shortArray.get().toFloat() / 32768f)
                                samplesCollected++
                            }
                        }
                        
                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                        
                        if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            mediaCodec.stop()
            mediaCodec.release()
        }

        return samples.toFloatArray()
    }

    private fun resampleAudio(
        samples: FloatArray,
        originalSampleRate: Int,
        channels: Int,
        targetSampleRate: Int
    ): FloatArray {
        val monoSamples = if (channels > 1) {
            val leftChannel = FloatArray(samples.size / 2)
            val rightChannel = FloatArray(samples.size / 2)
            for (i in leftChannel.indices) {
                leftChannel[i] = samples[i * 2]
                rightChannel[i] = samples[i * 2 + 1]
            }
            FloatArray(leftChannel.size) { i -> (leftChannel[i] + rightChannel[i]) / 2f }
        } else {
            samples
        }

        if (originalSampleRate == targetSampleRate) {
            return monoSamples
        }

        val ratio = targetSampleRate.toFloat() / originalSampleRate
        val targetLength = (monoSamples.size * ratio).toInt()

        val result = FloatArray(targetLength)
        for (i in 0 until targetLength) {
            val srcIndex = i / ratio
            val srcIndexInt = srcIndex.toInt()
            val fraction = srcIndex - srcIndexInt

            if (srcIndexInt + 1 < monoSamples.size) {
                result[i] = monoSamples[srcIndexInt] * (1 - fraction) + monoSamples[srcIndexInt + 1] * fraction
            } else {
                result[i] = monoSamples.getOrElse(srcIndexInt) { 0f }
            }
        }

        return result
    }

    private fun createHtmlWithFingerprintLogic(samplesJson: String): String {
        val afpJs = context.assets.open("afp.js").bufferedReader().use { it.readText() }
        val afpWasmJs = context.assets.open("afp.wasm.js").bufferedReader().use { it.readText() }

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
</head>
<body>
<script>
$afpWasmJs
window.WASM_BINARY = WASM_BINARY;
$afpJs

function b64encode(data) {
    return btoa(String.fromCharCode(...data));
}

async function generateFingerprint(samples) {
    let fpRuntime = AudioFingerprintRuntime();
    
    await new Promise(resolve => {
        let check = setInterval(() => {
            if (typeof fpRuntime.ExtractQueryFP === 'function') {
                clearInterval(check);
                resolve();
            }
        }, 10);
        setTimeout(() => { clearInterval(check); resolve(); }, 5000);
    });

    if (typeof fpRuntime.ExtractQueryFP !== 'function') {
        return 'ERROR: ExtractQueryFP not found';
    }

    let PCMBuffer = new Float32Array(samples);
    let fp_vector = fpRuntime.ExtractQueryFP(PCMBuffer.buffer);

    let result_buf = new Uint8Array(fp_vector.size());
    for (let t = 0; t < fp_vector.size(); t++) {
        result_buf[t] = fp_vector.get(t);
    }

    return b64encode(result_buf);
}

window.onload = function() {
    let samples = JSON.parse('$samplesJson');
    generateFingerprint(samples).then(result => {
        window.Android.onFingerprintResult(result);
    }).catch(err => {
        window.Android.onFingerprintError("Error: " + err.toString());
    });
};
</script>
</body>
</html>
        """.trimIndent()
    }

    private inner class FingerprintCallback(
        private val continuation: kotlinx.coroutines.CancellableContinuation<String>
    ) {
        @JavascriptInterface
        fun onFingerprintResult(result: String) {
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }

        @JavascriptInterface
        fun onFingerprintError(error: String) {
            if (continuation.isActive) {
                continuation.resume("ERROR: $error")
            }
        }
    }
}