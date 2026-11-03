# Progress

| Slice | Scope | Status |
|---|---|---|
| 0 | Scaffold: building skeleton, SPEC, CLAUDE, PROGRESS, README stub, empty packages, CI | In review |
| 1 | Spine: DiagnosisAgent + stubbed metrics/logs tools -> structured Hypothesis | Not started |
| 2 | Intelligence: RAG, memory, four-agent pipeline, orchestrator, MCP servers, approval gate | Not started |
| 3 | Hardening: executor, eval harness, tracing, cost routing, demo loop, adversarial tests | Not started |

## Slice 0: scaffold
- Maven Spring Boot skeleton (Java 21, Spring Boot 3.5.16, Spring AI BOM 1.1.8 managed but no starter on the classpath yet).
- Package structure from SPEC.md, as empty packages with `package-info.java` describing each one.
- One context-load test; GitHub Actions runs `./mvnw verify` on every push and PR.

### Needs an LLM key
- Nothing yet.

### Open decisions
- See the scaffold PR description: Spring Boot/Spring AI line, default LLM provider, where alert intake lands.
