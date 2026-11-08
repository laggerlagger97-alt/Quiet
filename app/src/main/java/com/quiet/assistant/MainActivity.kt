
package com.quiet.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private lateinit var talkButton: Button
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Quiet"
            textSize = 40f
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Твой голосовой помощник"
            textSize = 18f
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "Нажми кнопку и скажи что-нибудь"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 24)
        }

        talkButton = Button(this).apply {
            text = "Поговорить"
            setOnClickListener {
                if (isListening) stopListening() else startListening()
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(talkButton)
        setContentView(root)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Нет службы распознавания речи"
            talkButton.isEnabled = false
        } else {
            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this).apply {
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
                            status.text = "Обрабатываю речь..."
                        }

                        override fun onError(error: Int) {
                            isListening = false
                            talkButton.text = "Поговорить"
                            status.text = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                    "Не расслышал. Попробуй ещё раз."

                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                    "Нет доступа к микрофону"

                                else ->
                                    "Ошибка распознавания. Попробуй ещё раз."
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            isListening = false
                            talkButton.text = "Поговорить"

                            val matches = results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                            val phrase = matches?.firstOrNull().orEmpty()

                            if (phrase.isNotBlank()) {
                                val corrected = correctQuietName(phrase)
                                status.text = "Ты сказал: $corrected"

                                tts?.speak(
                                    "Я услышал: $corrected",
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "quiet_reply"
                                )
                            } else {
                                status.text = "Не расслышал. Попробуй ещё раз."
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
        }
    }

    private fun startListening() {
        val recognizer = speechRecognizer ?: return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            isListening = true
            status.text = "Запускаю микрофон..."
            talkButton.text = "Остановить"
            recognizer.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            talkButton.text = "Поговорить"
            status.text = "Не удалось включить микрофон"
        }
    }

    private fun stopListening() {
        speechRecognizer?.cancel()
        isListening = false
        talkButton.text = "Поговорить"
        status.text = "Остановлено"
    }

    private fun correctQuietName(text: String): String {
        return text.replace(
            Regex("(?iu)(?<![\\p{L}])(квайет|квайт|кваиет|кваит|quiet)(?![\\p{L}])")
        ) {
            "Quiet"
        }
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
