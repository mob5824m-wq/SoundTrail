package tech.egrie.soundtrail.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks a line back to the listener — the AI's reading of a request, mostly — through the
 * phone's own text-to-speech engine.
 *
 * Building the engine is asynchronous and its language is only known once it settles, so
 * [onAvailabilityChanged] reports whether this phone can speak, and [say] before that is
 * quietly dropped rather than queued onto an engine that may never arrive. Construct this on
 * the main thread and call [shutdown] when the surface that owns it goes away.
 *
 * The words are spoken by the phone's own engine; nothing leaves the device to say them.
 */
internal class VoiceSpeaker(context: Context) {

    private val main = Handler(Looper.getMainLooper())

    private var engine: TextToSpeech? = null
    private var settled = false
    private var usable = false
    private var closed = false
    private val watchers = mutableListOf<(Boolean) -> Unit>()

    init {
        val created = runCatching {
            TextToSpeech(context.applicationContext) { status ->
                // The engine calls back on an arbitrary thread; settle it on the main one, and
                // late enough that the field below has been assigned.
                main.post { settle(status == TextToSpeech.SUCCESS) }
            }
        }.getOrNull()
        engine = created
        if (created == null) main.post { settle(false) }
    }

    /** True once the engine speaks this phone's language — false hides the speaker button. */
    val available: Boolean
        get() = usable && !closed

    /**
     * Reports availability. Called as soon as the engine settles, or immediately when it
     * already has; a surface that builds after the engine is ready still finds out.
     */
    fun onAvailabilityChanged(listener: (Boolean) -> Unit) {
        main.post {
            when {
                closed -> listener(false)
                settled -> listener(usable)
                else -> watchers += listener
            }
        }
    }

    /**
     * Says [text] out loud. [interrupt] flushes whatever is being said; otherwise the line
     * queues behind it. Blank text, and a line asked for before the engine is ready, do nothing.
     */
    fun say(text: String, interrupt: Boolean = true) {
        main.post {
            val engine = engine ?: return@post
            if (!usable || closed || text.isBlank()) return@post
            val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            runCatching { engine.speak(text, mode, null, UTTERANCE_ID) }
        }
    }

    /** Stops mid-sentence; the engine stays ready for the next line. */
    fun stop() {
        main.post { runCatching { engine?.stop() } }
    }

    /** Stops and releases the engine. Every [say] after this does nothing. */
    fun shutdown() {
        main.post {
            closed = true
            usable = false
            watchers.clear()
            val engine = engine
            this.engine = null
            runCatching { engine?.stop() }
            runCatching { engine?.shutdown() }
        }
    }

    /**
     * Decides once whether this engine can speak. A phone can have an engine and still lack the
     * language, which is reported as missing data rather than an error — both mean "hide the
     * button" rather than "fail when pressed".
     */
    private fun settle(ok: Boolean) {
        if (settled) return
        settled = true
        val engine = engine
        usable = ok && engine != null && speaksThisPhone(engine)
        watchers.forEach { it(usable) }
        watchers.clear()
    }

    private fun speaksThisPhone(engine: TextToSpeech): Boolean {
        val language = runCatching { engine.setLanguage(Locale.getDefault()) }.getOrNull()
        return language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private companion object {
        const val UTTERANCE_ID = "soundtrail"
    }
}
