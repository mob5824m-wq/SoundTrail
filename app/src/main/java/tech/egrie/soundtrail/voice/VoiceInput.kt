package tech.egrie.soundtrail.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Speech-to-text for the places where the listener talks to Soundtrail instead of typing.
 *
 * The microphone belongs to the phone's speech recognizer, which the platform requires be
 * created and driven from the main thread — so every entry point here posts to the main thread
 * and every callback arrives there too. One listen is in flight at a time; a second [start]
 * while one is running is ignored rather than queued behind it. A listen that has been
 * superseded or cancelled can never deliver a result, which is what the generation counter is
 * for.
 *
 * The audio itself is handled by whichever speech service the phone has chosen (usually
 * Google's, sometimes an offline engine). Nothing is sent to Soundtrail or to any provider the
 * app talks to.
 *
 * The caller owns the lifetime: [release] when the surface that owns this goes away, or the
 * recognizer keeps its hold on the microphone.
 */
internal class VoiceInput(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** Bumped whenever a listen ends or is cancelled; callbacks carrying an old value are dropped. */
    private var generation = 0
    private var listening = false

    /** Whether this phone has a recognizer at all — false hides the microphone button. */
    val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** True while the microphone is open, so a button can show it. */
    val isListening: Boolean
        get() = listening

    /**
     * Opens the microphone for one phrase. [onPartial] fires with the words as they are
     * recognised, [onFinal] once with the whole phrase, [onFailure] with a sentence to show.
     * Exactly one of the last two arrives, and never both.
     */
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        main.post {
            if (listening) return@post
            if (!hasPermission()) {
                onFailure(VoiceErrors.sentence(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
                return@post
            }
            if (!available) {
                onFailure(VoiceErrors.unsupported())
                return@post
            }
            val engine = recognizer ?: createRecognizer()?.also { recognizer = it }
            if (engine == null) {
                onFailure(VoiceErrors.unsupported())
                return@post
            }
            val token = ++generation
            listening = true
            engine.setRecognitionListener(listener(token, onPartial, onFinal, onFailure))
            runCatching { engine.startListening(intent()) }.onFailure {
                listening = false
                onFailure(VoiceErrors.unsupported())
            }
        }
    }

    /** Asks for the phrase to end now. Whatever was heard is delivered like any other result. */
    fun stop() {
        main.post {
            if (listening) runCatching { recognizer?.stopListening() }
        }
    }

    /** Drops the phrase without delivering anything, and forgets it. */
    fun cancel() {
        main.post {
            generation++
            listening = false
            runCatching { recognizer?.cancel() }
        }
    }

    /** Frees the recognizer. Safe to call more than once, and after [release] nothing is heard. */
    fun release() {
        main.post {
            generation++
            listening = false
            val engine = recognizer ?: return@post
            recognizer = null
            runCatching { engine.destroy() }
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun createRecognizer(): SpeechRecognizer? =
        runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()

    /**
     * The listen request. Free-form, because a spoken request to a music app is a sentence and
     * not a search box; partial results are on so the words appear while they are still being
     * said; and the language is the phone's own rather than English.
     */
    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    private fun listener(
        token: Int,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onFailure: (String) -> Unit
    ): RecognitionListener = object : RecognitionListener {

        /** Only the listen this listener was made for may speak. */
        private fun live(): Boolean = token == generation

        private fun finish() {
            generation++
            listening = false
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live()) return
            val heard = first(partialResults) ?: return
            onPartial(heard)
        }

        override fun onResults(results: Bundle?) {
            if (!live()) return
            val heard = first(results)
            finish()
            if (heard == null) onFailure(VoiceErrors.sentence(SpeechRecognizer.ERROR_NO_MATCH))
            else onFinal(heard)
        }

        override fun onError(error: Int) {
            if (!live()) return
            finish()
            onFailure(VoiceErrors.sentence(error))
        }
    }

    /** The best phrase in a recognizer bundle, trimmed, or null when it holds nothing usable. */
    private fun first(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
}
