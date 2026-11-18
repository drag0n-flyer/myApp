package org.example.app.ui.media

/**
 * Recordings that were stopped because their screen left or turned (rotation, a split-view resize), keyed by
 * "<accountId>/<chatId>", until the next screen of that chat takes them. Process memory only: if the app is killed the
 * files are removed at the next start. A screen that asks before the recording is finished is called back when it arrives
 * and must [forget] its request when it leaves. [clear] (session end, local-data wipe) deletes everything kept and makes
 * recordings still being finished delete themselves instead of being kept.
 */
object VoiceKeep {
    private val kept = HashMap<String, CaptureState.Preview>()
    private val waiting = HashMap<String, (CaptureState.Preview) -> Unit>()
    private var generation = 0

    /** Read when a capture is created; a [put] with an older value arrives after a [clear] and deletes its file. */
    fun currentGeneration(): Int = synchronized(this) { generation }

    fun put(key: String, preview: CaptureState.Preview, madeIn: Int = currentGeneration()) {
        var waiter: ((CaptureState.Preview) -> Unit)? = null
        val stale = synchronized(this) {
            if (madeIn != generation) {
                true
            } else {
                waiter = waiting.remove(key)
                if (waiter == null) kept[key] = preview
                false
            }
        }
        if (stale) preview.file.delete() else waiter?.invoke(preview)
    }

    /** Hands over the preview kept for [key] now, or when it arrives. */
    fun take(key: String, onPreview: (CaptureState.Preview) -> Unit) {
        val preview = synchronized(this) { kept.remove(key).also { if (it == null) waiting[key] = onPreview } }
        if (preview != null) onPreview(preview)
    }

    /** The screen that asked with [onPreview] is gone: it is not called back and not kept alive. */
    fun forget(key: String, onPreview: (CaptureState.Preview) -> Unit) {
        synchronized(this) { if (waiting[key] === onPreview) waiting.remove(key) }
    }

    fun clear() {
        val files = synchronized(this) {
            generation++
            waiting.clear()
            kept.values.toList().also { kept.clear() }
        }
        files.forEach { it.file.delete() }
    }
}
