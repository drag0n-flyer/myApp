package org.example.app.calls.policy

/** "Hide my IP address in calls". */
enum class IPHiding {
    Never, NonContacts, Always;

    /** Whether a call with this person must go through the relay server. */
    fun requiresRelay(isContact: Boolean): Boolean = when (this) {
        Never -> false
        NonContacts -> !isContact
        Always -> true
    }
}

enum class IceTransportPolicy { All, Relay }

enum class IceState { New, Checking, Connected, Completed, Disconnected, Failed, Closed }

/** The order is the Swift raw-value order (p180 lowest), so `<` and `coerceAtMost` work. */
enum class VideoTier { P180, P270, P360 }

enum class CallQuality { Good, Weak }

enum class CallBanner { VideoLowered, AudioOnly, Reconnecting, ConnectionLost }

data class MediaStats(
    val availableOutgoingBitrate: Double? = null, // bps, from the selected candidate pair
    val roundTripTime: Double? = null,            // seconds
    val inboundLossFraction: Double = 0.0,        // 0..1, audio inbound-rtp, since the last sample
    val jitter: Double? = null,                   // seconds
    val bytesSent: Long = 0, val bytesReceived: Long = 0,
    val packetsSent: Long = 0, val packetsReceived: Long = 0,
    val usingRelay: Boolean = false,              // local candidate type == relay
    val audioCodec: String? = null,               // log only
    val bweCeiling: Double? = null,               // bps, the estimator ceiling the session set (the estimate cannot pass it)
)
