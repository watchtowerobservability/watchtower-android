package example

import android.app.Application
import dev.watchtower.Config
import dev.watchtower.Watchtower

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Publishable key only, never a secret ingest key.
        Watchtower.init(this, Config(key = "<publishable-key>", service = "example-android", endpoint = "https://<your-edge>"))
    }
}
