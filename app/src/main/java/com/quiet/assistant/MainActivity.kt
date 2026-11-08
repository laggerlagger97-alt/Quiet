package com.quiet.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private val requestSpeech = 1001

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

        val button = Button(this).apply {
            text = "Поговорить"
            setOnClickListener { startListening() }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(button)

        setContentView(root)
    }

    private fun startListening() {
        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "ru-RU"
            )
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Скажите что-нибудь"
            )
        }

        try {
            startActivityForResult(intent, requestSpeech)
        } catch (e: Exception) {
            status.text = "Не удалось запустить распознавание речи"
        }
    }

    @Deprecated("Используется для совместимости")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == requestSpeech && resultCode == RESULT_OK) {
            val words = data?.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS
            )

            val phrase = words?.firstOrNull().orEmpty()

            if (phrase.isNotBlank()) {
                status.text = "Ты сказал: $phrase"

                tts?.speak(
                    "Я услышал: $phrase",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "quiet_reply"
                )
            }
        }
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}