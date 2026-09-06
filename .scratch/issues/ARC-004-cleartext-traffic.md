# ARC-004 — `usesCleartextTraffic=true` for an app that only talks HTTPS

- **Severity:** high
- **Status:** open
- **Area:** `app/src/main/AndroidManifest.xml`, `speech/ModelDownloadManager.kt`

## Problem

`android:usesCleartextTraffic="true"` is set. The only network destination left
in the app is the model download, whose URL is
`https://github.com/.../ggml-base.en.bin` — HTTPS, hardcoded, single host. The
Retrofit backend the flag was presumably added for is gone
([ADR-0011](../../docs/adr/0011-on-device-transcription.md)).

The attribute opts the whole app out of the platform's default cleartext block,
so an attacker who can influence a redirect gets a plaintext channel that the
platform would otherwise have refused.

## Fix

Drop the attribute (API 28+ then blocks cleartext by default at this
`targetSdk`), and add a `networkSecurityConfig` that pins the download to
`github.com`/`objects.githubusercontent.com` over TLS only.
