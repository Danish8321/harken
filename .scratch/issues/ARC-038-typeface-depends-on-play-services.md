# ARC-038 — The app's whole typeface system is a Play Services download

- **Severity:** high
- **Status:** open
- **Area:** `ui/theme/Type.kt`, `res/values/font_certs.xml`, `app/build.gradle.kts`

## Problem

All three families — Space Grotesk (headings), Figtree (body), IBM Plex Mono
(readouts) — are declared as `GoogleFont` against the downloadable-fonts
provider `com.google.android.gms.fonts`:

```kotlin
private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)
```

That means the app's visual identity is fetched at runtime from Google Play
Services. Three consequences, in the order they matter:

1. **It contradicts what the app is.** ADR-0011 says everything stays on the
   phone and the app makes no network calls. This one does — indirectly, through
   GMS, on first launch, before the user has recorded anything.
2. **It fails open, silently.** No GMS (a de-Googled phone, an emulator image
   without Play, a work profile that blocks it), or no network on first run, and
   every family falls back to the system default. The user sees a different app
   from the one that was designed — and nothing tells them, or us.
3. **It is why Lint reports 35 typos.** Every one of them is inside the base64
   certificate blobs in `font_certs.xml`. Not a single one is in prose. The
   whole `Typos` count in ARC-037 is this file.

## The fix

Bundle the three families as `res/font/*.ttf` and delete the provider, the
certificate array and the `ui-text-google-fonts` dependency. All three are SIL
Open Font License 1.1, which permits embedding and redistribution.

Typography then loads from the APK: deterministic, offline, identical on every
device, and with no Play Services in the picture at all.

## Why it is not fixed here

It adds font binaries to the repository — vendored third-party assets, which is
a dependency decision even though the net effect is to remove one. That needs
sign-off, and it wants a size budget: eleven weights are declared across the
three families, and subsetting to the weights actually used is part of the job.

Blocked on the same call: whether to keep eleven weights or cut to the six the
screens really use.
