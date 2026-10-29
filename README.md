# Watchtower (Android)

[![CI](https://github.com/watchtowerobservability/watchtower-android/actions/workflows/ci.yml/badge.svg)](https://github.com/watchtowerobservability/watchtower-android/actions/workflows/ci.yml)

Watchtower SDK for Android: crashes, ANRs, app start, slow/frozen frames, RUM batches and screenshot replay that masks text and inputs by default, with an offline buffer.

Status: 0.x, early. APIs may change between minor versions.

## Install

Download the `.aar` from the latest release and add it as a file dependency (Maven publishing comes later).

## Use

Set the endpoint explicitly; the default is a placeholder.

Use a publishable key here, never a secret ingest key.

## Provenance

Every release artifact carries a GitHub build attestation. Verify one with
`gh attestation verify <file> --repo watchtowerobservability/watchtower-android`.

## License

Apache-2.0. Source of truth is the Watchtower monorepo; this repository is a published mirror.
