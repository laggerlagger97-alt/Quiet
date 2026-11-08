package com.quiet.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private lateinit var conversationView: TextView
    private lateinit var talkButton: Button
    private lateinit var wakeButton: Button

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var wakeMode = false
    private var wakeOnly = false
    private var apiKey = ""

    private val executor = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)
        apiKey = getSharedPreferences("quiet_settings", MODE_PRIVATE)
            .getString("gemini_key", "") ?: ""

        createInterface()
        createSpeechRecognizer()

        if (apiKey.isBlank()) {
            showApiKeyDialog()
        }
    }

    private fun createInterface() {
        val bg = 0xFF0B1020.toInt()
        val panel = 0xFF151D33.toInt()
        val accent = 0xFF8B7CFF.toInt()
        val muted = 0xFF9AA6C4.toInt()
        val white = 0xFFF5F7FF.toInt()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        fun rounded(color: Int, radius: Float) =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius.toInt()).toFloat()
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(4))
            setBackgroundColor(bg)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = "quiet."
            textSize = 26f
            setTextColor(white)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        val live = TextView(this).apply {
            text = "● AI ASSISTANT"
            textSize = 9f
            setTextColor(0xFF9D91FF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(0xFF252344.toInt(), 30f)
        }
        top.addView(brand, LinearLayout.LayoutParams(0, dp(38), 1f))
        top.addView(live)
        root.addView(top)

        val greeting = TextView(this).apply {
            text = "Твой голос. Твой помощник."
            textSize = 20f
            setTextColor(white)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(4), 0, dp(2))
            maxLines = 1
        }
        root.addView(greeting)

        val hint = TextView(this).apply {
            text = "Нажми «Говорить» и задай вопрос"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(7))
            maxLines = 1
        }
        root.addView(hint)

        status = TextView(this).apply {
            text = "Я рядом. Готов помочь."
            textSize = 12f
            setTextColor(0xFFD7D3FF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(panel, 16f)
            maxLines = 2
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(42)))

        val orb = TextView(this).apply {
            text = "✦"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(white)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF8D7CFF.toInt(), 0xFF5145B8.toInt())
            ).apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(dp(2), 0xFFB6ADFF.toInt())
            }
        }
        val orbWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(7))
            addView(orb, LinearLayout.LayoutParams(dp(64), dp(64)))
        }
        root.addView(orbWrap)

        conversationView = TextView(this).apply {
            text = "Здесь появится наша беседа.\nМожешь спросить меня о чём угодно."
            textSize = 13f
            setTextColor(white)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.TOP
        }
        root.addView(conversationView, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(2)
            bottomMargin = dp(6)
        })

        talkButton = Button(this).apply {
            text = "🎙  Говорить"
            textSize = 15f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(white)
            background = rounded(accent, 18f)
            setOnClickListener {
                if (isListening) stopListening() else startListening()
            }
        }
        root.addView(talkButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            bottomMargin = dp(5)
        })

        wakeButton = Button(this).apply {
            text = "◉  Фоновое пробуждение: выкл."
            textSize = 12f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(0xFFDCD8FF.toInt())
            background = rounded(0xFF252344.toInt(), 16f)
            setOnClickListener {
                if (wakeMode) {
                    stopService(Intent(this@MainActivity, WakeWordService::class.java))
                    wakeMode = false
                    wakeButton.text = "◉  Фоновое пробуждение: выкл."
                    status.text = "Фоновое пробуждение выключено"
                } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                } else {
                    wakeMode = true
                    startForegroundService(Intent(this@MainActivity, WakeWordService::class.java))
                    wakeButton.text = "⏹  Выключить фоновый режим"
                    status.text = "Quiet ожидает ключевое слово. Скажи «Quiet»."
                }
            }
        }
        root.addView(wakeButton, LinearLayout.LayoutParams(-1, dp(42)).apply {
            bottomMargin = dp(2)
        })

        val settingsButton = Button(this).apply {
            text = "Настройки Gemini"
            isAllCaps = false
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
            setTextColor(muted)
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            setOnClickListener { showApiKeyDialog() }
        }
        root.addView(settingsButton, LinearLayout.LayoutParams(-1, dp(30)))
        setContentView(root)
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Вставь свой Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(apiKey)
        }

        val container = LinearLayout(this).apply {
            setPadding(24, 8, 24, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Подключение Gemini")
            .setMessage("Введи API-ключ из Google AI Studio. Он сохранится на телефоне.")
            .setView(container)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text.toString().trim()

                if (key.isBlank()) {
                    input.error = "Введи API-ключ"
                    return@setOnClickListener
                }

                apiKey = key
                getSharedPreferences("quiet_settings", MODE_PRIVATE)
                    .edit()
                    .putString("gemini_key", key)
                    .apply()

                status.text = "Gemini подключён. Можешь говорить!"
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == "com.quiet.assistant.WAKE") {
            wakeMode = false
            tts?.speak("Слушаю", TextToSpeech.QUEUE_FLUSH, null, "quiet_wake")
            status.text = "Слушаю тебя. Задай вопрос."
            talkButton.text = "🎙  Включить Quiet"
            android.os.Handler(mainLooper).postDelayed({ startListening() }, 900)
        }
    }

    private fun createSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "На телефоне нет службы распознавания речи"
            talkButton.isEnabled = false
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    status.text = "Слушаю тебя..."
                    talkButton.text = "⏹  Остановить"
                }

                override fun onBeginningOfSpeech() {
                    status.text = "Говори..."
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    talkButton.text = "🎙  Говорить"
                    status.text = "Думаю над ответом..."
                }

                override fun onError(error: Int) {
                    isListening = false
                    talkButton.text = "Поговорить"
                    status.text = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "Не расслышал. Попробуй ещё раз."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Нет разрешения на микрофон."
                        else -> "Ошибка распознавания. Попробуй ещё раз."
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    talkButton.text = "Поговорить"

                    val phrase = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()

                    if (phrase.isNotBlank()) {
                        askGemini(phrase)
                    } else {
                        status.text = "Не расслышал. Попробуй ещё раз."
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startWakeListening() {
        if (!wakeMode || isFinishing) return
        wakeOnly = true
        status.text = "Quiet активен. Скажи «Quiet»"
        startListening()
    }

    private fun startListening() {
        if (isListening) return
        if (apiKey.isBlank()) {
            showApiKeyDialog()
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                101
            )
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            status.text = "Запускаю микрофон..."
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            status.text = "Не удалось включить микрофон."
        }
    }

    private fun stopListening() {
        speechRecognizer?.cancel()
        isListening = false
        talkButton.text = "Поговорить"
        status.text = "Остановлено"
    }

    private fun askGemini(question: String) {
        if (apiKey.isBlank()) {
            showApiKeyDialog()
            return
        }

        status.text = "Quiet думает..."
        conversationView.append("\n\nТы: $question\n")

        executor.execute {
            try {
                val answer = requestGemini(question)

                runOnUiThread {
                    history.add(question to answer)
                    while (history.size > 8) {
                        history.removeAt(0)
                    }

                    conversationView.append("\nQuiet: $answer\n")
                    status.text = "Готов слушать дальше."

                    tts?.speak(
                        answer,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "quiet_answer"
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Не удалось получить ответ: ${e.message ?: "ошибка сети"}"
                    conversationView.append("\nОшибка: ${e.message}\n")
                }
            }
        }
    }

    private fun speakNeural(text: String) {
        executor.execute {
            try {
                val audio = requestGeminiSpeech(text)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(audio.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(audio, 0, audio.size)
                track.play()
                while (track.playbackHeadPosition < audio.size / 2 && track.playState == AudioTrack.PLAYSTATE_PLAYING) Thread.sleep(40)
                track.release()
                runOnUiThread { if (wakeMode) startWakeListening() }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Нейроозвучка недоступна — включаю голос телефона"
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quiet_fallback")
                    if (wakeMode) startWakeListening()
                }
            }
        }
    }

    private fun requestGeminiSpeech(text: String): ByteArray {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Произнеси естественным, спокойным, дружелюбным женским голосом на русском языке. Текст: $text")))))
                .put("generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore")))))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw Exception("Gemini TTS HTTP $code")
            val parts = JSONObject(response).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                val data = inline.optString("data")
                if (data.isNotBlank()) return Base64.decode(data, Base64.DEFAULT)
            }
            throw Exception("Gemini не вернул аудио")
        } finally { connection.disconnect() }
    }

    private fun requestGemini(question: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/" +
                "models/gemini-3.8-flash:generateContent"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )
            connection.setRequestProperty("x-goog-api-key", apiKey)

            val contents = JSONArray()

            for ((oldQuestion, oldAnswer) in history.takeLast(8)) {
                contents.put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", oldQuestion)
                            )
                        )
                )

                contents.put(
                    JSONObject()
                        .put("role", "model")
                        .put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", oldAnswer)
                            )
                        )
                )
            }

            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", question)
                        )
                    )
            )

            val body = JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "Тебя зовут Quiet. Ты дружелюбный голосовой " +
                                    "помощник. Всегда отвечай на русском языке, " +
                                    "естественно, понятно и не слишком длинно. " +
                                    "Не повторяй вопрос вместо ответа."
                            )
                        )
                    )
                )
                .put("contents", contents)
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.7)
                        .put("maxOutputTokens", 500)
                )

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream.bufferedReader().use {
                it.readText()
            }

            if (code !in 200..299) {
                val message = try {
                    JSONObject(responseText)
                        .getJSONObject("error")
                        .optString("message", "HTTP $code")
                } catch (e: Exception) {
                    "HTTP $code"
                }
                throw Exception(message)
            }

            val json = JSONObject(responseText)
            val candidates = json.optJSONArray("candidates")
                ?: throw Exception(
                    json.optJSONObject("promptFeedback")
                        ?.optString("blockReason")
                        ?.takeIf { !it.isNullOrBlank() }
                        ?.let { "Запрос заблокирован: $it" }
                        ?: "Сервис не вернул ответ. Попробуй изменить вопрос."
                )
            if (candidates.length() == 0) {
                throw Exception("Gemini не вернул вариантов ответа. Попробуй ещё раз.")
            }

            val content = candidates.optJSONObject(0)?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
                ?: throw Exception("В ответе Gemini нет текста. Попробуй ещё раз.")

            val answer = StringBuilder()
            for (i in 0 until parts.length()) {
                answer.append(parts.optJSONObject(i)?.optString("text", "").orEmpty())
            }

            return answer.toString().trim().ifBlank {
                "Gemini вернул пустой ответ. Попробуй задать вопрос иначе."
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 101) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startListening()
            } else {
                status.text = "Разреши Quiet использовать микрофон."
            }
        }
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru", "RU"))
            tts?.setSpeechRate(0.94f)
            tts?.setPitch(1.0f)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                status.text = "Установи русский голос в настройках телефона."
            }
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }
}