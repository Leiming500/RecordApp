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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder().addInterceptor(logging).build()
        val retrofit = Retrofit.Builder()
            .baseUrl("http://3.105.95.17:8000/api/v1/ingest/audio")
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

    /** ✅ Full WAV header version (restored) **/
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
            // RIFF header
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            // file size minus 8 bytes
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            // fmt chunk
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            // Subchunk1Size (16 for PCM)
            header[16] = 16
            // AudioFormat (1 = PCM)
            header[20] = 1
            // NumChannels
            header[22] = channels.toByte()
            // SampleRate
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            // ByteRate
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            // BlockAlign = NumChannels * BitsPerSample/8
            header[32] = (channels * bitsPerSample / 8).toByte()
            // BitsPerSample
            header[34] = bitsPerSample.toByte()
            // data subchunk
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            // Subchunk2Size (dataLen)
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

@Composable
fun RecordScreen(files: List<AudioFile>, onStartStopRecording: () -> Unit) {
    var isRecording by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("Uploading") }

    val filteredFiles = remember(files, selectedFilter) {
        when (selectedFilter) {
            "Uploaded" -> files.filter { it.uploaded }
            "Failed" -> files.filter { !it.uploaded && it.lastError != null }
            else -> files.filter { !it.uploaded && it.lastError == null }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {
                onStartStopRecording()
                isRecording = !isRecording
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(if (isRecording) "Stop Recording" else "Start Recording")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            FilterButton("Uploading", selectedFilter, Color(0xFFFFC107)) { selectedFilter = "Uploading" }
            FilterButton("Uploaded", selectedFilter, Color(0xFF4CAF50)) { selectedFilter = "Uploaded" }
            FilterButton("Failed", selectedFilter, Color(0xFFF44336)) { selectedFilter = "Failed" }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(filteredFiles) { file ->
                Text(file.fileName, modifier = Modifier.padding(8.dp))
            }
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
