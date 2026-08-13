# Product roadmap

Last updated: 2026-08-13
Current release: [`v3.2.1`](https://github.com/hpigu/AIProjectDashboard/releases/tag/v3.2.1)

This roadmap tracks current product decisions. Released changes belong in
[`CHANGELOG.md`](../CHANGELOG.md); implementation history remains in git.

## Product direction

AI Project Board is a local coordination layer for developers running multiple
Claude Code or Codex sessions. Its core promise is narrow:

- prevent two agents from receiving the same task;
- enforce task prerequisites before dispatch;
- preserve structured blockers and completion evidence;
- show cross-project progress without requiring a hosted account.

It is not intended to replace a general-purpose human project-management suite.

## Current priorities

| Priority | Outcome | Completion signal |
|---|---|---|
| P0 | Improve discovery | English repository homepage, accurate GitHub description and topics, and a demo that shows concurrent claiming, dependency release, blockers, and evidence |
| P0 | Shorten first-time setup | A new user can install the server, connect one supported agent, and create the first task without reading internal release documentation |
| P0 | Validate the Windows bundle | Run the `v3.2.1` ZIP in Windows Sandbox and record SmartScreen, non-admin, startup, shutdown, and data-location results |
| P0 | Observe external users | At least five non-authors attempt the setup; record time-to-first-card and the step where each person stops |
| P1 | Remove repository-specific role seeds | A user project named `AgentDashboard` must not receive this repository's paths, ports, or commit rules |

Feature work that does not improve one of these outcomes waits until the first
external setup results are available.

## Delivery status

| Area | Current state | Known gap |
|---|---|---|
| Release | `v3.2.1` publishes Linux x64, macOS arm64/x64 JARs, a Windows x64 ZIP, and `SHA256SUMS.txt` | Release downloads have not yet produced verified external installs |
| Windows | ZIP includes a JDK 21 jlink runtime; CI checks lifecycle and update rollback | Windows Sandbox validation is still pending |
| macOS/Linux | Platform JARs are published | JDK 21 is required; `install/install.sh` is not distributed as a standalone release asset |
| Agent clients | Claude Code and Codex CLI paths are documented; plugin-page updates were validated in Codex and Claude Desktop | Clean first-time desktop GUI installation still needs an external-user test |
| Language | The web UI supports English and Traditional Chinese | MCP descriptions, role instructions, CLI output, and most errors remain primarily Traditional Chinese |

## Deferred work

These items need evidence before implementation.

| Item | Start when |
|---|---|
| Project deletion | Users repeatedly need to remove test projects; require archive-before-delete and audit coverage |
| Export/import | A user needs to move data between machines or analyse it outside H2 |
| Cross-project attention view | A user actively runs more than three projects and misses blocked work |
| Cycle-time and throughput reports | Users ask for delivery-time or role-throughput analysis |
| Priority, due date, estimate, labels | `sort_order` and dependencies are insufficient for real projects |
| Webhooks | Desktop notifications do not reach the person responsible for blockers |
| Static analysis and coverage gates | A second contributor begins submitting changes regularly |
| CI browser regression suite | The frontend has another regression not caught by the current checks |

## Engineering debt

- `RoleSeeder` contains AgentDashboard-specific overrides that should move to a
  development fixture or external project configuration.
- Several services cross package boundaries through repositories. Revisit the
  aggregate boundary before the task and project packages grow further.
- `TaskEditTools` and `TaskBlockTools` lack direct MCP adapter tests. Add them
  when either adapter changes.

## Explicit non-goals

- Public or multi-tenant hosting without server-side authentication.
- Horizontal scaling of the in-process SSE registry.
- Docker packaging while the supported lifecycle, backup, and recovery model is
  host-process based.
- Background or implicit updates. Server and plugin updates remain explicit.
- A second general write API. MCP remains the only write surface; REST stays
  read-only.

## Manual validation still required

- Follow [`windows-sandbox-clean-install-checklist.md`](windows-sandbox-clean-install-checklist.md)
  with the published `v3.2.1` ZIP and fill in its validation record.
- Test the documented Codex Git marketplace flow from an environment that has
  never installed the local marketplace.
- Ask external testers to stop at the first unclear or failing step instead of
  helping them through it; that step is the product evidence.
