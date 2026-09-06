# ARC-041 — The .NET gate failed once and passed on a re-run

- **Severity:** medium
- **Status:** done
- **Area:** `tests/Harken.Api.IntegrationTests/CustomWebApplicationFactory.cs`

## Problem

`test-fast.sh` failed:

```
Failed Harken.Api.IntegrationTests.TranscriptionJobTests.PollingAnUnknownSessionGetsNotFound
   at Microsoft.Extensions.Hosting.HostApplicationBuilder.HostBuilderAdapter.ApplyChanges()
   at Microsoft.Extensions.Hosting.HostApplicationBuilder.Build()
   at Microsoft.AspNetCore.Mvc.Testing.WebApplicationFactory`1.CreateHost(IHostBuilder builder)
```

An immediate re-run passed, as did six more. A gate that fails once in a while
is worse than a gate that fails: it teaches everyone to press the button again,
which is exactly the reflex that lets a real failure through.

The test factory had two defects that together explain the stack:

1. **The schema was created inside `ConfigureServices`.** It called
   `services.BuildServiceProvider()` — a second, throwaway container (this is
   what the ASP0000 analyzer exists to catch), whose singletons duplicate the
   real host's and which is never disposed. Because it ran during host
   construction, anything it threw surfaced from `Build()` with no indication of
   which test or why.
2. **Disposal called `SqliteConnection.ClearAllPools()`, which is
   process-global.** With two test assemblies and xunit's parallel collections,
   one class finishing tore down pooled connections belonging to factories other
   classes were still building. That is a cross-test dependency through a static,
   and it lands precisely where the stack says: in the victim's `Build()`.

## The fix

- Moved `EnsureCreated` and the WAL pragma into an override of `CreateHost`,
  after `base.CreateHost`, using the real host's provider. The throwaway
  container is gone.
- `Pooling=False` on the test connection string, so `Dispose` can delete the
  file without touching any other factory's pool. `ClearAllPools()` is gone.
  Nothing here is hot enough for the pool to be worth a global side effect.

## Evidence

`check.sh` and `test-fast.sh` green; the suite run six further times, green each
time. Honest limit: the original failure was never reproduced on demand, so this
is not proof the flake is gone. What it is: two real defects removed, both
capable of producing exactly the observed stack, and neither of which should
have been there regardless.

If it recurs, the next step is `--parallel none` on the assemblies to confirm
the interference is cross-collection rather than inside one.
