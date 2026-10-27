package org.example.app.ui.qr

/**
 * Owns one camera binding for one screen visit. The provider arrives asynchronously, so a screen
 * can leave first: after [release] the provider is never bound, and if it is bound it is unbound
 * on release. Failures are reported through [onFailure], never thrown. Main thread only.
 */
class CameraBinder<P>(
    private val bind: (P) -> Unit,
    private val unbind: (P) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private var released = false
    private var provider: P? = null

    fun ready(p: P) {
        if (released) return
        provider = p
        try {
            bind(p)
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    fun failed(t: Throwable) {
        if (!released) onFailure(t)
    }

    fun release() {
        if (released) return
        released = true
        val p = provider ?: return
        provider = null
        try { unbind(p) } catch (_: Exception) { }
    }
}
