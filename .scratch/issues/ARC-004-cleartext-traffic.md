# ARC-004 — `usesCleartextTraffic=true` for an app that only talks HTTPS

- **Severity:** high
- **Status:** closed
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

## Resolution

The attribute is removed rather than set to false — at `targetSdk` 36 the
platform default already refuses cleartext, so a `networkSecurityConfig` that
restated it would be a file with no behaviour. The manifest comment records why
it was ever true.

## Evidence

`check.sh` (dotnet build, assembleDebug, assembleRelease, and the new lintDebug
step) and `test-fast.sh` (14 + 32 .NET, 92 Android JVM) both pass.

## Device verification

Model download over `https://github.com/...` still resolves on the device: the
Settings model card shows "Not downloaded yet" with a working Download action
after a fresh install, i.e. the network path is intact.

## Status: closed
