import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_files")
data class AudioFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    var uploadStatus: Int // 0=未上传, 1=已上传
)
