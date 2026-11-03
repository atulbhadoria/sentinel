# Sentinel: project conventions

## Hard rules (never break)
- Investigation tools are READ-ONLY. No arbitrary shell or command execution, ever.
- No state-changing action runs without explicit human approval.
- Every decision and action is written to an audit log.
- Agent outputs are structured objects validated against a schema, never free text.

## Working style
- Build in slices (see SPEC.md). A slice is done only when its acceptance criteria pass and tests are green.
- Write tests for each slice; mock the LLM in unit tests.
- Simplest version first; add complexity one step at a time.
- One PR per slice; after opening a PR, stop and wait for review/merge.
- Update PROGRESS.md after each slice.

## Build
- `./mvnw verify` builds and runs the tests. It must pass with no LLM API key and no running services.
