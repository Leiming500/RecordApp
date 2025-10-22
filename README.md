
##  Continuous Audio Recorder App

**Author:** [Leiming500](https://github.com/Leiming500)
**Platform:** Android
**Language:** Kotlin
**Database:** Room
**Architecture:** MVVM + Flow
**Purpose:** Continuously record audio in 2-second segments, capture GPS location, and upload data automatically.

---

###  Overview

This Android application continuously records audio in **2-second files**, automatically capturing:

* The **file name**
* **Timestamp**
* **GPS coordinates** (latitude & longitude)

All these details are stored in a **Room database**, and each file can be automatically uploaded to a remote server for processing or storage.

---

###  Features

✅ Continuous background recording (split every 2 seconds)
✅ Automatic saving of location metadata
✅ Local persistence with Room database
✅ Reactive updates via Kotlin Flow
✅ Optional upload service for cloud synchronization
✅ Works with `.m4a` format (high compression & clarity)

---

###  Tech Stack

| Layer        | Technology                    |
| ------------ | ----------------------------- |
| Language     | Kotlin                        |
| Architecture | MVVM                          |
| Database     | Room (with DAO & Entity)      |
| Reactive     | Kotlin Flow                   |
| Audio API    | MediaRecorder                 |
| Location     | FusedLocationProviderClient   |
| Network      | Retrofit or OkHttp (optional) |

---

###  Project Structure

```
app/
 ├── data/
 │    ├── entity/AudioRecordEntity.kt
 │    ├── dao/AudioRecordDao.kt
 │    └── database/AppDatabase.kt
 ├── service/
 │    ├── AudioRecorderService.kt
 │    ├── LocationHelper.kt
 │    └── UploadWorker.kt
 ├── ui/
 │    └── MainActivity.kt
 └── utils/
      └── FileUtils.kt
```

---

###  How It Works

1. **MainActivity** starts the recording service.
2. **AudioRecorderService** continuously records in 2-second chunks.
3. Each audio segment is saved as an `.m4a` file.
4. The **GPS location** is fetched and stored in the Room database.
5. The **UploadWorker** (optional) uploads files asynchronously to the server.

---

###  Getting Started

**Step 1:** Clone the repository

```bash
git clone https://github.com/Leiming500/ContinuousAudioRecorder.git
```

**Step 2:** Open in Android Studio
Make sure Kotlin and Gradle are configured properly.

**Step 3:** Add permissions in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
```

**Step 4:** Run on a real device
(Emulators may not provide microphone or GPS data.)

---

###  Dependencies

```gradle
implementation("androidx.room:room-runtime:2.6.1")
kapt("androidx.room:room-compiler:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")

implementation("com.google.android.gms:play-services-location:21.3.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

---

###  Future Improvements

* Background service resilience under Doze Mode
* Remote upload progress tracking
* Add waveform visualization in UI
* Support for other audio formats (e.g., WAV, AAC)

---

###  License

This project is open-source under the [MIT License](LICENSE).
You are free to use, modify, and distribute it.


