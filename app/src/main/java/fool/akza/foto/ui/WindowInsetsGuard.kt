package fool.akza.foto.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps the window's status/navigation insets pinned to their visible values
 * while the full-screen viewer hides the system bars.
 *
 * Hiding the bars makes the platform animate their insets to zero (and back on
 * show). Every inset-driven layout in the app would follow that animation, so
 * the gallery grid, the frosted headers and the viewer chrome would visibly
 * shift in the middle of the viewer's tile-morph entry/exit. Reporting the
 * pre-hide insets for as long as the viewer is open keeps everything still,
 * while the bars themselves still fade out/in with the system's own animation.
 *
 * The guard records the last real insets on every dispatch and only overrides
 * them while frozen, so a rotation or a change of the bars outside the viewer
 * is picked up as usual.
 */
internal class WindowInsetsGuard {
    private var visibleInsets: WindowInsetsCompat? = null
    private var frozen = false

    /** Records the latest real insets while nothing is frozen. */
    fun record(insets: WindowInsetsCompat) {
        if (!frozen) visibleInsets = insets
    }

    /**
     * Called by the viewer right before it hides the system bars. [fallback] is
     * read from the window when no dispatch has been recorded yet (e.g. the
     * viewer opened straight from a share intent during the first frame).
     */
    fun freeze(fallback: WindowInsetsCompat? = null) {
        if (visibleInsets == null) visibleInsets = fallback
        frozen = true
    }

    /** Called when the viewer closes, after the bars have been shown again. */
    fun unfreeze() {
        frozen = false
    }

    /** Re-applies the pinned bar insets over [insets] while frozen. */
    fun apply(insets: WindowInsetsCompat): WindowInsetsCompat {
        val pinned = visibleInsets
        if (!frozen || pinned == null) return insets
        return WindowInsetsCompat.Builder(insets)
            .setInsets(
                WindowInsetsCompat.Type.statusBars(),
                pinned.getInsets(WindowInsetsCompat.Type.statusBars()),
            )
            .setInsets(
                WindowInsetsCompat.Type.navigationBars(),
                pinned.getInsets(WindowInsetsCompat.Type.navigationBars()),
            )
            .build()
    }
}

/** The activity's guard, so a full-screen surface can freeze the window insets. */
internal val LocalWindowInsetsGuard = compositionLocalOf<WindowInsetsGuard?> { null }
