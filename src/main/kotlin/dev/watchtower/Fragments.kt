package dev.watchtower

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/** Fragment screen name: the simple class name, so it reads like an activity screen. */
internal fun fragmentScreen(f: Class<*>): String = f.simpleName.ifEmpty { f.name.substringAfterLast('.') }

/**
 * Fragment screen views: every resumed fragment that shows content (not a dialog host or an
 * invisible retained fragment) becomes a `view`, like an activity. androidx.fragment is a
 * compileOnly dependency: this class is only loaded when the app has it ([Fragments.install]).
 */
internal class FragmentViews(private val tracker: ViewTracker) : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
        if (f.view == null) return
        tracker.appeared(fragmentScreen(f.javaClass), System.currentTimeMillis())?.let(Watchtower::record)
    }

    override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
        if (f.view != null) tracker.disappeared(fragmentScreen(f.javaClass), System.currentTimeMillis())
    }
}

internal object Fragments {
    private val present = runCatching { Class.forName("androidx.fragment.app.FragmentActivity") }.isSuccess

    /** Called for each created activity; recursive so child fragments are tracked too. */
    fun install(a: Activity, tracker: ViewTracker) {
        if (!present || a !is FragmentActivity) return
        a.supportFragmentManager.registerFragmentLifecycleCallbacks(FragmentViews(tracker), true)
    }
}
