# Sentinel: specification

This is the authoritative spec for the project. Conventions for working on it live in CLAUDE.md; status lives in PROGRESS.md.

## Working agreement
1. Do not build everything at once. Work in the three slices defined below, in order.
2. First PR = project scaffold only: a building (but logic-free) Maven Spring Boot skeleton, plus SPEC.md (this spec), CLAUDE.md (the conventions below), PROGRESS.md, README.md stub, and the package structure as empty packages. The PR description must contain the proposed plan: package structure, core domain model, and the Slice 1 task breakdown. Then stop and wait for review/merge before coding Slice 1.
3. After that, one PR per slice (Slice 1, 2, 3). Each PR must be independently buildable, include its own tests, and update PROGRESS.md. After opening each PR, stop and wait for review/merge before starting the next slice.
4. Keep commits small and focused with clear messages.
5. Mock the LLM in unit tests so they run free in CI. Live-LLM behavior (diagnosis loop, eval harness) needs an LLM API key provided as a repo secret; if it is absent, still build everything and make all mocked/unit/integration tests pass, and clearly note in the PR what needs the key to run.
6. Ask a clarifying question (via a PR comment or by pausing) whenever a decision is ambiguous or affects architecture or safety. Do not guess on those.
7. Prefer the simplest version that works, then add complexity. No premature abstractions.

## Mission
Build "Sentinel": an autonomous on-call incident-response copilot. Given a production alert, it investigates the root cause on its own using read-only tools, proposes a structured remediation plan, and executes it only after a human approves.

## Stack
- Java 21, Spring Boot 3.3+, Maven.
- Spring AI (latest stable) for LLM calls, tool/function calling, chat memory, RAG, and MCP.
- PostgreSQL + pgvector as the vector store.
- LLM provider behind a configurable interface; default one provider but make swapping trivial. Never hard-code a vendor.
- Docker + docker-compose for the full local environment.
- Testing: JUnit 5, AssertJ, Mockito, Testcontainers, WireMock.
- Observability: Micrometer tracing plus a persisted audit log table.

## Repo layout
```
sentinel/
  pom.xml, docker-compose.yml, CLAUDE.md, README.md, ARCHITECTURE.md, PROGRESS.md, SPEC.md
  src/main/java/com/sentinel/
    intake/        webhook controller, payload adapters, dedup, queue
    domain/        Incident, Severity, Hypothesis, Plan, Action, ActionResult, AuditEntry
    orchestrator/  state-machine/graph driving the agent pipeline
    agents/        TriageAgent, DiagnosisAgent, RemediationPlannerAgent, CriticAgent
    tools/readonly/   metrics, logs, runbook-search (RAG), deployment-history + MCP server
    tools/action/     restart, scale, rollback, clearCache, toggleFlag, ticket + MCP server
    memory/        short-term incident memory + long-term incident/postmortem store
    rag/           embedding, chunking, retrieval over runbooks + postmortems
    approval/      human approval gate (CLI or REST; optional Slack)
    executor/      dry-run, idempotency, blast-radius, circuit breaker, verify + rollback
    llm/           provider abstraction, model router (cheap vs strong), cost tracking
    guardrails/    output validation, action allowlist, prompt-injection defense
    observability/ tracing, per-incident timeline, audit logging
    eval/          dataset loader, scorer, results table
  src/test/java/com/sentinel/...
  demo/
    faulty-service/   toy target app with fault-injection + admin endpoints
    prometheus/       prometheus + alertmanager config
    runbooks/         seed runbooks and postmortems for RAG
    datasets/         golden incidents JSON for eval
```

## Core domain model (define early)
- Incident: id, fingerprint, source, severity, raw payload, canonical fields, status, timeline.
- Hypothesis: root-cause statement, confidence, evidence list, tool calls made.
- Plan: ordered list of Action invocations, each with action id, parameters, rationale, risk level.
- Action: id, parameters, idempotency key, blast-radius limit.
- ActionResult: status, output, timestamp.
- AuditEntry: who, what, when, why, before/after.

All agent outputs are structured objects validated against a schema, never free text.

## Agent pipeline
An orchestrator drives four specialist agents via a state-machine/graph pattern, with durable state checkpointed to the DB so a long investigation survives a restart. Each agent has its own system prompt (stored as a version-controlled file) defining role, allowed tools, and output schema.
1. TriageAgent: classify severity, decide routing.
2. DiagnosisAgent: investigation loop. Form a hypothesis, gather evidence via read-only tools and RAG, refine until confident. Output a Hypothesis.
3. RemediationPlannerAgent: turn the hypothesis into a structured Plan.
4. CriticAgent: review the plan for risk, scope, and blast radius before it reaches the human.

## Tools (two separated MCP servers)
- Read-only investigation tools (never need approval): `queryMetrics(service, metric, window)`; `fetchLogs(service, level, window)`; `searchRunbooks(query)` backed by RAG; `recentDeployments(service)`.
- Action tools (write-capable, approval required): `restartService(service, instanceLimit)`; `scaleService(service, replicas)`; `rollbackDeployment(service, version)`; `clearCache(service, cacheKey)`; `toggleFeatureFlag(flag, state)`; `openTicket(summary, details)`.

The agent may only select from and parameterize these primitives. It must never get raw shell or arbitrary command execution.

## Input side (alert intake)
- `POST /webhooks/alert`: accept payload, return 200 immediately, enqueue for async processing (in-memory queue for MVP, abstracted so it can become Kafka/RabbitMQ later).
- An adapter layer normalizes at least Prometheus Alertmanager and a generic schema into the canonical Incident.
- Deduplicate/group by fingerprint so a repeatedly firing alert creates one investigation, not many.
- docker-compose includes Prometheus + Alertmanager and the toy faulty service, with an alert rule wired so inducing a fault produces a real alert hitting the webhook.

## Output side (executor)
On approval, execute each Action with: dry-run mode, idempotency keys (a retry must not double-act), per-action blast-radius limits, a circuit breaker that escalates to a human on failure instead of looping, and a full audit log. After execution, run a verification step: re-query the alert's triggering metric to confirm resolution; if unresolved, roll back via a compensating action or re-escalate. Action tools call the toy service's admin endpoints (`/admin/restart`, `/admin/scale`, `/admin/rollback`, `/admin/cache`), so nothing real is ever at risk but the architecture is production-shaped.

## Cross-cutting
- Guardrails: validate all structured outputs against schemas; enforce the action allowlist; read-only tools need no approval, any state-changing action does. Defend against prompt injection via ingested data: log lines and alert payloads are untrusted and must never hijack agent instructions. Include explicit tests for this.
- Cost and latency: a model router sends triage/classification to a cheap model and diagnosis to a strong model; cache where sensible; track tokens and cost per incident.
- Observability: trace every agent step and tool call; expose a per-incident timeline; persist an audit trail.

## Testing (implement all four layers)
1. Unit tests for deterministic plumbing (tools, parsers, router, guardrails, approval gate, executor) with the LLM client mocked. Fast, free, every commit.
2. Integration tests for tools and MCP servers using Testcontainers (pgvector) and WireMock (external APIs); contract-test the MCP schemas.
3. Eval harness: a golden dataset of synthetic incidents (JSON), each with a known root cause and acceptable remediation. Run the agent over each; score root-cause accuracy (exact match or LLM-as-judge against a rubric), tool-call efficiency, correct escalation, latency, and cost. Emit a results table. Set thresholds so regressions can fail CI. Validate the judge against human labels on a sample.
4. Adversarial tests: the agent must never execute a destructive action without approval, and must resist prompt injection from log/alert content.

- Use record/replay of LLM responses so behavioral tests are stable and free in CI; run a small live-model suite on a schedule. Use temperature 0 for reproducibility; judge eval pass-rate and variance over multiple runs, not a single run.
- CI (GitHub Actions): layers 1, 2, 4 on every push; full eval nightly or on a run-evals label.

## Slices and acceptance criteria
- **Slice 1, the spine:** one DiagnosisAgent takes a seeded alert, calls two read-only tools (metrics + logs, stubbed), returns a structured Hypothesis. Done when: app runs, a seeded alert produces a hypothesis, mocked-LLM unit tests pass.
- **Slice 2, intelligence:** add RAG over runbooks, short/long-term memory, the four-agent pipeline + orchestrator with durable state, both MCP tool servers, and the human-approval gate. Done when: a seeded alert flows through all four agents to a structured plan sitting at the approval gate, integration tests pass.
- **Slice 3, production hardening:** add the executor (dry-run, idempotency, blast-radius, audit, verify + rollback), the eval harness with a results table, observability/tracing, cost routing, the Prometheus + Alertmanager + toy-service demo loop, and adversarial tests. Done when: the full demo loop runs end to end (induce fault, real alert, diagnose, plan, approve, execute, verify resolved), the eval table is generated, all tests pass, README + ARCHITECTURE.md complete.

## Deliverables
- `docker-compose up` brings up Sentinel + Postgres/pgvector + Prometheus/Alertmanager + the toy service.
- README with problem statement, demo recording placeholder, architecture diagram placeholder, quick-start, and the eval results table.
- ARCHITECTURE.md with design decisions, a dedicated "safety model" section (allowlisted actions, approval gate, dry-run, idempotency, blast-radius, audit, verify/rollback, prompt-injection defense), and known failure modes and trade-offs.
- Seed scripts, runbooks, and the golden-dataset JSON.

## Non-negotiables
- Investigation is read-only. No arbitrary command execution, ever.
- Nothing that changes state runs without human approval.
- Every decision and action is audited.
- Each slice ships with its tests and is independently buildable.
