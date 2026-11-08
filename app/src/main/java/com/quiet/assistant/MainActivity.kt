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

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "Quiet"
            textSize = 36f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Твой голосовой ИИ-помощник"
            textSize = 17f
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "Нажми «Поговорить» и задай вопрос"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 16)
        }

        conversationView = TextView(this).apply {
            text = "Здесь появятся наши разговоры."
            textSize = 16f
            setPadding(12, 12, 12, 12)
        }

        val scroll = ScrollView(this).apply {
            addView(
                conversationView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val settingsButton = Button(this).apply {
            text = "Настроить Gemini API"
            setOnClickListener { showApiKeyDialog() }
        }

        talkButton = Button(this).apply {
            text = "Поговорить"
            textSize = 18f
            setOnClickListener {
                if (isListening) stopListening() else startListening()
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(settingsButton)
        root.addView(talkButton)

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
                    talkButton.text = "Остановить"
                }

                override fun onBeginningOfSpeech() {
                    status.text = "Говори..."
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    talkButton.text = "Поговорить"
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

    private fun startListening() {
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
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
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
            val parts = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")

            val answer = StringBuilder()
            for (i in 0 until parts.length()) {
                answer.append(parts.getJSONObject(i).optString("text", ""))
            }

            return answer.toString().trim().ifBlank {
                "Не получилось сформировать ответ. Попробуй ещё раз."
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