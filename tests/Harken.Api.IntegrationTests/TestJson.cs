using System.Text.Json;
using System.Text.Json.Serialization;

namespace Harken.Api.IntegrationTests;

// Mirrors the server's ConfigureHttpJsonOptions (Program.cs) so ReadFromJsonAsync in these
// tests decodes the same wire format real clients see — enums as strings, not ordinals.
public static class TestJson
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() },
    };
}
