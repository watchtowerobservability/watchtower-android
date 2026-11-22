# Changelog

## 0.1.2

- Sync with the monorepo: wireframe replay, ANR and tombstone threads, client storage instrumentation.

- Native crashes show every thread: tombstones (API 31+) carry the other threads' backtraces, the NDK handler dumps them (tgkill with a real-time signal) and adds each module's GNU build id so frames symbolicate on every Android version. ANR traces give the main thread's stack and the other threads.
- Client storage: `WatchtowerSQLiteOpenHelperFactory` (Room, SQLDelight, SupportSQLite; androidx.sqlite is compileOnly), `Datastore.measure` / `measureSuspend` for DataStore, MMKV, Realm, `Datastore.recordStoreSize` (file + WAL). Slow or failed ops as `db` events, main-thread work flagged `db.main_thread`.
- Requests carry the package name in `X-Watchtower-App-Id`; list it in the publishable key's app ids.
- Optional NDK crash handler (`-Pwatchtower.ndk=true`): native crashes with frames on every Android version.
- `feedback(message, name, email, eventId, done)` posts user feedback linked to the last error.
- Crash-loop guard: `launchCrashCount`, `isCrashLooping`, `crashLoopThreshold`; launch crashes are tagged `crash.launch`.

## 0.1.0

- Initial public release.
