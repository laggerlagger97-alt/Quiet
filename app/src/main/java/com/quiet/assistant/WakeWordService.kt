package com.quiet.assistant

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class WakeWordService : Service() {
    @Volatile private var running = false
    @Volatile private var triggered = false
    private val channelId = "quiet_wake"
    private val worker = Executors.newSingleThreadExecutor()
    private var audioRecord: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "com.quiet.assistant.WAKE"
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Quiet — офлайн-пробуждение")
            .setContentText("Подготавливаю русский детектор…")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(41, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(41, notification)
        running = true
        worker.execute { prepareAndListen() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Офлайн-пробуждение Quiet", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(message: String) {
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Quiet — офлайн-пробуждение")
            .setContentText(message)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(41, notification)
    }

    private fun prepareAndListen() {
        try {
            val modelDir = File(filesDir, "vosk-model-small-ru-0.22")
            if (!File(modelDir, "am/final.mdl").exists()) {
                updateNotification("Загружаю русскую модель (около 45 МБ)…")
                val archive = File(cacheDir, "vosk-ru.zip")
                URL("https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip").openConnection().apply {
                    connectTimeout = 20000
                    readTimeout = 120000
                }.getInputStream().use { input ->
                    FileOutputStream(archive).use { output -> input.copyTo(output) }
                }
                updateNotification("Распаковываю офлайн-модель…")
                unzipSafely(archive, filesDir)
                archive.delete()
            }
            if (!running) return
            updateNotification("Модель готова. Скажи «Квайет»")
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, 16000.0f)
            listenLoop()
        } catch (e: Exception) {
            Log.e("QuietWake", "Offline detector failed", e)
            updateNotification("Ошибка офлайн-детектора. Открой Quiet и попробуй снова.")
            stopSelf()
        }
    }

    private fun unzipSafely(zipFile: File, destination: File) {
        val root = destination.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val output = File(destination, entry.name)
                if (!output.canonicalPath.startsWith(root)) throw SecurityException("Invalid model archive path")
                if (entry.isDirectory) output.mkdirs() else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun listenLoop() {
        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IllegalStateException("AudioRecord buffer unavailable")
        val buffer = ByteArray(maxOf(minBuffer, 4096))
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            buffer.size * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone could not be initialized")
        }
        audioRecord = record
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            audioRecord = null
            throw IllegalStateException("Microphone did not enter recording state")
        }
        updateNotification("Активен — слушаю слово «Квайт»")
        Log.i("QuietWake", "Microphone recording started; sampleRate=$sampleRate")
        var lastPartialLogAt = 0L
        while (running && !triggered) {
            val count = record.read(buffer, 0, buffer.size)
            if (count < 0) throw IllegalStateException("Microphone read failed: $count")
            if (count > 0) {
                val finalResult = recognizer?.acceptWaveForm(buffer, count) == true
                val json = JSONObject(if (finalResult) recognizer?.result ?: "{}" else recognizer?.partialResult ?: "{}")
                val text = if (finalResult) json.optString("text") else json.optString("partial")
                if (text.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    if (finalResult || now - lastPartialLogAt >= 1500L) {
                        Log.d("QuietWake", "${if (finalResult) "final" else "partial"}='$text'")
                        lastPartialLogAt = now
                    }
                }
                if (isWakePhrase(text)) {
                    Log.i("QuietWake", "Wake phrase matched: '$text'")
                    triggered = true
                    runOnUiThreadCompat()
                    break
                }
            }
        }
        try { record.stop() } catch (_: Exception) {}
        record.release()
        audioRecord = null
    }

    private fun isWakePhrase(value: String): Boolean {
        val normalized = value.lowercase(Locale("ru", "RU"))
            .replace("ё", "е")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        val words = normalized.split(Regex("\\s+"))
        val targets = listOf(
            "квайет", "квайэт", "квайт", "квейт", "кваит", "квеет",
            "квиет", "квият", "кьют", "куайт", "куаит", "квет", "quiet"
        )
        return words.any { word ->
            targets.any { target -> word == target || (target.length >= 5 && levenshtein(word, target) <= 1) }
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun runOnUiThreadCompat() {
        android.os.Handler(mainLooper).post {
            if (!running) return@post
            updateNotification("Ключевое слово найдено")
            val intent = Intent(this, MainActivity::class.java).apply {
                action = "com.quiet.assistant.WAKE"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        running = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        recognizer = null
        model = null
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
