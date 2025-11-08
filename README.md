

---

# 🎙️ Continuous Audio Recorder & Uploader (RecordDemo)

> An Android app that continuously records 2-second WAV audio segments, captures GPS metadata, and uploads them to a backend API for AI-based fault detection.

---


**Name:** Leiming GUO  
**Role:** Android Developer / System Designer  
**Affiliation:** University of Newcastle – MIT (INFO6900 Project)  



---

## 📚 Table of Contents

<details>
<summary>Click to expand</summary>

1. [Overview](#-overview)
2. [Features](#-features)
3. [Tech Stack](#-tech-stack)
4. [Project Structure](#-project-structure)
5. [How It Works](#-how-it-works)
6. [API Endpoint](#-api-endpoint)
7. [HTTP Status Codes](#-http-status-codes--responses)
8. [Dependencies](#-dependencies)
9. [Future Improvements](#-future-improvements)
10. [Team Roles](#-team-roles--responsibilities)
11. [License](#-license)

</details>

---

## 🧭 Overview

This Android application continuously records **2-second WAV audio files**, each tagged with:

* 🎧 File name (timestamp + GPS)
* 📍 Latitude & longitude
* ⏱ Timestamp metadata

All segments are saved locally in a **Room database** and automatically uploaded to a backend API.
The UI (Jetpack Compose) lets users view **Uploading / Uploaded / Failed** items, view detailed error info, and **retry failed uploads**.

---

## ⚙️ Features

✅ Continuous 2-second audio capture
✅ Automatic GPS tagging (via Fused Location Provider)
✅ Local database (Room + Flow)
✅ Reactive UI (Jetpack Compose + Coroutines)
✅ Background auto-upload with retry
✅ Error dialog with retry option
✅ Filter tabs: Uploading / Uploaded / Failed
✅ WAV file creation with correct PCM header

---

## 🧩 Tech Stack

| Layer        | Technology                       |
| ------------ | -------------------------------- |
| Language     | **Kotlin**                       |
| UI Framework | **Jetpack Compose (Material 3)** |
| Database     | **Room + Kotlin Flow**           |
| Architecture | **MVVM**                         |
| Network      | **Retrofit + OkHttp**            |
| Location     | **FusedLocationProviderClient**  |
| Audio        | **AudioRecord API (PCM → WAV)**  |

---

## 🗂️ Project Structure

```
app/
 ├── data/
 │    ├── AudioFile.kt
 │    ├── AudioFileDao.kt
 │    └── AppDatabase.kt
 ├── network/
 │    └── ApiService.kt
 ├── repo/
 │    └── UploadRepository.kt
 ├── ui/
 │    └── MainActivity.kt
 └── utils/
      └── RetrofitClient.kt (optional)
```

---

## 🚀 How It Works

1. **MainActivity** starts continuous recording via `AudioRecord`.
2. Every **2 seconds**, PCM data is written into a `.wav` file.
3. The app fetches the **current GPS location**.
4. Metadata (path, name, location) is stored in **Room**.
5. A background coroutine uploads files automatically.
6. Upload statuses update dynamically in the UI.
7. Failed uploads show an **error dialog** with a **Retry** button.

---

## 🔌 API Endpoint

**URL:**

```
POST http://3.105.95.17:8000/api/v1/ingest/audio
```

**Headers:**

```
x-api-key: IT-PRO-UON-2025
Content-Type: multipart/form-data
```

### 📦 Body Type: `multipart/form-data`

Includes both:

* A **binary audio file**
* A **JSON metadata object**

| Field      | Type          | Example                                                                                                                                                                                          | Description                  |
| ---------- | ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------- |
| `file`     | File (binary) | `rec_1736159012345_-32.9154_151.7659.wav`                                                                                                                                                        | 2-second WAV audio segment   |
| `metadata` | JSON (text)   | `{ "id": 1736159012345, "lat": -32.9154, "lng": 151.7659, "description": "rec_1736159012345_-32.9154_151.7659.wav", "captured_at": "2025-11-07T14:07:12", "belt_id": null, "section_id": null }` | Metadata describing the file |

---

### 💻 Example (cURL)

```bash
curl -X POST http://3.105.95.17:8000/api/v1/ingest/audio \
  -H "x-api-key: IT-PRO-UON-2025" \
  -F "file=@rec_1736159012345_-32.9154_151.7659.wav;type=audio/wav" \
  -F 'metadata={
        "id": 1736159012345,
        "lat": -32.9154,
        "lng": 151.7659,
        "description": "rec_1736159012345_-32.9154_151.7659.wav",
        "captured_at": "2025-11-07T14:07:12",
        "belt_id": null,
        "section_id": null
      }'
```

---

## ⚙️ HTTP Status Codes & Responses

| Code                           | Meaning               | Typical Cause                                              | Recommended App Handling                            |
| ------------------------------ | --------------------- | ---------------------------------------------------------- | --------------------------------------------------- |
| **200 OK**                     | ✅ Success             | File uploaded and metadata stored successfully             | Mark record as `uploaded = true`; delete local file |
| **400 Bad Request**            | ❌ Invalid request     | Missing JSON fields, malformed request, or wrong data type | Log error and show user message                     |
| **401 Unauthorized**           | 🔒 Auth error         | Missing or invalid `x-api-key`                             | Stop retry, alert developer                         |
| **404 Not Found**              | 🚫 Invalid endpoint   | Wrong URL or inactive API                                  | Verify endpoint before retry                        |
| **413 Payload Too Large**      | 📦 File too big       | Audio exceeds backend size limit                           | Skip or compress file                               |
| **415 Unsupported Media Type** | 🎧 Wrong content type | File not `audio/wav` or metadata not JSON                  | Fix MIME type                                       |
| **500 Internal Server Error**  | 💥 Server issue       | AI model or DB processing failure                          | Retry with backoff (max 3)                          |
| **503 Service Unavailable**    | 💤 Temporary downtime | Server overload or maintenance                             | Queue for later retry                               |

---

### 🧠 Example Failure Response

```json
{
  "status": "error",
  "message": "Invalid metadata structure",
  "code": 400
}
```

---

### 🔁 Client Behavior Summary

1. **200 (OK)** → Mark as uploaded ✅
2. **4xx (Client Error)** → Stop retry; show “Upload Failed” ❌
3. **5xx (Server Error)** → Retry up to 3 times ⏳
4. **After 3 retries** → Move to Failed tab with full error reason 🧾

---

## 🧱 Dependencies

```gradle
implementation "androidx.room:room-runtime:2.6.1"
kapt "androidx.room:room-compiler:2.6.1"
implementation "androidx.room:room-ktx:2.6.1"

implementation "com.google.android.gms:play-services-location:21.1.0"
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1"
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1"
implementation "com.squareup.retrofit2:retrofit:2.9.0"
implementation "com.squareup.okhttp3:logging-interceptor:4.12.0"
```

---

## 💡 Future Improvements

* Background service resilience under Doze mode
* AI-based fault detection feedback integration
* Upload queue & progress tracking
* Power-efficient continuous recording
* In-app waveform visualization

---


## 📜 License

Open-source under the **MIT License**.
You may freely use, modify, and distribute this project.

---


