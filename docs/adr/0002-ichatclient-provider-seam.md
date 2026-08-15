# 2. IChatClient provider seam — Ollama now, Foundry later

Date: 2026-08-14

## Status
Accepted

## Context
The project must use Microsoft Agent Framework + Azure AI Foundry (a stated learning
goal). But provisioning Foundry (subscription, project, model deployment, cost) is a
blocker for phase 1, and iterating agents against a paid cloud model is slow.

Agent Framework composes agents over the `Microsoft.Extensions.AI` `IChatClient`
abstraction. Ollama (local), OpenAI, and Azure Foundry all converge on
`ChatClientAgent` through that same interface (verified against Agent Framework .NET
docs and the official `Agent_With_Ollama` sample).

## Decision
Treat `IChatClient` as the provider seam. Phase 1 binds it to a **local Ollama**
model (Gemma). Later phases rebind the same keyed client to **Azure Foundry** with no
change to agent code. Registered via keyed DI (`AddKeyedChatClient("chat-model")`)
and resolved in the agent factory.

## Consequences
- Phase 1 runs free, offline, fast to iterate — no Azure provisioning blocker.
- Foundry migration is a DI/config change, not a rewrite.
- Agent behavior differs between a local Gemma and a Foundry model; summaries must be
  re-validated when the provider is switched.
- Speech-to-text is unaffected — STT stays on Azure Speech regardless (Ollama has no
  STT). See the STT engine choice.
