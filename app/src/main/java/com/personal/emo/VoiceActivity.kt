package com.personal.emo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

class VoiceActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "ایمو گوش می‌کنه…")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            startActivityForResult(i, 7)
        } catch (e: ActivityNotFoundException) {
            EmoService.instance?.command("")
            finish()
        }
    }

    override fun onActivityResult(rq: Int, rs: Int, d: Intent?) {
        super.onActivityResult(rq, rs, d)
        if (rs == RESULT_OK) {
            val list = d?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            // only the best recognition result, to avoid matching keywords from weaker alternatives
            EmoService.instance?.command(list?.firstOrNull() ?: "")
        }
        finish()
    }
}
