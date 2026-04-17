package com.audiorecognize

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiorecognize.audio.MusicRecognizerApi
import com.audiorecognize.ui.theme.AudioRecognizeTheme
import java.io.File
import com.audiorecognize.RecognitionStep

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioRecognizeTheme {
                AudioRecognizeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRecognizeScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val viewModel: AudioViewModel = viewModel(
        factory = AudioViewModelFactory(application)
    )

    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(context, "需要权限才能使用", Toast.LENGTH_SHORT).show()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedFileUri = it
            selectedFileName = it.lastPathSegment ?: "未知文件"
        }
    }

    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("歌曲识别") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            FileRecognitionContent(
                selectedFileName = selectedFileName,
                selectedFileUri = selectedFileUri,
                viewModel = viewModel,
                onSelectFile = { filePickerLauncher.launch("audio/*") },
                onRecognize = { filePath ->
                    viewModel.recognizeAudio(filePath)
                },
                getPathFromUri = ::getPathFromUri
            )
        }
    }
}

@Composable
fun FileRecognitionContent(
    selectedFileName: String,
    selectedFileUri: Uri?,
    viewModel: AudioViewModel,
    onSelectFile: () -> Unit,
    onRecognize: (String) -> Unit,
    getPathFromUri: (android.content.Context, Uri) -> String?
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "选择一首歌曲进行识别",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (selectedFileName.isNotEmpty()) {
                    Text(
                        text = "已选择: $selectedFileName",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                Button(
                    onClick = onSelectFile,
                    enabled = viewModel.uiState.value.status != RecognitionStatus.Loading
                ) {
                    Text("选择音频文件")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                selectedFileUri?.let { uri ->
                    val filePath = getPathFromUri(context, uri)
                    if (filePath != null) {
                        onRecognize(filePath)
                    } else {
                        Toast.makeText(context, "无法读取文件", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enabled = selectedFileUri != null && viewModel.uiState.value.status != RecognitionStatus.Loading
        ) {
            if (viewModel.uiState.value.status == RecognitionStatus.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("识别中...")
            } else {
                Text("开始识别")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val uiState by viewModel.uiState.collectAsState()

        val isLoading = uiState.status == RecognitionStatus.Loading

        if (isLoading || uiState.currentStep != RecognitionStep.Idle) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = uiState.stepProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.stepMessage,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = getStepDescription(uiState.currentStep),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        when (uiState.status) {
            RecognitionStatus.Idle -> {
                Text(
                    text = "请选择音频文件并点击识别",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RecognitionStatus.Loading -> {
            }
            RecognitionStatus.Success -> {
                if (uiState.results.isEmpty()) {
                    Text(
                        text = "未找到匹配结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = "找到 ${uiState.results.size} 个结果:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(uiState.results.size) { index ->
                            ResultCard(result = uiState.results[index], index = index)
                        }
                    }
                }
            }
            RecognitionStatus.Error -> {
                Text(
                    text = uiState.errorMessage ?: "识别失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun LiveRecordingContent(
    viewModel: LiveRecognizeViewModel,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (uiState.status) {
                    RecordingStatus.Idle -> {
                        Text(
                            text = "点击开始录音识别",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Button(
                            onClick = onStartRecording
                        ) {
                            Text("开始录音")
                        }
                    }
                    RecordingStatus.Recording, RecordingStatus.Recognizing -> {
                        Text(
                            text = uiState.currentStep,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        LinearProgressIndicator(
                            progress = uiState.stepProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )

                        Text(
                            text = "${uiState.elapsedTime}s / 10s",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Button(
                            onClick = onStopRecording,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("停止录音")
                        }
                    }
                    RecordingStatus.Success -> {
                        Text(
                            text = "识别成功!",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        uiState.results.forEachIndexed { index, result ->
                            ResultCard(result = result, index = index)
                        }
                    }
                    RecordingStatus.Error -> {
                        Text(
                            text = uiState.errorMessage ?: "识别失败",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Button(onClick = onStartRecording) {
                            Text("重新录音")
                        }
                    }
                    RecordingStatus.Stopped -> {
                        Text(
                            text = "已停止",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Button(onClick = onStartRecording) {
                            Text("重新录音")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResultCard(result: MusicRecognizerApi.SongResult, index: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "${index + 1}. ${result.songName}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "歌手: ${result.artistName}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "专辑: ${result.albumName}",
                style = MaterialTheme.typography.bodySmall
            )
            if (result.score > 0) {
                Text(
                    text = "匹配度: ${result.score}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "时长: ${result.duration / 1000}秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (result.songUrl.isNotEmpty()) {
                Text(
                    text = "链接: ${result.songUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1
                )
            }
            Text(
                text = "歌曲ID: ${result.songId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getPathFromUri(context: android.content.Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val tempFile = File(context.cacheDir, "temp_audio")
        inputStream?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun getStepDescription(step: RecognitionStep): String {
    return when (step) {
        RecognitionStep.Idle -> ""
        RecognitionStep.LoadingFile -> "步骤 1/4: 读取音频文件"
        RecognitionStep.GeneratingFingerprint -> "步骤 2/4: 处理音频数据"
        RecognitionStep.Recognizing -> "步骤 3/4: 查询服务器"
        RecognitionStep.Completed -> "步骤 4/4: 返回结果"
        RecognitionStep.Failed -> "识别失败"
    }
}