using Harken.Core.Client;
using Xunit;

namespace Harken.Core.UnitTests;

public class TokenStoreTests
{
    /// <summary>In-memory stand-in for SecureStorage, so these run without MAUI.</summary>
    private sealed class FakeSecretStore : ISecretStore
    {
        public Dictionary<string, string> Values { get; } = new(StringComparer.Ordinal);

        public Task<string?> GetAsync(string key) =>
            Task.FromResult(Values.TryGetValue(key, out var value) ? value : null);

        public Task SetAsync(string key, string value)
        {
            Values[key] = value;
            return Task.CompletedTask;
        }

        public bool Remove(string key) => Values.Remove(key);
    }

    [Fact]
    public async Task GetTokenAsync_WhenNothingStored_ReturnsNull()
    {
        var store = new TokenStore(new FakeSecretStore());

        Assert.Null(await store.GetTokenAsync());
    }

    [Fact]
    public async Task SetTokenAsync_ThenGetTokenAsync_RoundTripsTheToken()
    {
        var store = new TokenStore(new FakeSecretStore());

        await store.SetTokenAsync("jwt-value");

        Assert.Equal("jwt-value", await store.GetTokenAsync());
    }

    [Fact]
    public async Task SetTokenAsync_Twice_KeepsTheNewestToken()
    {
        var store = new TokenStore(new FakeSecretStore());

        await store.SetTokenAsync("first");
        await store.SetTokenAsync("second");

        Assert.Equal("second", await store.GetTokenAsync());
    }

    [Fact]
    public async Task Clear_RemovesTheStoredToken()
    {
        var secrets = new FakeSecretStore();
        var store = new TokenStore(secrets);
        await store.SetTokenAsync("jwt-value");

        store.Clear();

        Assert.Null(await store.GetTokenAsync());
        Assert.Empty(secrets.Values);
    }

    [Fact]
    public async Task GetTokenAsync_WhenStoredValueIsBlank_ReturnsNull()
    {
        var secrets = new FakeSecretStore();
        await secrets.SetAsync(TokenStore.TokenKey, "   ");
        var store = new TokenStore(secrets);

        // A blank token is as good as no token: treat it as signed out rather than
        // sending an empty bearer header the server will reject.
        Assert.Null(await store.GetTokenAsync());
    }

    [Fact]
    public async Task SetTokenAsync_WithBlankToken_Throws()
    {
        var store = new TokenStore(new FakeSecretStore());

        await Assert.ThrowsAsync<ArgumentException>(() => store.SetTokenAsync("  "));
    }
}
