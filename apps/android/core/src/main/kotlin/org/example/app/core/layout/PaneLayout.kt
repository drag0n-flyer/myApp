package org.example.app.core.layout

/** One screen at a time, or the chat list beside the open chat. Decided from the window width alone. */
enum class PaneLayout {
    Single,
    TwoPane;

    companion object {
        /** The window width, in dp, from which two panes show. */
        const val TWO_PANE_MIN_DP = 840

        /** The width of the chat list pane in two-pane mode. */
        const val LIST_PANE_DP = 360

        fun forWidth(dp: Int): PaneLayout = if (dp >= TWO_PANE_MIN_DP) TwoPane else Single
    }
}
