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
import java.net.SocketTimeoutException
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
    private var openRouterKey = ""

    private val executor = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)
        apiKey = getSharedPreferences("quiet_settings", MODE_PRIVATE)
            .getString("gemini_key", "") ?: ""
        openRouterKey = getSharedPreferences("quiet_settings", MODE_PRIVATE)
            .getString("openrouter_key", "") ?: ""

        createInterface()
        createSpeechRecognizer()

        if (apiKey.isBlank() && openRouterKey.isBlank()) {
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
            setPadding(dp(14), dp(3), dp(14), dp(2))
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
        root.addView(status, LinearLayout.LayoutParams(-1, dp(38)))

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
            setPadding(0, dp(3), 0, dp(3))
            addView(orb, LinearLayout.LayoutParams(dp(54), dp(54)))
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
        val chatScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(conversationView, ScrollView.LayoutParams(-1, -2))
        }
        root.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(1)
            bottomMargin = dp(3)
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
        root.addView(talkButton, LinearLayout.LayoutParams(-1, dp(44)).apply {
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
            text = "Настройки ИИ"
            isAllCaps = false
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
            setTextColor(muted)
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            setOnClickListener { showApiKeyDialog() }
        }
        root.addView(settingsButton, LinearLayout.LayoutParams(-1, dp(24)))
        setContentView(root)
    }

    private fun showApiKeyDialog() {
        val geminiInput = EditText(this).apply {
            hint = "Gemini API key (основной)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(apiKey)
        }
        val routerInput = EditText(this).apply {
            hint = "OpenRouter API key (резервный)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(openRouterKey)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(geminiInput)
            addView(routerInput)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Настройки ИИ")
            .setMessage("Gemini работает первым. Если он недоступен, Quiet попробует OpenRouter. Достаточно указать хотя бы один ключ.")
            .setView(container)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val gemini = geminiInput.text.toString().trim()
                val router = routerInput.text.toString().trim()
                if (gemini.isBlank() && router.isBlank()) {
                    geminiInput.error = "Укажи хотя бы один API-ключ"
                    return@setOnClickListener
                }
                apiKey = gemini
                openRouterKey = router
                getSharedPreferences("quiet_settings", MODE_PRIVATE).edit()
                    .putString("gemini_key", gemini)
                    .putString("openrouter_key", router)
                    .apply()
                status.text = "Настройки ИИ сохранены."
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == "com.quiet.assistant.WAKE") {
            // Keep background mode enabled; Vosk is restarted after the answer.
            wakeMode = true
            wakeOnly = false
            status.text = "Слушаю тебя. Задай вопрос."
            talkButton.text = "🎙  Включить Quiet"
            android.os.Handler(mainLooper).postDelayed({ startListening() }, 1200)
        }
    }

    private fun createSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "На телефоне нет службы распознавания речи"
            talkButton.isEnabled = false
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    status.text = "Слушаю тебя..."
                    talkButton.text = "⏹  Остановить"
                }
                override fun onBeginningOfSpeech() { status.text = "Говори..." }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    talkButton.text = "🎙  Говорить"
                    status.text = "Распознаю речь..."
                }
                override fun onError(error: Int) {
                    isListening = false
                    talkButton.text = "🎙  Говорить"
                    status.text = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал речь. Попробуй говорить чуть ближе к микрофону."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Проблема с интернетом или службой распознавания речи."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято. Нажми «Говорить» ещё раз."
                        else -> "Ошибка распознавания ($error). Попробуй ещё раз."
                    }
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    talkButton.text = "🎙  Говорить"
                    val choices = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val bestIndex = if (scores != null && scores.size == choices.size) {
                        scores.indices.maxByOrNull { scores[it] } ?: 0
                    } else 0
                    val phrase = choices.getOrNull(bestIndex)?.trim().orEmpty()
                    if (phrase.isNotBlank()) askGemini(phrase)
                    else status.text = "Не расслышал. Попробуй ещё раз."
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
        try {
            startForegroundService(Intent(this, WakeWordService::class.java))
        } catch (e: Exception) {
            status.text = "Не удалось запустить фоновое пробуждение: " + (e.message ?: "проверь разрешение микрофона")
        }
    }

    private fun startListening() {
        if (isListening) return
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    arrayListOf("Quiet", "Квайет", "Квайт", "арбузы", "сколько стоит")
                )
            }
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
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

    private fun appendChat(value: String) {
        conversationView.append(value)
        conversationView.post {
            val parent = conversationView.parent
            if (parent is ScrollView) parent.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun speakAnswer(text: String) {
        if (apiKey.isNotBlank()) speakNeural(text)
        else tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quiet_answer")
    }

    private fun askGemini(question: String) {
        val localAction = executeLocalCommand(question)
        if (localAction != null) {
            appendChat("\n\nТы: $question\nQuiet: $localAction\n")
            status.text = "Готов слушать дальше."
            speakAnswer(localAction)
            return
        }

        if (apiKey.isBlank() && openRouterKey.isBlank()) {
            showApiKeyDialog()
            return
        }

        status.text = "Quiet думает..."
        appendChat("\n\nТы: $question\n")

        executor.execute {
            try {
                val answer = requestAssistant(question)

                runOnUiThread {
                    history.add(question to answer)
                    while (history.size > 8) {
                        history.removeAt(0)
                    }

                    appendChat("\nQuiet: $answer\n")
                    status.text = "Готов слушать дальше."

                    speakAnswer(answer)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Не удалось получить ответ: ${e.message ?: "ошибка сети"}"
                    appendChat("\nОшибка: ${e.message}\n")
                }
            }
        }
    }

    // Explicit phone actions run locally and do not wait for Gemini.
    private fun executeLocalCommand(raw: String): String? {
        val q = raw.lowercase(Locale("ru", "RU")).replace("ё", "е").trim()
        val prefixes = listOf("открой ", "запусти ", "включи ", "перейди в ")
        val prefix = prefixes.firstOrNull { q.startsWith(it) }
        if (prefix != null) {
            val target = q.removePrefix(prefix).trim()
                .removePrefix("мне ").removePrefix("пожалуйста ").trim()
            val apps = listOf(
                Triple(listOf("youtube", "ютуб", "ю туб", "ютубчик"), "com.google.android.youtube", "YouTube"),
                Triple(listOf("telegram", "телеграм", "телеграмм", "телегу"), "org.telegram.messenger", "Telegram"),
                Triple(listOf("whatsapp business", "ватсап бизнес", "вотсап бизнес", "вацап бизнес"), "com.whatsapp.w4b", "WhatsApp Business"),
                Triple(listOf("whatsapp", "ватсап", "вотсап", "вацап"), "com.whatsapp", "WhatsApp"),
                Triple(listOf("instagram", "инстаграм", "инсту"), "com.instagram.android", "Instagram"),
                Triple(listOf("tiktok", "тикток"), "com.zhiliaoapp.musically", "TikTok"),
                Triple(listOf("chrome", "хром", "браузер", "интернет"), "com.android.chrome", "браузер"),
                Triple(listOf("карты", "google maps", "гугл карты"), "com.google.android.apps.maps", "Карты"),
                Triple(listOf("gmail", "почту", "почта"), "com.google.android.gm", "Gmail"),
                Triple(listOf("камеру", "камера"), "camera", "Камера"),
                Triple(listOf("настройки", "настройки телефона"), "settings", "Настройки"),
                Triple(listOf("калькулятор"), "calculator", "Калькулятор"),
                Triple(listOf("контакты", "телефонную книгу"), "contacts", "Контакты"),
                Triple(listOf("звонилку", "телефон", "звонки"), "dialer", "Телефон")
            )
            val app = apps.firstOrNull { a -> a.first.any { target == it || target.contains(it) } }
            if (app != null) {
                return try {
                    val intent = when (app.second) {
                        "com.whatsapp.w4b" -> packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
                            ?: return "WhatsApp Business не найден. Проверь, что он установлен."
                        "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
                        "camera" -> Intent("android.media.action.IMAGE_CAPTURE")
                        "contacts" -> Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.dir/contact")
                        "dialer" -> Intent(Intent.ACTION_DIAL)
                        "calculator" -> packageManager.getLaunchIntentForPackage("com.google.android.calculator")
                            ?: packageManager.getLaunchIntentForPackage("com.miui.calculator")
                        else -> packageManager.getLaunchIntentForPackage(app.second)
                    } ?: return "Приложение ${app.third} не найдено на телефоне."
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    "Открываю ${app.third}."
                } catch (_: Exception) { "Не удалось открыть ${app.third}." }
            }
        }

        if (q.startsWith("открой настройки wi-fi") || q.startsWith("открой wifi") ||
            q.startsWith("открой вайфай")) {
            return try {
                startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                "Открываю настройки Wi-Fi."
            } catch (_: Exception) { "Не удалось открыть настройки Wi-Fi." }
        }
        if (q.startsWith("открой bluetooth") || q.startsWith("открой блютуз")) {
            return try {
                startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                "Открываю настройки Bluetooth."
            } catch (_: Exception) { "Не удалось открыть настройки Bluetooth." }
        }
        val searchPrefix = listOf("найди в интернете ", "поищи в интернете ", "загугли ")
            .firstOrNull { q.startsWith(it) }
        if (searchPrefix != null) {
            val query = q.removePrefix(searchPrefix).trim()
            if (query.isNotBlank()) {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                )))
                return "Ищу в интернете: $query."
            }
        }
        return null
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
            connection.connectTimeout = 30000
            connection.readTimeout = 90000
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

    private fun requestAssistant(question: String): String {
        var geminiFailure: Exception? = null
        if (apiKey.isNotBlank()) {
            try {
                return requestGemini(question)
            } catch (e: Exception) {
                geminiFailure = e
            }
        }
        if (openRouterKey.isNotBlank()) {
            try {
                return requestOpenRouter(question)
            } catch (e: Exception) {
                throw Exception("Gemini: ${geminiFailure?.message ?: "ключ не задан"}. OpenRouter: ${e.message}")
            }
        }
        throw geminiFailure ?: Exception("Добавь ключ Gemini или OpenRouter в настройках.")
    }

    private fun requestOpenRouter(question: String): String {
        val connection = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12000
            connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Authorization", "Bearer $openRouterKey")
            connection.setRequestProperty("HTTP-Referer", "https://github.com/laggerlagger97-alt/Quiet")
            connection.setRequestProperty("X-Title", "Quiet Assistant")
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content",
                "Тебя зовут Quiet. Ты дружелюбный голосовой помощник. Отвечай на русском языке, ясно и естественно."))
            for ((oldQuestion, oldAnswer) in history.takeLast(8)) {
                messages.put(JSONObject().put("role", "user").put("content", oldQuestion))
                messages.put(JSONObject().put("role", "assistant").put("content", oldAnswer))
            }
            messages.put(JSONObject().put("role", "user").put("content", question))
            val body = JSONObject()
                .put("model", "openrouter/free")
                .put("messages", messages)
                .put("max_tokens", 500)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                throw Exception("HTTP $code: " + try {
                    JSONObject(raw).optJSONObject("error")?.optString("message", raw) ?: raw
                } catch (_: Exception) { raw.take(300) })
            }
            val choices = JSONObject(raw).optJSONArray("choices")
                ?: throw Exception("OpenRouter не вернул список ответов.")
            if (choices.length() == 0) throw Exception("OpenRouter не вернул вариантов ответа.")
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: throw Exception("В ответе OpenRouter нет сообщения.")
            val content = message.optString("content", "").trim()
            if (content.isBlank()) throw Exception("OpenRouter вернул пустой ответ.")
            return content
        } finally {
            connection.disconnect()
        }
    }

    private fun requestGemini(question: String): String {
        val models = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash")
        var lastError: Exception? = null

        // Bound network retries so a failed request does not keep the user waiting.
        for (model in models) {
            try {
                return requestGeminiWithModel(question, model)
            } catch (e: Exception) {
                lastError = e
                val message = e.message.orEmpty().lowercase(Locale.ROOT)
                val canTryFallback = message.contains("http 404") ||
                    message.contains("not found") ||
                    message.contains("high demand") ||
                    message.contains("overloaded") ||
                    message.contains("http 429") ||
                    message.contains("http 500") ||
                    message.contains("http 502") ||
                    message.contains("http 503") ||
                    message.contains("http 504")
                if (!canTryFallback) break
            }
        }

        val detail = lastError?.message.orEmpty()
        val lower = detail.lowercase(Locale.ROOT)
        if (lower.contains("unknownhost") || lower.contains("unable to resolve host") ||
            lower.contains("no address associated with hostname")) {
            throw Exception("Нет соединения с сервером Google. Проверь интернет, VPN и частный DNS.")
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            throw Exception("Сервер не ответил вовремя. Проверь соединение и повтори запрос.")
        }
        if (lower.contains("http 400") || lower.contains("invalid_argument")) {
            throw Exception("Google отклонил формат запроса (400). $detail")
        }
        if (lower.contains("http 401") || lower.contains("http 403") || lower.contains("api_key_invalid") || lower.contains("permission_denied")) {
            throw Exception("API-ключ недействителен или не имеет доступа. Проверь ключ Gemini в настройках. $detail")
        }
        if (lower.contains("http 404") || lower.contains("model_not_found")) {
            throw Exception("Google не нашёл доступную модель для этого API-ключа. $detail")
        }
        if (lower.contains("http 429") || lower.contains("quota") || lower.contains("rate limit")) {
            throw Exception("Превышен лимит запросов или квота Gemini. Подожди сброса лимита. $detail")
        }
        if (lower.contains("http 503") || lower.contains("http 502") || lower.contains("http 500")) {
            throw Exception("Серверы Google перегружены или временно недоступны. Попробуй позже. $detail")
        }
        throw Exception("Не удалось получить ответ от Gemini: $detail")
    }

    private fun requestGeminiWithModel(question: String, model: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        )
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 25000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)

            val contents = JSONArray()
            for ((oldQuestion, oldAnswer) in history.takeLast(8)) {
                contents.put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", oldQuestion))))
                contents.put(JSONObject().put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", oldAnswer))))
            }
            contents.put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", question))))

            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text",
                        "Тебя зовут Quiet. Ты дружелюбный голосовой помощник. " +
                            "Всегда отвечай на русском языке, естественно, понятно и не слишком длинно. " +
                            "Не повторяй вопрос вместо ответа."
                    )
                )))
                .put("contents", contents)
                // Gemini decides when live Google Search is useful, then grounds its answer.
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                .put("generationConfig", JSONObject()
                    .put("temperature", 0.7)
                    .put("maxOutputTokens", 500))

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }

            if (code !in 200..299) {
                val message = try {
                    JSONObject(responseText).getJSONObject("error")
                        .optString("message", "HTTP $code")
                } catch (e: Exception) {
                    "HTTP $code"
                }
                throw Exception("HTTP $code: $message")
            }

            val json = JSONObject(responseText)
            val candidates = json.optJSONArray("candidates")
                ?: throw Exception(
                    json.optJSONObject("promptFeedback")?.optString("blockReason")
                        ?.takeIf { !it.isNullOrBlank() }
                        ?.let { "Запрос заблокирован: $it" }
                        ?: "Сервис не вернул ответ. Попробуй изменить вопрос."
                )
            if (candidates.length() == 0) {
                throw Exception("Gemini не вернул вариантов ответа. Попробуй ещё раз.")
            }

            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
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