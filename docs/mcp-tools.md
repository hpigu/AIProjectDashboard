# MCP tools and read APIs

[Back to README](../README.md) · [繁體中文](../README.zh-TW.md)

MCP is the only write surface. The browser UI and every REST endpoint are
read-only.

## Planning and queries

The server exposes these tools, but each client decides which tools an agent may
call. The default workers receive only `get_role` and the lifecycle tools in the
next section; the leader handles planning and global queries.

| Tool | Purpose |
|---|---|
| `create_project(name, description?)` | Create a project. Names are trimmed and deduplicated case-insensitively. |
| `create_tasks(projectId, tasks[])` | Create 1–50 tasks. Titles are limited to 300 characters. Dependencies can use `dependsOnIndexes` or `dependsOnTaskIds`. |
| `list_tasks(projectId?/projectName?, status?, category?, includeDescription?)` | Read tasks and progress. Provide either project ID or exact case-insensitive name. |
| `list_roles(projectName?)` | List generic roles, with project overrides replacing roles of the same name. |
| `get_role(name, projectName?)` | Return the project override when present, otherwise the generic role. |

## Worker lifecycle

| Tool | Purpose |
|---|---|
| `claim_next_task(projectName, category, assignee)` | Atomically claim the first TODO task whose prerequisites are all DONE. |
| `block_task(taskId, claimToken?, reasonType, detail, blockingTaskIds?, expectedVersion?)` | Mark the worker's task BLOCKED with structured evidence. |
| `complete_task(taskId, claimToken?, summary, verificationResults, changedFiles?, commitRef?, expectedVersion?)` | Complete an IN_PROGRESS or BLOCKED task with verification evidence. |
| `update_task_status(taskId, status, note?, claimToken?)` | Compatibility status endpoint. Resume with IN_PROGRESS and release with TODO. Evidence-required tasks cannot use it to reach DONE. |

## Leader-only operations

Archive, restore, and role writes require explicit authorization from the user in
the current conversation. A generic request to finish or clean up is not enough.

| Tool | Purpose |
|---|---|
| `reset_task_claim(taskId, note?)` | Recover a task after confirming that its worker lost the claim token. |
| `preview_archive_project(projectName)` | Read the archive impact and current IN_PROGRESS assignees. |
| `archive_project(projectName, reason, inProgressConfirmed?)` | Archive after preview. Active work requires a second explicit confirmation. |
| `restore_project(projectName, reason)` | Restore an archived project. |
| `update_task_details(taskId, title?, description?, category?, expectedVersion)` | Patch the specification of a TODO or BLOCKED task. |
| `set_task_dependencies(taskId, prerequisiteTaskIds, expectedVersion)` | Replace the complete prerequisite set of a TODO task. |
| `upsert_role(name, category?, instructions, projectName?)` | Create or replace generic or project-specific role instructions. |

## Task states and categories

```text
TODO --claim_next_task--> IN_PROGRESS --complete_task--> DONE
                              |  ^
                              |  |
                         block_task
                              |  |
                              v  |
                           BLOCKED --complete_task--> DONE
```

Categories are `BACKEND`, `FRONTEND`, `TEST`, `INFRA`, `DOC`, and `OTHER`.
Missing or unknown categories normalize to `OTHER`.

- A TODO task must be claimed before it can become IN_PROGRESS or BLOCKED.
- Returning a task to TODO clears `assignee` and `claimed_at`.
- Updates use optimistic locking. On a version conflict, read the task again
  before retrying.
- Claims are ordered by `sort_order`, then task ID.

## Dependencies

`sort_order` applies only within one category. Cross-category prerequisites use:

- `dependsOnIndexes`: 1-based positions earlier in the same create batch;
- `dependsOnTaskIds`: IDs of existing tasks in the same project.

`claim_next_task` skips candidates with unfinished prerequisites so unrelated work
can proceed. If every candidate is waiting, it reports the blocking prerequisites
instead of saying that no tasks exist. Dependency filtering does not change the
atomic compare-and-swap claim operation.

## Completion and blockers

Tasks created through `create_tasks` set `require_evidence=true`. They must use
`complete_task` with a non-empty summary and at least one verification result.
Allowed results are `PASSED`, `FAILED`, and `NOT_RUN`; a `FAILED` result rejects
completion.

Older tasks with `require_evidence=false` retain the legacy direct-DONE path through
`update_task_status`.

`block_task` accepts these reason types:

- `DEPENDENCY`
- `USER_INPUT`
- `TECHNICAL`
- `ENVIRONMENT`
- `EXTERNAL`
- `OTHER`

`detail` is required. `DEPENDENCY` also requires at least one task ID from the
same project.

## Claim tokens

A successful claim returns a token. Tasks that store a token hash require the
same token for block, complete, and status operations. Older tasks without a hash
remain backward compatible.

Keep tokens only in worker and leader context. Never write them to files, commits,
task logs, or user-facing instructions.

## Client boundary

The MCP server has no caller identity. Worker allowlists are client-side
guardrails, not server authorization. Keep the server on localhost; do not expose
`/mcp` before adding a real authentication boundary.

The five default workers receive only:

- `get_role`
- `claim_next_task`
- `block_task`
- `complete_task`
- `update_task_status`

Instructions returned by `get_role` may narrow this list but cannot expand it.
Use MCP `tools/list` or the `tools` field from `/api/health` as the authoritative
runtime tool list.

## Read-only HTTP endpoints

| Endpoint | Purpose |
|---|---|
| `GET /api/projects` | Project summaries with name-prefix and status filters |
| `GET /api/projects/{id}/board` | Tasks grouped for the board view |
| `GET /api/projects/{id}/dependencies` | Dependency graph nodes and edges |
| `GET /api/projects/{id}/tasks/{taskId}` | Task detail |
| `GET /api/projects/{id}/tasks/{taskId}/history` | Status history, blocker data, and completion evidence |
| `GET /api/roles` | Role instructions; accepts `projectName` |
| `GET /api/events` | Server-sent task and project events |
| `GET /api/health` | Version, commit, registered tools, and start time |
| `GET /api/health/live` | Process liveness without a database check |
| `GET /api/health/ready` | Database, migration, and MCP registration readiness |
| `GET /api/diagnostics` | Migration, SSE, project/task, backup, and disk-capacity diagnostics |

## SSE limits

- `BOARD_SSE_MAX_CONNECTIONS`, default `32`: additional connections receive 503.
- `BOARD_SSE_CLIENT_QUEUE_CAPACITY`, default `128`: a slow client is disconnected
  when its queue fills, then the UI reconnects and reloads current state.

Event I/O runs outside MCP call threads. One slow browser tab cannot block an
agent tool call, and event order remains stable within each connection.
