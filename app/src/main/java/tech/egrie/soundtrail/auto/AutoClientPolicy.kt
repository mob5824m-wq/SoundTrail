package tech.egrie.soundtrail.auto

/**
 * Who may browse the library through Soundtrail's media browser service.
 *
 * A media browser service has to be exported for a car to reach it, so something has to
 * decide who is asking. What is behind it is the listener's own music — the pins, the
 * places they named, the playlists they built — so the answer is deliberately narrow:
 * the car hosts, the system's own media surfaces, and anything the platform has already
 * trusted with media control ([MEDIA_CONTENT_CONTROL], a privileged permission).
 *
 * A refusal is a plain "no root": the caller learns nothing about what is stored here.
 * Callers are identified by the uid the system reports, not by the name a client claims,
 * so a different app cannot borrow one of these package names.
 */
internal object AutoClientPolicy {

    /** The packages that drive media in a car, on a lockscreen, or by voice. */
    private val KNOWN_HOSTS = setOf(
        "com.google.android.projection.gearhead",    // Android Auto
        "com.google.android.googlequicksearchbox",   // Google app: Assistant media requests
        "com.android.systemui",                      // System media controls and lockscreen
        "com.android.car.media",                     // Android Automotive OS host
        "com.android.car"
    )

    /** Already granted to the platform's own media surfaces; reading any session comes with it. */
    const val MEDIA_CONTENT_CONTROL = "android.permission.MEDIA_CONTENT_CONTROL"

    /**
     * True when the caller is this app, a known media host, or privileged enough to read the
     * platform's media sessions anyway. [callers] is every package the caller's uid owns,
     * which is what makes the check impossible to spoof by name.
     */
    fun isTrusted(
        callers: List<String>,
        ownPackage: String,
        holdsMediaContentControl: (String) -> Boolean
    ): Boolean = callers.any { caller ->
        caller == ownPackage || caller in KNOWN_HOSTS || holdsMediaContentControl(caller)
    }
}
