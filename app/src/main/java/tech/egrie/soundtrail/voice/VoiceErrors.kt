package tech.egrie.soundtrail.voice

import android.speech.SpeechRecognizer

/**
 * What a listener reads when listening stops without words.
 *
 * The platform reports a number and nothing else, and some of those numbers are alarming for no
 * reason ("client" and "server" mean the recognizer gave up, not that the phone is broken), so
 * each one becomes a plain sentence that says what happened and whether trying again helps.
 */
internal object VoiceErrors {

    /** The sentence for a recognizer error code. Unknown codes get the neutral one. */
    fun sentence(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything. Try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Soundtrail needs microphone access to listen."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "The microphone is busy in another app. Close it and try again."
        SpeechRecognizer.ERROR_AUDIO -> "Another app is using the microphone."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs a network connection on this phone."
        SpeechRecognizer.ERROR_SERVER -> "The speech service couldn't answer. Try again."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            "Too many attempts at once. Wait a moment and try again."
        SpeechRecognizer.ERROR_CLIENT -> "Listening stopped early. Try again."
        else -> "Couldn't listen just now. Try again."
    }

    /** The sentence when the phone has no speech recognizer to offer at all. */
    fun unsupported(): String = "This phone has no speech recognition available."
}
