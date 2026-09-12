# ARC-061 — Typing the next letter blanks the search results to "No matches"

- **Severity:** medium
- **Area:** `ui/LibraryViewModel.kt`, new `ui/LibrarySearch.kt`
- **Status:** fixed

## Problem

The Library's search runs under `collectLatest`, and the comment above it says exactly what
that is for:

```kotlin
// collectLatest, so a keystroke cancels both the debounce and any query already
// running for the term before it — the last thing typed is the only thing queried.
```

The body then catches the cancellation it just asked for:

```kotlin
delay(SEARCH_DEBOUNCE_MS)
_searchState.value = _searchState.value.copy(isSearching = true)
val hits =
    runCatching { repository.search(query) }
        .onFailure { Log.e(TAG, "Search failed", it) }
        .getOrDefault(emptyList())
_searchState.value = _searchState.value.copy(results = hits, isSearching = false)
```

`runCatching` catches `Throwable`, so the `CancellationException` raised inside
`repository.search` when the next keystroke supersedes the query is swallowed. The block
does not stop there. It logs the cancellation as an error, substitutes `emptyList()` for
"what the search found", and writes it to `_searchState`.

`collectLatest` cancels the previous block and *joins* it before starting the new one, so
that write always lands first — the state the new run begins from is one where the previous
term's hits have already been thrown away.

## What the user sees

`SearchQuery.MIN_LENGTH` is 2, so this starts at the second character.

`LibrarySearchState.isActive` is true as soon as the term is long enough, and `LibraryScreen`
branches on the pair:

```kotlin
isSearching && results.isEmpty() -> // spinner
results.isEmpty() ->               // "No matches"
```

The cancelled run leaves `results` empty and `isSearching` **false**, which is the second
branch. The header above it reads "0 results" from the same list. So on every keystroke typed
faster than a query completes, the results that were on screen are replaced by a confident
"No matches" for the debounce plus the length of the new query — at minimum 180 ms, longer on
a device with a large transcript table, and once per keystroke for as long as the user keeps
typing. It corrects itself when they stop, which is why it reads as flicker rather than as a
broken search.

The log line is the second cost: `Log.e(TAG, "Search failed", CancellationException)` for
ordinary typing means the one error this screen can report is almost always about nothing.

## Related but not this

The project already named this defect class and fixed it once —
`ModelDownloadManager.runCatchingDownload`:

> [runCatching] without swallowing cancellation. Plain `runCatching` catches
> `CancellationException` too, which turns "the user left the screen" into "the download
> failed".

ARC-060 found the same shape in `LibraryExporter.export` and replaced both `runCatching`
blocks with `try`/`catch` that rethrow `CancellationException`. This is the third site. Every
other `runCatching` in the module is non-suspending — parsing, `File` operations,
`startActivity`, `notify`, codec release — and cannot be cancelled, so this is the last one.

## The fix

The `runCatching` becomes a `try`/`catch` that rethrows `CancellationException` and swallows
only `Exception`, so a cancelled run publishes nothing at all and the hits already on screen
stay there until the new ones replace them.

Getting a test onto that needed the run to exist outside the ViewModel. `LibraryViewModel` is
an `AndroidViewModel` built from a `container`, so nothing in its `init` is constructible on
the JVM, and there is no Robolectric in this module. The whole of the `collectLatest` body
moved to `ui/LibrarySearch.kt` as one suspend function over a `MutableStateFlow` and a
`suspend (String) -> List<SearchHit>` — the same reason `PlaybackCursor` already exists in
this package, and the same shape as ARC-060's `ExportDestination`. What it has to get right is
what it does when it is cancelled, and cancellation is the *ordinary* end of a run here, not
the exceptional one.

## Evidence

`.claude/scripts/test-fast.sh`: **219 tests, 0 failures** (215 before). `check.sh` OK.

`LibrarySearchTest`, four tests over a `MutableStateFlow` and a search function supplied by
the test — no repository, no Application:

- `a keystroke cancelling a running query leaves the hits already on screen alone`
- `a query that finishes publishes its hits`
- `a query that fails reads as no matches rather than as a stuck spinner`
- `a term too short to query clears what the last one found`

The first is the ticket. Its search function completes a `CompletableDeferred` to say it has
started and then awaits one that never completes, so the run is still inside the query when
the job is cancelled — a search that has already returned cannot be cancelled, and that is
where the old code got away with it.

Falsified by putting the `runCatching` back verbatim: **219 tests, 1 failure**, and the
failure is exactly `a keystroke cancelling a running query leaves the hits already on screen
alone`. The other three stay green, `a query that fails reads as no matches rather than as a
stuck spinner` in particular — it is the control that says the `catch (e: Exception)` arm
still swallows a real failure rather than letting it out into `collectLatest`.

Not observed on a device. What the tests pin is that a cancelled run publishes nothing; what
they do not pin is the interval — how long the wrong "No matches" was actually on screen
depends on how large the transcript table is on the phone. The lower bound is the 180 ms
debounce, which is already longer than a keystroke.
