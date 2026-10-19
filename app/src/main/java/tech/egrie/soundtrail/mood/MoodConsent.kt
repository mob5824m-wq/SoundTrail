package tech.egrie.soundtrail.mood

/**
 * Google AI Studio's API root, which is where a Gemini reading is sent.
 *
 * The consent record and the request itself must name the same place, so the constant lives here
 * and [MoodAiService] reads it rather than keeping a second copy that could drift.
 */
internal const val GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta"

/**
 * The exact external destination a mood reading would be sent to.
 *
 * A destination is more than a provider: pointing an OpenAI-compatible card at a different
 * gateway, or moving Ollama from a laptop to a server, sends the same words somewhere else. The
 * consent record is therefore keyed on both, and [fingerprint] changes whenever either does.
 */
internal data class MoodAiDestination(
    val provider: MoodAiProvider,
    val endpoint: String
) {
    /** The stable key stored after the listener approves this exact destination. */
    val fingerprint: String get() = "${provider.id}|$endpoint"
}

/**
 * The two reads and writes a consent record needs.
 *
 * Abstracted for exactly one reason: the rule that a changed provider or address invalidates an
 * earlier approval is the rule that makes the prompt honest, and it should be a unit test rather
 * than something only a person with a phone can check. [MoodStore] supplies the
 * SharedPreferences-backed implementation.
 */
internal interface MoodConsentStorage {
    fun read(key: String): String?

    /** Writes [value], or removes the key when [value] is null. */
    fun write(key: String, value: String?)
}

/**
 * The decision "this exact provider and endpoint may receive mood data".
 *
 * One record, not a set of approvals: moving from one endpoint to another has to ask again
 * rather than quietly reuse an answer given for somewhere else.
 */
internal class MoodConsentRecord(private val storage: MoodConsentStorage) {
    /** True only for the destination that was approved last. */
    fun isApproved(destination: MoodAiDestination): Boolean =
        storage.read(KEY_FINGERPRINT) == destination.fingerprint

    /**
     * Replaces whatever was approved before with [destination], and records when. The timestamp
     * is written as text so the storage contract stays two simple operations.
     */
    fun remember(destination: MoodAiDestination, atMs: Long) {
        storage.write(KEY_FINGERPRINT, destination.fingerprint)
        storage.write(KEY_APPROVED_AT, atMs.toString())
    }

    /**
     * When the approval currently in effect was given, or null when there is none. A timestamp
     * left behind without a matching fingerprint is treated as no approval at all, so the Mood
     * tab cannot show a withdrawal control for a decision that is not in force.
     */
    fun approvedAt(): Long? {
        if (storage.read(KEY_FINGERPRINT) == null) return null
        return storage.read(KEY_APPROVED_AT)?.toLongOrNull()?.takeIf { it >= 0 }
    }

    fun forget() {
        storage.write(KEY_FINGERPRINT, null)
        storage.write(KEY_APPROVED_AT, null)
    }

    companion object {
        const val KEY_FINGERPRINT = "ai_consent"
        const val KEY_APPROVED_AT = "ai_consent_at"
    }
}

/**
 * What the listener is asked to approve before a mood reading leaves the phone.
 *
 * Kept as plain data so the screen can render it and a unit test can pin down the exact endpoint
 * named for each provider.
 */
internal data class MoodAiConsentPrompt(
    val providerLabel: String,
    val endpoint: String
)

/**
 * Turns the selected provider and whatever address the listener typed into the destination that
 * will actually receive the request.
 *
 * The on-device engine has no destination and therefore never prompts. A provider whose address
 * is missing or unreadable has no destination either — nothing can be sent, and the app reports
 * what is missing instead of asking for consent to a place that does not exist.
 */
internal object MoodAiConsent {
    fun destination(
        provider: MoodAiProvider,
        ollamaUrl: String,
        openAiUrl: String
    ): MoodAiDestination? = when (provider) {
        MoodAiProvider.ON_DEVICE -> null
        MoodAiProvider.OLLAMA ->
            MoodAiUrls.normalize(ollamaUrl)?.let { MoodAiDestination(provider, it) }
        MoodAiProvider.OPENAI_COMPATIBLE ->
            MoodAiUrls.normalizeOpenAi(openAiUrl)?.let { MoodAiDestination(provider, it) }
        MoodAiProvider.GEMINI -> MoodAiDestination(provider, GEMINI_API_BASE)
    }
}
