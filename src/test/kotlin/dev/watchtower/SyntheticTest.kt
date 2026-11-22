package dev.watchtower

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UPT-006: a check run's intent extras tag the session as that run. */
class SyntheticTest {
    @Test fun runIdFromIntentExtras() {
        val a = Synthetic.of("0123abcd", "mon_1")
        assertEquals("0123abcd", a["synthetic.run_id"])
        assertEquals("mon_1", a["synthetic.check_id"])
        assertTrue(Synthetic.of(null, "mon_1").isEmpty())
        // Without a run nothing is added.
        val o = JSONObject().put("attrs", JSONObject().put("k", "v"))
        assertEquals(1, Synthetic.tag(o).getJSONObject("attrs").length())
    }
}
