# Agent rules

Rules for every coding agent (Claude, Codex) working in this repository.

## Read first
- [GOAL.md](GOAL.md): product goal, decisions, roadmap. Section 16 holds the AI-development rules below in full.
- [UI design](docs/superpowers/specs/2026-10-27-app-ui-design.md) and its [mockups](docs/superpowers/specs/mockups/).
- [UI build plan](docs/superpowers/plans/2026-10-27-app-ui-sample-data.md): work task by task, in order.
- [Server design](docs/superpowers/specs/2026-10-27-server-design.md) and [server plan](docs/superpowers/plans/2026-10-27-go-server.md): work task by task, in order.

Decisions in these documents were made by the project owner. Do not change them on your own; ask.

## How work is done
- Follow the plan's steps exactly: write the failing test, see it fail, implement, see it pass, commit.
- Use the exact names, signatures, strings, and values given in the plan and the design.
- Code is written on Linux. `AppCore` tests run locally: `cd apps/apple/Packages/AppCore && swift test`.
- Server (Go, in `server/`) tests run locally and need Postgres: `cd server && TEST_DATABASE_URL='postgres:///postgres?host=/var/run/postgresql' go test ./...`. CI runs them in `.github/workflows/server.yml`.
- iPhone and Mac builds and UI tests run in GitHub Actions on `macos-26` runners. Push, then check with `gh run watch` and `gh run view --log-failed`. Never claim an app task passes without a green run.

## Rules
- **Commits:** plain messages, no `Co-Authored-By` or other AI attribution trailers.
- **Dependencies:** add none without the owner's approval.
- **Secrets:** never handle the APNs key, signing certificates, or production credentials; never commit secrets.
- **Human review:** code touching cryptography, authentication, keys, protocol state machines, the push gateway, or the WebRTC/Opus build needs the owner's review before merge.
- **UI:** Apple system components only; SF Symbols; system text styles; VoiceOver labels on every control; leading/trailing layout; all strings in the String Catalog.
- **No encryption labels** in the chat list, chat, or call screens; only the chat info page shows the encryption type.
- **Plain words** in the UI: never show "codec", "kbps", "relay", "MLS", or "E2EE".
- **Brand:** the app name is the placeholder `[App]` (`AppBrand.name`) until the owner chooses one.
