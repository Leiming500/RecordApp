package com.example.recorddemo

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.recorddemo.data.AppDatabase
import com.example.recorddemo.data.AudioFile
import com.example.recorddemo.network.ApiService
import com.example.recorddemo.repo.UploadRepository
import com.example.recorddemo.ui.theme.RecordDemoTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastKnownLocation: android.location.Location? = null

    private lateinit var db: AppDatabase
    private lateinit var uploadRepo: UploadRepository
    private val ioScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher() + SupervisorJob())


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getDatabase(this)

        // ✅ Base URL fixed — must end with slash
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder().addInterceptor(logging).build()
        val retrofit = Retrofit.Builder()
            .baseUrl("http://3.105.95.17:8000/") // <-- fixed
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(ApiService::class.java)
        uploadRepo = UploadRepository(api, db.audioFileDao())

        requestPermissions()
        observePendingUploads()

        setContent {
            RecordDemoTheme {
                val allFiles by db.audioFileDao().getAllFilesFlow().collectAsState(initial = emptyList())
                RecordScreen(files = allFiles, onStartStopRecording = { toggleRecording() })
            }
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { !it }) {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_LONG).show()
            }
        }
        launcher.launch(perms.toTypedArray())
    }

    private fun toggleRecording() {
        if (isRecording) {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
        } else {
            isRecording = true
            startRecording()
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        }
    }

    // 🎧 Recording logic kept 100% same
    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Toast.makeText(this, "AudioRecord error: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        audioRecord?.startRecording()
        val dir = File(getExternalFilesDir(null), "RecordDemo")
        if (!dir.exists()) dir.mkdirs()

        val twoSecSamples = sampleRate * 2
        val tempBuffer = ShortArray(twoSecSamples)
        var tempOffset = 0

        thread {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    var copied = 0
                    while (copied < read) {
                        val toCopy = min(read - copied, twoSecSamples - tempOffset)
                        System.arraycopy(buffer, copied, tempBuffer, tempOffset, toCopy)
                        tempOffset += toCopy
                        copied += toCopy

                        if (tempOffset >= twoSecSamples) {
                            updateLocation()
                            val lat = lastKnownLocation?.latitude ?: 0.0
                            val lon = lastKnownLocation?.longitude ?: 0.0
                            val file = savePcmAsWav(tempBuffer.copyOf(), dir, lat, lon)
                            ioScope.launch {
                                db.audioFileDao().insert(
                                    AudioFile(filePath = file.absolutePath, fileName = file.name, latitude = lat, longitude = lon)
                                )
                            }
                            tempOffset = 0
                        }
                    }
                }
            }
        }
    }

    private fun updateLocation() {
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc -> if (loc != null) lastKnownLocation = loc }
        } catch (se: SecurityException) {
            Log.w(TAG, "Location permission missing: ${se.message}")
        }
    }

    // 🎵 Full WAV save logic unchanged
    private fun savePcmAsWav(pcmData: ShortArray, dir: File, lat: Double, lon: Double): File {
        val timestamp = System.currentTimeMillis()
        val fileName = "rec_${timestamp}_${lat}_${lon}.wav"
        val file = File(dir, fileName)

        val byteBuffer = ByteBuffer.allocate(pcmData.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        pcmData.forEach { byteBuffer.putShort(it) }

        val pcmBytes = byteBuffer.array()
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataLen = pcmBytes.size
        val totalDataLen = dataLen + 36
        Log.d(TAG, "Writing WAV header, total bytes=${byteBuffer.capacity()}")

        FileOutputStream(file).use { fos ->
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[20] = 1
            header[22] = channels.toByte()
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * bitsPerSample / 8).toByte()
            header[34] = bitsPerSample.toByte()
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (dataLen and 0xff).toByte()
            header[41] = ((dataLen shr 8) and 0xff).toByte()
            header[42] = ((dataLen shr 16) and 0xff).toByte()
            header[43] = ((dataLen shr 24) and 0xff).toByte()
            fos.write(header)
            fos.write(pcmBytes)
            fos.flush()
        }
        return file
    }

    private fun observePendingUploads() {
        ioScope.launch {
            db.audioFileDao().getPendingUploadsFlow().collectLatest { list ->
                for (entity in list) {
                    val success = uploadRepo.uploadWithRetry(entity, maxRetries = 3)
                    Log.d(TAG, "Upload ${if (success) "succeeded" else "failed"} for ${entity.fileName}")
                    if (success) File(entity.filePath).delete()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.release()
        ioScope.cancel()
    }
}

// 🎨 Compose UI: three colored buttons for filtering
@Composable
fun RecordScreen(
    files: List<AudioFile>,
    onStartStopRecording: () -> Unit
) {
    // ✅ 这些都用显式 .value，避免 by 委托引发的报错
    val isRecordingState = remember { mutableStateOf(false) }
    val selectedFilterState = remember { mutableStateOf("Uploading") }

    val showErrorDialogState = remember { mutableStateOf(false) }
    val currentErrorTextState = remember { mutableStateOf("") }
    val currentFileState = remember { mutableStateOf<AudioFile?>(null) }
    val retryingInDialogState = remember { mutableStateOf(false) }  // ← 你报错的这个

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val filteredFiles = remember(files, selectedFilterState.value) {
        when (selectedFilterState.value) {
            "Uploaded" -> files.filter { it.uploaded }
            "Failed"   -> files.filter { !it.uploaded && it.lastError != null }
            else       -> files.filter { !it.uploaded && it.lastError == null }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {
                onStartStopRecording()
                isRecordingState.value = !isRecordingState.value
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(if (isRecordingState.value) "Stop Recording" else "Start Recording")
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FilterButton("Uploading", selectedFilterState.value, Color(0xFFFFC107)) { selectedFilterState.value = "Uploading" }
            FilterButton("Uploaded",  selectedFilterState.value, Color(0xFF4CAF50)) { selectedFilterState.value = "Uploaded" }
            FilterButton("Failed",    selectedFilterState.value, Color(0xFFF44336)) { selectedFilterState.value = "Failed" }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(filteredFiles) { file ->
                // ⬛ 1️⃣ 上传中 (Uploading)
                if (!file.uploaded && file.lastError == null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9C4)), // 淡黄色
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = file.fileName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Black
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Uploading...",
                                    color = Color(0xFFFFA000),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFFFFA000)
                                )
                            }
                        }
                    }
                }

                // 🟩 2️⃣ 已上传 (Uploaded)
                else if (file.uploaded) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)), // 淡绿色
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = file.fileName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Black
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Uploaded successfully ✅",
                                color = Color(0xFF388E3C),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // 🟥 3️⃣ 上传失败 (Failed)
                else if (!file.uploaded && file.lastError != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)), // 淡红色
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = file.fileName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Black
                            )

                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = file.lastError ?: "Unknown error",
                                color = Color.Red,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .clickable {
                                        currentErrorTextState.value = file.lastError ?: ""
                                        currentFileState.value = file
                                        showErrorDialogState.value = true
                                    }
                                    .padding(bottom = 6.dp)
                            )

                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        selectedFilterState.value = "Uploading"
                                        scope.launch(Dispatchers.IO) {
                                            val success = UploadRepository(
                                                RetrofitClient.instance,
                                                AppDatabase.getDatabase(context).audioFileDao()
                                            ).uploadWithRetry(file, maxRetries = 3)

                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    if (success) "Retry Success!" else "Retry Failed!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575))
                                ) {
                                    Text("Retry", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 🔔
        if (showErrorDialogState.value) {
            AlertDialog(
                onDismissRequest = { if (!retryingInDialogState.value) showErrorDialogState.value = false },
                title = { Text("Upload Error Detail") },
                text = {
                    Column {
                        Text(
                            currentErrorTextState.value,
                            color = Color.DarkGray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (retryingInDialogState.value) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Retrying…")
                            }
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val file = currentFileState.value ?: return@Button
                                showErrorDialogState.value = false
                                selectedFilterState.value = "Uploading"
                                retryingInDialogState.value = true
                                scope.launch(Dispatchers.IO) {
                                    val success = UploadRepository(
                                        RetrofitClient.instance,
                                        AppDatabase.getDatabase(context).audioFileDao()
                                    ).uploadWithRetry(file, maxRetries = 3)

                                    withContext(Dispatchers.Main) {
                                        retryingInDialogState.value = false
                                        Toast.makeText(
                                            context,
                                            if (success) "Retry Success!" else "Retry Failed!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            enabled = !retryingInDialogState.value,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) { Text("Retry") }

                        Spacer(Modifier.width(8.dp))

                        Button(
                            onClick = { showErrorDialogState.value = false },
                            enabled = !retryingInDialogState.value
                        ) { Text("OK") }
                    }
                },
                containerColor = Color(0xFFF5F5F5),
                tonalElevation = 8.dp
            )
        }
    }
}



@Composable
fun FilterButton(label: String, selected: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected == label) color else color.copy(alpha = 0.5f)
        )
    ) {
        Text(label)
    }
}
