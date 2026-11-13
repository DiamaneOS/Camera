package app.grapheneos.camera.ui

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewGroup
import app.grapheneos.camera.CameraMode
import com.google.android.material.tabs.TabLayout

class BottomTabLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TabLayout(context, attrs) {

    private var sp = 0

    private val snapPoints: ArrayList<Int> = arrayListOf()

    // A finger is on the strip, from its down to its up.
    private var touching = false

    private var velocityTracker: VelocityTracker? = null

    // How fast the strip was scrolling when the finger last left it, in px per second.
    private var releaseVelocity = 0f

    // The tab TabLayout is scrolling to the middle, and since when.
    private var centering: Tab? = null
    private var centeringSince = 0L

    private lateinit var tabParent: ViewGroup

    val selectedTab: Tab?
        get() {
            return getTabAt(selectedTabPosition)
        }

    fun getTabForMode(mode: CameraMode): Tab? {
        for (index in 0 until tabCount) {
            val tab = getTabAt(index)
            if (tab?.tag == mode) {
                return tab
            }
        }

        return null
    }

    // Sees every event of a gesture on the strip, wherever it starts and whichever view handles it.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                centering = null
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
            }
        }
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                // The strip scrolls against the finger.
                releaseVelocity = velocityTracker?.takeIf { ev.actionMasked == MotionEvent.ACTION_UP }?.let {
                    it.computeCurrentVelocity(1000)
                    -it.xVelocity
                } ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // TabLayout scrolls a newly selected tab to the middle by itself. Under a finger that would
    // pull the strip away from it, so the strip stays where the finger put it.
    override fun scrollTo(x: Int, y: Int) {
        if (!touching) super.scrollTo(x, y)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)

        if (tabCount == 0) return

        tabParent = getChildAt(0) as ViewGroup
        val firstTab = tabParent.getChildAt(0)
        val lastTab = tabParent.getChildAt(tabParent.childCount - 1)
        sp = width / 2 - firstTab.width / 2
        getChildAt(0).setPaddingRelative(
            sp,
            0,
            width / 2 - lastTab.width / 2,
            0
        )

        val points = ArrayList<Int>(tabCount * 2)

        for (tabIndex in 0 until tabCount) {
            points.add(calculateScrollXStartForTab(tabIndex))
            points.add(calculateScrollXEndForTab(tabIndex))
        }

        // A pass that left the tabs where they were has nothing to put right. Scrolling here
        // would cut across TabLayout's own scroll to a newly selected tab.
        if (points == snapPoints) return

        snapPoints.clear()
        snapPoints.addAll(points)

        // Not under a finger either: this scroll would fight the drag.
        if (!touching) centerSelectedTab()
    }

    private fun centerSelectedTab() {
        getTabAt(selectedTabPosition)?.let {
            centerTab(it)
        }
    }

    override fun onScrollChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
        super.onScrollChanged(x, y, oldX, oldY)

        // Only a finger on the strip moves the highlight this way. Centering a tab animates the
        // scroll position through the places of every tab in between, and switching mode blocks
        // the main thread for long enough that those frames arrive after the switch.
        if (!touching) {
            centering?.let { if (x == calculateScrollXForTab(it.position)) centering = null }
            return
        }

        val tab = tabAt(x) ?: return
        if (selectedTabPosition != tab.position) {
            selectTab(tab)
        }
    }

    // snapPoints is empty until the first layout pass, and goes stale when the tab set is
    // rebuilt, so an index taken from it may no longer name a tab.
    private fun tabAt(x: Int): Tab? {
        if (snapPoints.isEmpty() || snapPoints.last() == 0) return null
        return getTabAt(TabStripSnap.index(snapPoints, x))
    }

    /** The tab a finger leaving the strip picks. */
    fun tabAtRelease(): Tab? = tabAt(TabStripSnap.landing(scrollX, releaseVelocity))

    // Selecting the selected tab again makes TabLayout start its scroll to the middle over. A
    // touch selects a tab up to three times in a row (the touch, the mode switch, the tab's own
    // click), and each new start would put the scroll out of step with the highlight.
    override fun selectTab(tab: Tab?, updateIndicator: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (tab != null && tab === selectedTab && tab === centering && now - centeringSince < CENTERING_MS) return
        super.selectTab(tab, updateIndicator)
        centering = tab?.takeIf {
            updateIndicator && !touching && this::tabParent.isInitialized &&
                scrollX != calculateScrollXForTab(it.position)
        }
        centeringSince = now
    }

    private fun centerTab(tab: Tab) {
        if (!this::tabParent.isInitialized) return
        val targetScrollX = calculateScrollXForTab(tab.position)

        if (scrollX != targetScrollX)
            smoothScrollTo(targetScrollX, 0)
    }

    private fun calculateScrollXForTab(position: Int): Int {
        val selectedChild = tabParent.getChildAt(position) ?: return 0
        val selectedWidth = selectedChild.width

        return selectedChild.left + selectedWidth / 2 - width / 2
    }

    private fun calculateScrollXStartForTab(position: Int): Int {
        val selectedChild = tabParent.getChildAt(position) ?: return 0

        return selectedChild.left - width / 2
    }

    private fun calculateScrollXEndForTab(position: Int): Int {
        val selectedChild = tabParent.getChildAt(position) ?: return 0
        val selectedWidth = selectedChild.width

        return selectedChild.left + selectedWidth - width / 2
    }

    fun getAllModes(): Set<CameraMode> {
        return IntRange(0, tabCount - 1).map {
            getTabAt(it)!!.tag as CameraMode
        }.toSet()
    }

    private companion object {
        // Longer than a mode switch and the scroll after it take together.
        const val CENTERING_MS = 1000L
    }
}
