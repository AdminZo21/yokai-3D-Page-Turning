package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

/**
 * Drives a [PageCurlRenderer] from the pager's scroll position.
 *
 * Every page is pinned to the screen instead of sliding. The page being turned is captured once
 * when the turn starts and hidden, while the renderer draws it curling away over the next page,
 * which is the real view and so keeps loading and drawing as usual.
 *
 * - L2R (`mirror` = false): the page at the lower index is turned, from its right edge.
 * - R2L (`mirror` = true): the page at the higher index is turned, from its left edge.
 *
 * If the turning page can't be captured the pager falls back to its normal slide for that turn.
 *
 * @param backgroundColor the reader background, painted behind the captured page.
 * @param onChanged called when the curl state changed and the pager needs to redraw.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class PageCurlTransformer(
    private val renderer: PageCurlRenderer,
    private val mirror: Boolean,
    private val backgroundColor: () -> Int,
    private val onChanged: () -> Unit,
) : ViewPager.PageTransformer {

    /** The page currently captured and being curled. */
    private var turning: View? = null

    /** A page that failed to capture; it just slides until it comes to rest. */
    private var uncapturable: View? = null

    init {
        renderer.mirror = mirror
    }

    override fun transformPage(page: View, position: Float) {
        val width = page.width
        if (width == 0 || page.height == 0) return

        // Pages at rest next to the current one stay exactly where the pager laid them out,
        // just off screen and still drawing, so their images keep loading.
        if (abs(position) >= REST_THRESHOLD) {
            restore(page)
            if (page === turning) release()
            if (page === uncapturable) uncapturable = null
            return
        }

        val isTurning = if (mirror) position >= 0f else position <= 0f
        if (!isTurning) {
            // The page being revealed: pinned in place underneath the curl.
            page.translationX = -position * width
            page.alpha = 1f
            return
        }

        // The current page at rest, or one that came back to rest after a partial drag.
        if (abs(position) < MOVING_THRESHOLD) {
            restore(page)
            if (page === turning) release()
            if (page === uncapturable) uncapturable = null
            return
        }

        if (page === uncapturable) {
            restore(page)
            return
        }

        if (turning !== page) {
            if (turning != null) release()
            if (!renderer.capture(page, backgroundColor())) {
                uncapturable = page
                restore(page)
                return
            }
            turning = page
        }

        page.translationX = -position * width
        page.alpha = 0f
        renderer.progress = abs(position)
        onChanged()
    }

    /** Stops the curl and hides nothing: pages are restored by [transformPage] and [restore]. */
    fun release() {
        turning = null
        renderer.release()
        onChanged()
    }

    companion object {
        /** Float slack so a page at rest (position of exactly +-1) is always treated as resting. */
        private const val REST_THRESHOLD = 0.999f

        /** Below this the turning page is considered back at rest. */
        private const val MOVING_THRESHOLD = 0.002f

        /**
         * Undoes everything the transformer applied to [page]. Call for each child of the pager
         * after removing the transformer, since the pager doesn't reset pages by itself.
         */
        fun restore(page: View) {
            page.translationX = 0f
            page.alpha = 1f
        }
    }
}
