package com.example.recorddemo

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.recorddemo.ui.theme.RecordDemoTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastKnownLocation: android.location.Location? = null

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private val recordedFiles = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestPermissions()

        setContent {
            RecordDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RecordScreen(
                        recordedFiles = recordedFiles,
                        onStartStopRecording = { startStopRecording() }
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { !it }) {
                Toast.makeText(this, "权限被拒绝", Toast.LENGTH_LONG).show()
            }
        }
        launcher.launch(permissions.toTypedArray())
    }

    private fun startStopRecording() {
        if (isRecording) {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Toast.makeText(this, "录音停止", Toast.LENGTH_SHORT).show()
        } else {
            isRecording = true
            startRecording()
            Toast.makeText(this, "录音开始", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()

        val dir = File(getExternalFilesDir(null), "RecordDemo")
        if (!dir.exists()) dir.mkdirs()

        thread {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val pcmData = buffer.copyOf(read)
                    updateLocation()
                    val file = savePcmAsWav(pcmData, dir)

                    // 打印文件信息到控制台
                    val fileSizeKb = file.length() / 1024
                    println("文件生成成功: ${file.absolutePath}, 大小: ${fileSizeKb} KB, 格式: ${file.extension}")

                    runOnUiThread {
                        recordedFiles.add(file.absolutePath)
                    }
                }
                Thread.sleep(2000) // 每2秒切分一次
            }
        }
    }

    private fun updateLocation() {
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    lastKnownLocation = location
                }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun savePcmAsWav(pcmData: ShortArray, dir: File): File {
        val timestamp = System.currentTimeMillis()
        val lat = lastKnownLocation?.latitude ?: 0.0
        val lon = lastKnownLocation?.longitude ?: 0.0
        val fileName = "record_${timestamp}_${lat}_${lon}.wav"
        val file = File(dir, fileName)

        val byteBuffer = ByteBuffer.allocate(pcmData.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        pcmData.forEach { byteBuffer.putShort(it) }

        FileOutputStream(file).use { fos ->
            val totalDataLen = byteBuffer.capacity() + 36
            val byteRate = 16 * sampleRate / 8
            val header = ByteArray(44)
            System.arraycopy("RIFF".toByteArray(), 0, header, 0, 4)
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            System.arraycopy("WAVE".toByteArray(), 0, header, 8, 4)
            System.arraycopy("fmt ".toByteArray(), 0, header, 12, 4)
            header[16] = 16
            header[20] = 1
            header[22] = 1
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (2).toByte()
            header[34] = 16
            System.arraycopy("data".toByteArray(), 0, header, 36, 4)
            val dataLen = byteBuffer.capacity()
            header[40] = (dataLen and 0xff).toByte()
            header[41] = ((dataLen shr 8) and 0xff).toByte()
            header[42] = ((dataLen shr 16) and 0xff).toByte()
            header[43] = ((dataLen shr 24) and 0xff).toByte()
            fos.write(header)
            fos.write(byteBuffer.array())
        }
        return file
    }
}

@Composable
fun RecordScreen(
    recordedFiles: List<String>,
    onStartStopRecording: () -> Unit
) {
    var isRecording by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {
                onStartStopRecording()
                isRecording = !isRecording
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(text = if (isRecording) "停止录音" else "开始录音")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            items(recordedFiles) { file ->
                Text(text = file, modifier = Modifier.padding(4.dp))
            }
        }
    }
}
