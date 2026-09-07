# ARC-038 — The app's whole typeface system is a Play Services download

- **Severity:** high
- **Status:** done
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

## Approved, 2026-09-07

Bundle them. Subset to the weights actually used, not everything declared.

## Blocked on one download

The only step left that this environment cannot do. Outbound network from the
shell is blocked here — `curl` to `fonts.google.com` and to `raw.githubusercontent.com`
are both refused — so the `.ttf` files cannot be fetched. Gradle has its own network
allowance, but adding a permanent download task to the build for a one-off vendoring
is the wrong shape.

Run this once, from the repository root, and the rest of the ticket is unblocked:

```bash
mkdir -p .scratch/fonts && cd .scratch/fonts
curl -L -o spacegrotesk.zip "https://fonts.google.com/download?family=Space%20Grotesk"
curl -L -o figtree.zip      "https://fonts.google.com/download?family=Figtree"
curl -L -o ibmplexmono.zip  "https://fonts.google.com/download?family=IBM%20Plex%20Mono"
unzip -o spacegrotesk.zip -d spacegrotesk
unzip -o figtree.zip      -d figtree
unzip -o ibmplexmono.zip  -d ibmplexmono
```

Each zip carries `OFL.txt` and a `static/` directory of per-weight `.ttf` files.
The variable-font builds (`*[wght].ttf`) would be three files instead of ten and
work from API 26, but static faces are the simpler thing that works and carry no
runtime API risk, so take those.

## What happens after the download

`app/src/main/res/font/` gains, lowercase-with-underscores as Android resource
names require:

| File | From | Used by |
|---|---|---|
| `space_grotesk_regular.ttf` | Space Grotesk 400 | `ProtoHeadingFont` |
| `space_grotesk_medium.ttf` | Space Grotesk 500 | `ProtoHeadingFont` |
| `space_grotesk_bold.ttf` | Space Grotesk 700 | `ProtoHeadingFont` |
| `figtree_regular.ttf` | Figtree 400 | `ProtoBodyFont` |
| `figtree_medium.ttf` | Figtree 500 | `ProtoBodyFont` |
| `figtree_semibold.ttf` | Figtree 600 | `ProtoBodyFont` |
| `figtree_bold.ttf` | Figtree 700 | `ProtoBodyFont` |
| `figtree_extrabold.ttf` | Figtree 800 | `ProtoBodyFont` |
| `ibm_plex_mono_regular.ttf` | IBM Plex Mono 400 | `ProtoMonoFont` |
| `ibm_plex_mono_medium.ttf` | IBM Plex Mono 500 | `ProtoMonoFont` |

Ten faces, which is what the three families declare between them — the earlier
count of eleven in this ticket was wrong. `FontWeight.Black` appears at two call
sites and is declared by nothing; Compose resolves it to Bold today and will keep
doing so, which is worth a look but is not this ticket.

Then: rewrite `ui/theme/Type.kt` to `Font(R.font.…, FontWeight.…)` and drop the
`GoogleFont.Provider`; delete `res/values/font_certs.xml`; drop
`compose-google-fonts` from `libs.versions.toml` and `app/build.gradle.kts`; add
each family's `OFL.txt` under `app/src/main/assets/licenses/` (SIL OFL 1.1
requires the licence to travel with the fonts). `.gitattributes` already marks
`*.ttf` binary.

Expected: Lint&rsquo;s 35 typos go to zero, because every one of them is inside the
certificate blobs in the file being deleted. That closes the last unblocked item
in [ARC-037](ARC-037-lint-warnings-formatter-and-ci.md) too.

## Resolution, 2026-09-07

The zip download link above no longer works — `fonts.google.com/download?family=...`
now serves the fonts.google.com app shell (HTML) to a plain `curl`, not a zip, so
`unzip` failed with "End-of-central-directory signature not found" on all three.
Used the CSS2 API instead: `fonts.googleapis.com/css2?family=...&wght@...` with an
old-browser `User-Agent` (`Mozilla/5.0 (Windows NT 6.1)`) returns `@font-face` rules
with `.ttf` src URLs straight to `fonts.gstatic.com` — no JS, no session, no zip
needed. Each family's `OFL.txt` came from `raw.githubusercontent.com/google/fonts`
(the `google/fonts` repo's canonical `ofl/<slug>/OFL.txt` path) since the zip that
normally bundles it was unavailable.

Vendored all ten static faces under `app/src/main/res/font/`, rewrote
`ui/theme/Type.kt` to plain `Font(R.font.…, FontWeight.…)`, deleted
`res/values/font_certs.xml`, dropped `compose-google-fonts` and
`compose-ui-text-google-fonts` from `libs.versions.toml`, and added each family's
`OFL.txt` under `app/src/main/assets/licenses/<slug>/`. `check.sh` and
`test-fast.sh` both pass. Lint's `Typos` count is 0 (was 35); the 19 remaining
warnings are all version notices (`GradleDependency`/`NewerVersionAvailable`) plus
one `ChromeOsAbiSupport` — none from fonts. Committed as `0696cd4`.
