package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Canvas
import android.os.Parcelable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.animation.Interpolator
import android.widget.Scroller
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap

/**
 * Pager implementation that listens for tap and long tap and allows temporarily disabling touch
 * events in order to work with child views that need to disable touch events on this parent. The
 * pager can also be declared to be vertical by creating it with [isHorizontal] to false.
 */
open class Pager(
    context: Context,
    isHorizontal: Boolean = true,
) : DirectionalViewPager(context, isHorizontal) {

    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    /**
     * Optional drawing done on top of the pages, in the pager's own (screen) coordinates. Used by
     * the page curl animation.
     */
    var overlayDrawer: ((Canvas) -> Unit)? = null

    var isRestoring = false

    override fun onRestoreInstanceState(state: Parcelable?) {
        isRestoring = true
        val currentItem = currentItem
        super.onRestoreInstanceState(state)
        setCurrentItem(currentItem, false)
        isRestoring = false
    }

    /**
     * Gesture listener that implements tap and long tap events.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            tapListener?.invoke(ev)
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            val listener = longTapListener
            if (listener != null && listener.invoke(ev)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    /**
     * Whether the given [ev] should be intercepted. Only used to prevent crashes when child
     * views manipulate [requestDisallowInterceptTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Handles a touch event. Only used to prevent crashes when child views manipulate
     * [requestDisallowInterceptTouchEvent].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Executes the given key event when this pager has focus. Just do nothing because the reader
     * already dispatches key events to the viewer and has more control than this method.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        // Disable viewpager's default key event handling
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val drawer = overlayDrawer ?: return
        // Children are drawn in content coordinates, so move back to the visible area first.
        val checkpoint = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        drawer(canvas)
        canvas.restoreToCount(checkpoint)
    }

    /**
     * Makes page settle animations [factor] times as long as the default (1 restores it). Curling a
     * page looks wrong when it completes as fast as a plain slide.
     */
    fun setSettleDurationFactor(factor: Float) {
        try {
            // Found by type rather than name so it still works when the library is minified.
            val field = DirectionalViewPager::class.java.declaredFields
                .firstOrNull { it.type == Scroller::class.java } ?: return
            field.isAccessible = true
            field.set(this, ScaledScroller(context, factor))
        } catch (_: Exception) {
            // Keep the default speed.
        }
    }

    private class ScaledScroller(context: Context, private val factor: Float) :
        Scroller(context, Interpolator { t -> (t - 1f).let { it * it * it * it * it + 1f } }) {
        override fun startScroll(startX: Int, startY: Int, dx: Int, dy: Int, duration: Int) {
            super.startScroll(startX, startY, dx, dy, (duration * factor).toInt())
        }
    }
}
