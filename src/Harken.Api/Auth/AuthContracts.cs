namespace Harken.Api.Auth;

// API-local DTO for the /auth/register endpoint: a transport shape for this API,
// not a domain contract. LoginRequest/TokenResponse moved to Harken.Core.Contracts
// once clients had to speak them too (see Task 6).

public sealed record RegisterRequest(string Email, string Password);
