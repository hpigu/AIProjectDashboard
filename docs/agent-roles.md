# Agent roles and dispatch

[Back to README](../README.md) · [繁體中文](../README.zh-TW.md)

The board works without role agents. Roles add parallel dispatch, client-side
tool boundaries, and a consistent integration workflow.

## Roles

| Role | Category | Responsibility |
|---|---|---|
| `backend-dev` | `BACKEND` | Server logic, API, database, data model |
| `frontend-dev` | `FRONTEND` | UI, styles, client state, interaction |
| `qa` | `TEST` | Tests and verification |
| `infra` | `INFRA` | CI/CD, packaging, build, environment |
| `docs` | `DOC` | User and developer documentation |
| `reviewer` | none | Read-only review of the integrated batch |

`OTHER` has no dedicated worker and is handled by the leader when the scope and
authorization are clear.

Each worker claims one task in its category, commits the result, reports evidence
to the leader, and stops. It does not mark the task done or claim another task.
Only one live worker per category is allowed at a time.

## Dispatch workflow

```text
planning session
  create project and dependency-aware tasks

implementation session
  leader records the batch and starts one eligible task per idle role
  worker claims, implements, verifies, commits, and reports
  leader validates the report and merges task branch into dev
  leader marks the task DONE only after the merge succeeds
  newly unblocked tasks can then be dispatched

review
  reviewer inspects main...dev after the batch is complete
  leader merges dev into main only after required fixes are resolved
```

Scheduling is event-driven. When a worker exits or a task changes state, the
leader fills any newly idle role without waiting for the rest of the batch.

## Git model

```text
main
└── dev
    ├── task/123-backend-dev
    ├── task/124-frontend-dev
    └── task/129-leader
```

- Create every task branch and worktree from the latest `dev`.
- Workers commit only to their task branch. They do not merge, push, or deploy.
- The leader uses `--no-ff` to merge a validated task branch into `dev`.
- Mark the board task `DONE` only after that merge succeeds.
- Keep dirty, blocked, unmerged, or uncertain worktrees intact.
- Remove a clean worktree after merge; keep its branch until the batch reaches
  `main`.

This order ensures that downstream tasks are not released before their required
code exists on the integration branch.

## Review boundary

The reviewer reads the complete `main...dev` diff and reports findings. It does
not edit files, create tasks, change board state, merge, push, deploy, or restart
the service. The leader decides whether a finding requires a new task.

Review starts only when every task in the batch is `DONE` and no unresolved
`BLOCKED` task remains.

## Exception handling

| Situation | Action |
|---|---|
| Worker is blocked | Keep branch/worktree, release the role slot, continue unrelated work |
| User input is required | Worker reports the decision and impact; leader asks the user |
| Worker stops unexpectedly | Preserve work and resume once with the same role; ask the user after a repeated failure |
| Merge conflict | Return mechanical conflicts to the owning role; pause and ask on semantic conflicts |
| Leader validation fails | Allow one focused rework; ask the user if it still fails |
| Existing `IN_PROGRESS` task | Find its live worker and branch before dispatching anything new |
| Dirty `main` or existing `dev` state | Do not stash, commit, discard, or overwrite without user direction |
| User pauses or cancels | Stop new dispatch and preserve completed work |

## Instruction sources

| Source | Purpose |
|---|---|
| Board `role` table | Complete worker instructions; project override takes precedence over generic role |
| `RoleSeeder` | Initial instructions for a new database; never overwrites an existing role |
| Plugin `agents/*.md` | Client tool allowlist, hard boundaries, and fallback when the board is unavailable |
| `claim-tasks` skill | Leader scheduling, Git integration, validation, review, and exception flow |
| Repository `AGENTS.md` | Architecture, test isolation, and production-safety rules for this repository |

Agent metadata and tool allowlists must exist in files because the client reads
them before the agent starts. Project-specific working instructions belong in
the board because `get_role` reads them at task start.

## Role lookup

`get_role(name, projectName)` returns:

1. the project-specific role when it exists;
2. otherwise the generic role;
3. otherwise the list of available roles.

A project-specific role is a complete instruction document, not a patch applied
to the generic role. Updating a role through `upsert_role` requires explicit
leader authorization. Changes take effect on the next `get_role` call.

## Worker tool boundary

The five workers receive only:

- `get_role`
- `claim_next_task`
- `block_task`
- `complete_task`
- `update_task_status`

They do not receive project creation, task editing, dependency editing, archive,
role-write, or claim-reset tools. Instructions returned by the board cannot
expand the client allowlist.

Claim tokens remain inside worker and leader context. Do not write them to files,
commits, task logs, or user-facing instructions.

## Plugin installation

The Claude Code and Codex plugins include six role shells and the leader skill.
See [`installation.md`](installation.md) for supported installation paths.

Role customizations are stored in the local H2 database and do not travel with
the thin plugin to another machine.
