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

Each worker instance claims one task in its category, commits the result, reports
evidence to the leader, and stops. It does not mark the task done or claim another
task. Multiple instances of the same role may run concurrently when category
capacity and platform session slots allow it. `OTHER` remains leader-owned and
does not use a worker slot.

## Capacity and configuration

Capacity is counted independently for each project and worker category. The
environment variables are shared by all projects using a server; they are not
per-project settings:

| Variable | Default |
|---|---:|
| `BOARD_CAPACITY_BACKEND` | 3 |
| `BOARD_CAPACITY_FRONTEND` | 1 |
| `BOARD_CAPACITY_TEST` | 1 |
| `BOARD_CAPACITY_INFRA` | 1 |
| `BOARD_CAPACITY_DOC` | 1 |

Values must be ASCII decimal integers from 1 through 2147483647, with no leading
zero. Empty values, signs, whitespace, decimals, and values outside that range
are rejected at startup (Java) or when the Workers service is initialized. The
Workers Wrangler configs carry the same defaults in `vars`.

`IN_PROGRESS` tasks consume capacity, including work awaiting integration and
claims left by an interrupted session. `BLOCKED` tasks release capacity; check
the latest snapshot before resuming one. Reopening a `DONE` task is also guarded.
Transitions that leave a task in its current state do not count as new work.
Lowering a limit does not stop existing work, but the leader must pause new
dispatch while the category is over capacity. The board has no reliable session
liveness signal and never reclaims or reassigns an abandoned claim automatically.

The platform's total live-session limit includes the leader. For example, if the
current platform allows four sessions total, keep at most three worker sessions;
this is an example, not a universal platform limit. Do not start idle sessions to
fill the quota. Account for prerequisites, file/resource conflicts, existing
claims, and leader-side reservations; do not count a reserved task again after it
has been claimed. If capacity is one, use the usual sequential dispatch.

### Three Claude Code Backend workers

For a platform that can run the leader plus three workers, the leader can use
three `backend-dev` Claude Code instances. Each instance receives a distinct
assignee (for example, `backend-t12-a`, `backend-t12-b`, `backend-t12-c`, each no
longer than 60 characters) and one expected task ID. The leader briefly
serializes the claim handshake within BACKEND: select an eligible, unreserved
task; start its worker in that task's worktree; wait for the actual claimed
task ID; then assign the next distinct candidate. Each worker still calls
`claim_next_task` itself. If the returned ID differs from the expected ID, it
stops before editing and reports the mismatch so the leader can re-plan.

Create each `task/<task-id>-backend-dev` branch/worktree from the latest `dev`.
Give every worker its own worktree and task/instance-specific development port,
database, log, and PID. Never share a mutable worktree or local database. Check
prerequisites and obvious file/resource conflicts first; fewer than three
workers is correct when fewer independent tasks are ready. Codex workers follow
the same role, capacity, serialized claim, task-ID, and isolation rules. The
claim result's task `capacity` is a snapshot for the leader, not a session
reservation or caller identity.

## Dispatch workflow

```text
planning session
  create project and dependency-aware tasks

implementation session
  leader reads capacity snapshots and dispatches eligible, unreserved tasks
  worker claims, implements, verifies, commits, and reports
  leader validates the report and merges task branch into dev
  leader marks the task DONE only after the merge succeeds
  newly unblocked tasks can then be dispatched

review
  reviewer inspects main...dev after the batch is complete
  leader merges dev into main only after required fixes are resolved
```

Scheduling is event-driven. When a worker exits or a task changes state, the
leader rechecks prerequisites and capacity and dispatches eligible work without
waiting for the rest of the batch.

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

### Updating an existing role

Changing `RoleSeeder` only affects a role that does not already exist in that
database. Before upgrading a customized role, the leader reads the current
generic and project-specific instructions with `get_role`/`list_roles`, compares
them with the proposed complete replacement, and preserves intentional local
differences. The leader may call `upsert_role` only after the user explicitly
authorizes that update in the current conversation. Updating the source seeder
does not update an online database; this documentation does not perform an
online role update.

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
