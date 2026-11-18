package org.example.app.ui.media

/**
 * Recordings that were stopped because their screen left or turned (rotation, a split-view resize), keyed by chat, until
 * the next screen of that chat takes them. Process memory only: if the app is killed the files are removed at the next
 * start. A screen that asks before the recording is finished is called back when it arrives.
 */
object VoiceKeep {
    private val kept = HashMap<String, CaptureState.Preview>()
    private val waiting = HashMap<String, (CaptureState.Preview) -> Unit>()

    fun put(key: String, preview: CaptureState.Preview) {
        val waiter = synchronized(this) { waiting.remove(key).also { if (it == null) kept[key] = preview } }
        waiter?.invoke(preview)
    }

    /** Hands over the preview kept for [key] now, or when it arrives. */
    fun take(key: String, onPreview: (CaptureState.Preview) -> Unit) {
        val preview = synchronized(this) { kept.remove(key).also { if (it == null) waiting[key] = onPreview } }
        if (preview != null) onPreview(preview)
    }
}
