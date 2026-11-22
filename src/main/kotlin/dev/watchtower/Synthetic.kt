package dev.watchtower

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.json.JSONObject

/**
 * Marks the RUM of a Watchtower mobile check run as synthetic (UPT-006): the check launches the
 * app with the intent extras `wt_synthetic_run_id` (and `wt_synthetic_check_id`), and every event
 * after the first activity then carries `synthetic.run_id`, so the run links to this session.
 */
internal object Synthetic {
    @Volatile
    var attrs: Map<String, Any> = emptyMap()
        private set

    fun of(run: String?, check: String?): Map<String, Any> {
        if (run.isNullOrEmpty()) return emptyMap()
        val a = mutableMapOf<String, Any>("synthetic" to true, "synthetic.source" to "watchtower", "synthetic.run_id" to run.take(64))
        if (!check.isNullOrEmpty()) a["synthetic.check_id"] = check.take(64)
        return a
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                if (attrs.isEmpty()) attrs = of(a.intent?.getStringExtra("wt_synthetic_run_id"), a.intent?.getStringExtra("wt_synthetic_check_id"))
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Adds the run's attributes to an event's `attrs`. */
    fun tag(o: JSONObject): JSONObject {
        val run = attrs
        if (run.isEmpty()) return o
        val at = o.optJSONObject("attrs") ?: JSONObject().also { o.put("attrs", it) }
        run.forEach { (k, v) -> at.put(k, v) }
        return o
    }
}
