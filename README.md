# Watchtower (Android)

[![CI](https://github.com/watchtowerobservability/watchtower-android/actions/workflows/ci.yml/badge.svg)](https://github.com/watchtowerobservability/watchtower-android/actions/workflows/ci.yml)

Watchtower SDK for Android: crashes, ANRs, app start, slow/frozen frames, RUM batches and screenshot replay that masks text and inputs by default, with an offline buffer.

Status: 0.x, early. APIs may change between minor versions.

## Install

Download the `.aar` from the latest release and add it as a file dependency (Maven publishing comes later).

## Use

Set the endpoint explicitly; the default is a placeholder.

Use a publishable key here, never a secret ingest key.

Screens: every resumed activity is a RUM `view` (`trackViews = false` turns it off). Fragments
are not tracked automatically; record them with `Watchtower.record(Event("view", "CartFragment"))`.

Network: add `WatchtowerInterceptor()` to your `OkHttpClient.Builder()`. Each call becomes a
`resource` event (URL without query, method, status, duration, size). Hosts listed in
`Config(propagateTo = listOf("https://api.example.com/"))` also get a W3C `traceparent` header.

Storage: wrap your SQLite factory, `Room.databaseBuilder(...).openHelperFactory(WatchtowerSQLiteOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()))`
(SQLDelight: pass the same `factory` to `AndroidSqliteDriver`). DataStore, MMKV and Realm:
`Datastore.measure("mmkv", "encode") { ... }` or `Datastore.measureSuspend("datastore", "updateData") { store.updateData { ... } }`.
Sizes: `Datastore.recordStoreSize("sqlite", context.getDatabasePath("app.db"))`. Slow or failed operations become
`db` events (operation and table only, never statement text); main-thread work over one frame is flagged `db.main_thread`.

Session end: on the next launch the previous session gets `session.end_reason` (crash, anr, oom,
native_crash, user_exit, background_timeout), from `ApplicationExitInfo` on Android 11+. Native
(NDK) crashes are reported from the exit record: on Android 12+ the SDK decodes the protobuf
tombstone on the device (signal, abort message and the crashing thread's backtrace with build
ids, up to 256 KiB), and the server symbolicates it against symbol files you uploaded. Building with
`-Pwatchtower.ndk=true` (needs the NDK and CMake) also ships `libwatchtower-ndk.so`, an in-process
signal handler that records the signal and up to 64 frames (module and offset) and then passes the
crash on to the system; the next launch sends it as a `NativeCrash` error with `error.stack` in
tombstone form, on every Android version.

## App id

The SDK sends the package name as `X-Watchtower-App-Id`. An app sends no `Origin`, so add that package name to the publishable key's **app ids** (Settings, Browser keys) or the edge refuses its events and feedback with 403 `app_id_not_allowed`.

## User feedback

```kotlin
Watchtower.feedback("The checkout button does nothing", name = "Ann", email = "ann@example.com") { ok -> }
```

It is linked to the last error the SDK recorded (or pass `eventId`). Name and email become
pseudonyms at the edge. `ok` is false when nothing was sent; it never throws.

## Crash-loop guard

A crash within 5 s of `init` is a launch crash; such crash events carry `crash.launch`,
`crash.since_launch_ms` and `crash.loop_count`. `Watchtower.launchCrashCount` counts the
consecutive launches that ended in one, and `isCrashLooping` is true from
`Config(crashLoopThreshold = 2)`. Check it right after `init` to enter a safe mode:

```kotlin
Watchtower.init(this, config)
if (Watchtower.isCrashLooping) { skipRemoteConfig(); clearCaches() }
```

A native crash is only known from the exit record, so it is counted a moment after `init`.

## Provenance

Every release artifact carries a GitHub build attestation. Verify one with
`gh attestation verify <file> --repo watchtowerobservability/watchtower-android`.

## License

Apache-2.0. Source of truth is the Watchtower monorepo; this repository is a published mirror.

## Also in this version

Fragment screen views (when `androidx.fragment` is present), `WatchtowerHttp.trace(connection) { ... }`
for `HttpURLConnection`, other threads' stacks on crashes and ANRs (`error.threads`), opt-in
launch profiling (`Config.profileLaunch`, event `app_launch_profile` with folded stacks),
`Config.buildId` (`debug.build_id` on errors) and `replay.session_id` on errors when replay is on.
ProGuard/R8 mappings and NDK symbols upload through `sdks/gradle-plugin` (`watchtowerUpload`).

## Publishing (owner step)

The release workflow publishes to Maven Central (signed) when the repository secrets
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` (armored private key) and
`SIGNING_PASSWORD` are set; GitHub Packages works with no extra setup. The `dev.watchtower`
namespace must be verified on the Central Portal first.
