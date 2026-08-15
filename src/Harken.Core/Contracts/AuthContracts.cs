namespace Harken.Core.Contracts;

// Shared with every client: the console, the mobile app, and the future browser
// extension all POST /auth/login and read the token back. RegisterRequest stays
// API-local — no client offers a sign-up screen.

public sealed record LoginRequest(string Email, string Password);

public sealed record TokenResponse(string Token, DateTimeOffset ExpiresAt);
