/** Real SQLite engine compatibility tests; not a substitute for actual D1 tests. */
import { test } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { BoardService } from "../src/service.js";
class SqliteD1 {
  constructor() {
    this.sql = new DatabaseSync(":memory:");
    this.sql.exec(readFileSync(new URL("../migrations/0001_init.sql", import.meta.url), "utf8"));
    this.beforeBatch = null;
    this.calls = 0;
  }
  prepare(query) {
    const db = this;
    return { bind(...params) {
      return {
        async first() {
          db.calls++;
          return db.sql.prepare(query).get(...params) ?? null;
        },
        async all() {
          db.calls++;
          return { results: db.sql.prepare(query).all(...params) };
        },
        async run() {
          db.calls++;
          const r = db.sql.prepare(query).run(...params);
          return { meta: { changes: Number(r.changes), last_row_id: Number(r.lastInsertRowid) } };
        }
      };
    } };
  }
  async batch(statements) {
    if (this.beforeBatch) {
      const callback = this.beforeBatch;
      this.beforeBatch = null;
      await callback();
    }
    this.sql.exec("BEGIN");
    try {
      const result = [];
      for (const stmt of statements) result.push(await stmt.run());
      this.sql.exec("COMMIT");
      return result;
    } catch (e) {
      this.sql.exec("ROLLBACK");
      throw e;
    }
  }
}
const setup = () => {
  const db = new SqliteD1();
  return { db, s: new BoardService(db) };
};
const project = async (s) => (await s.execute("create_project", { name: "Example" })).project.id;
const tasks = async (s, p, inputs) => s.execute("create_tasks", { projectId: p, tasks: inputs });
const claim = (s, category = "BACKEND", assignee = "worker") => s.execute("claim_next_task", {
  projectName: "EXAMPLE",
  category,
  assignee
});
const complete = (s, c, extra = {}) => s.execute("complete_task", { taskId: c.task.id, claimToken: c.claimToken, summary: "Implemented", verificationResults: [{ name: "tests", result: "PASSED" }], ...extra });
const plain = (v) => JSON.parse(JSON.stringify(v));
test("projects deduplicate full names case-insensitively and seeded full roles persist", async () => {
  const { db, s } = setup();
  const p = await project(s);
  assert.equal((await s.execute("create_project", { name: " example ", description: "ignored" })).project.id, p);
  assert.equal((await s.listProjects()).length, 1);
  const roles = await s.listRoles();
  assert.equal(roles.length, 5);
  assert.ok(roles.every((r) => r.instructions.length > 500));
  await s.execute("upsert_role", { name: "qa", instructions: "custom", category: "TEST" });
  assert.equal((await s.execute("get_role", { name: "qa" })).role.instructions, "custom");
  const events = await s.eventsAfter(0);
  assert.equal(events.length, 1);
  assert.equal(events[0].type, "project.created");
  assert.equal(await s.latestEventId(), 1);
  db.sql.close();
});
test("batch validation is atomic, enforces earlier indexes/same project, 50 tasks and dependency skip", async () => {
  const { db, s } = setup();
  const p = await project(s);
  for (const inputs of [
    [{ title: "a", dependsOnIndexes: [1] }],
    [{ title: "a", dependsOnIndexes: [2] }, { title: "b" }],
    [{ title: "a" }, { title: " " }]
  ])
    await assert.rejects(tasks(s, p, inputs));
  assert.equal((await s.getBoard(p)).columns.TODO.length, 0);
  const other = (await s.execute("create_project", { name: "other" })).project.id;
  const [foreign] = await tasks(s, other, [{ title: "foreign" }]);
  await assert.rejects(tasks(s, p, [{ title: "a", dependsOnTaskIds: [foreign.id] }]));
  const made = await tasks(s, p, [{ title: "infra", category: "INFRA" }, { title: "blocked", category: "BACKEND", dependsOnIndexes: [
    1
  ] }, { title: "independent", category: "BACKEND" }]);
  const c = await claim(s);
  assert.equal(c.task.id, made[2].id);
  const waiting = await claim(s);
  assert.equal(waiting.outcome, "BLOCKED_BY_DEPENDENCY");
  assert.equal(waiting.blockedCandidates[0].waitingFor[0].id, made[0].id);
  await tasks(s, p, Array.from({ length: 50 }, (_, i) => ({ title: "bulk " + i, dependsOnTaskIds: [made[0].id] })));
  assert.equal((await s.getBoard(p)).columns.TODO.length, 52);
  db.sql.close();
});
test("claim revision conflict rolls back every side effect then retries, one token hash only", async () => {
  const { db, s } = setup();
  const p = await project(s);
  await tasks(s, p, [{ title: "one", category: "BACKEND" }]);
  let winner;
  db.beforeBatch = async () => {
    winner = await claim(s, "BACKEND", "first");
  };
  const loser = await claim(s, "BACKEND", "second");
  assert.equal(winner.outcome, "CLAIMED");
  assert.equal(loser.outcome, "CONTENDED");
  const stored = plain(db.sql.prepare("SELECT * FROM task").get());
  assert.notEqual(stored.claim_token_hash, winner.claimToken);
  assert.equal(stored.claim_token_hash.length, 64);
  assert.equal(db.sql.prepare("SELECT count(*) AS n FROM task_log").get().n, 2);
  assert.equal((await s.eventsAfter(0)).filter((e) => e.type === "task.status_changed").length, 1);
  const dump = JSON.stringify(db.sql.prepare("SELECT * FROM task_log").all()) + JSON.stringify(await s.eventsAfter(0));
  assert.ok(!dump.includes(winner.claimToken));
  db.sql.close();
});
test("tokens, blockers, evidence, history, reopen and reset preserve lifecycle invariants", async () => {
  const { db, s } = setup();
  const p = await project(s);
  const [t, dependency] = await tasks(s, p, [{ title: "one", category: "BACKEND" }, { title: "other" }]);
  await assert.rejects(s.execute("update_task_status", { taskId: t.id, status: "IN_PROGRESS" }));
  const c = await claim(s);
  await assert.rejects(s.execute("block_task", { taskId: t.id, reasonType: "TECHNICAL", detail: "broken" }));
  await assert.rejects(s.execute("update_task_status", { taskId: t.id, status: "DONE", claimToken: c.claimToken }));
  await s.execute("block_task", { taskId: t.id, claimToken: c.claimToken, reasonType: "DEPENDENCY", detail: "waiting", blockingTaskIds: [
    dependency.id
  ], expectedVersion: 1 });
  let detail = await s.getTaskDetail(p, t.id);
  assert.equal(detail.currentBlocker.blockingTasks[0].id, dependency.id);
  await assert.rejects(complete(s, c, { verificationResults: [{ name: "test", result: "FAILED", detail: "fails" }] }));
  await assert.rejects(complete(s, c, { verificationResults: [{ name: "test", result: "NOT_RUN" }] }));
  await assert.rejects(complete(s, c, { expectedVersion: 1 }));
  await complete(s, c, { expectedVersion: 2, changedFiles: "src/a", commitRef: "abc" });
  detail = await s.getTaskDetail(p, t.id);
  assert.equal(detail.status, "DONE");
  assert.equal(detail.currentBlocker, null);
  assert.equal(detail.completionEvidence.verifications[0].result, "PASSED");
  assert.ok(db.sql.prepare("SELECT cleared_at FROM task_block_event").get().cleared_at);
  assert.equal(db.sql.prepare("SELECT claim_token_hash FROM task WHERE id=?").get(t.id).claim_token_hash, null);
  await s.execute("update_task_status", { taskId: t.id, status: "IN_PROGRESS" });
  await s.execute("reset_task_claim", { taskId: t.id, note: "lost" });
  detail = await s.getTaskDetail(p, t.id);
  assert.equal(detail.status, "TODO");
  assert.equal(detail.assignee, null);
  assert.equal(detail.claimedAt, null);
  assert.equal((await s.getHistory(p, t.id)).length, 6);
  db.sql.close();
});
test("spec edits and dependency replacement version fence, cross-project and cycles", async () => {
  const { db, s } = setup();
  const p = await project(s);
  const [a, b] = await tasks(s, p, [{ title: "A" }, { title: "B" }]);
  const edit = await s.execute("update_task_details", { taskId: a.id, title: " A revised ", expectedVersion: 0 });
  assert.equal(edit.version, 1);
  await assert.rejects(s.execute("update_task_details", { taskId: a.id, title: "stale", expectedVersion: 0 }));
  await s.execute("set_task_dependencies", { taskId: a.id, prerequisiteTaskIds: [b.id], expectedVersion: 1 });
  await assert.rejects(s.execute("set_task_dependencies", { taskId: b.id, prerequisiteTaskIds: [a.id], expectedVersion: 0 }));
  assert.equal((await s.getDependencies(p)).edges.length, 1);
  await s.execute("set_task_dependencies", { taskId: a.id, prerequisiteTaskIds: [], expectedVersion: 2 });
  assert.equal((await s.getDependencies(p)).edges.length, 0);
  db.sql.close();
});
test("cross-isolate dependency editing conflict cannot commit a new cycle", async () => {
  const { db, s } = setup();
  const p = await project(s);
  const [a, b] = await tasks(s, p, [{ title: "A" }, { title: "B" }]);
  db.beforeBatch = () => s.execute("set_task_dependencies", { taskId: b.id, prerequisiteTaskIds: [a.id], expectedVersion: 0 });
  await assert.rejects(
    s.execute("set_task_dependencies", { taskId: a.id, prerequisiteTaskIds: [b.id], expectedVersion: 0 }),
    (e) => e.code === "CONFLICT"
  );
  assert.equal((await s.getDependencies(p)).edges.length, 1);
  assert.equal((await s.getHistory(p, a.id)).length, 1);
  db.sql.close();
});
test("archive confirmation, audit counts, all scoped writes denied and restore retains data", async () => {
  const { db, s } = setup();
  const p = await project(s);
  const [t] = await tasks(s, p, [{ title: "one", category: "BACKEND" }]);
  const c = await claim(s);
  await assert.rejects(s.execute("archive_project", { projectName: "Example", reason: "request" }));
  assert.equal((await s.execute("preview_archive_project", { projectName: "Example" })).counts.inProgress, 1);
  await s.execute("archive_project", { projectName: "Example", reason: "request", inProgressConfirmed: true });
  for (const [action, args] of [["create_tasks", { projectId: p, tasks: [{ title: "no" }] }], ["complete_task", { taskId: t.id, claimToken: c.claimToken, summary: "done", verificationResults: [{ name: "tests", result: "PASSED" }] }], ["reset_task_claim", { taskId: t.id }], ["update_task_status", { taskId: t.id, status: "BLOCKED", claimToken: c.claimToken }], [
    "update_task_details",
    { taskId: t.id, title: "no", expectedVersion: 1 }
  ], ["set_task_dependencies", {
    taskId: t.id,
    expectedVersion: 1,
    prerequisiteTaskIds: []
  }], ["upsert_role", { name: "qa", instructions: "no", projectName: "Example" }], ["claim_next_task", { projectName: "Example", category: "BACKEND", assignee: "no" }]]) await assert.rejects(s.execute(action, args));
  assert.equal((await s.listProjects()).length, 0);
  assert.equal((await s.listProjects(new URLSearchParams("includeArchived=true"))).length, 1);
  assert.equal((await s.getBoard(p)).columns.IN_PROGRESS.length, 1);
  await s.execute("restore_project", { projectName: "Example", reason: "request" });
  await complete(s, c);
  assert.equal(db.sql.prepare("SELECT count(*) AS n FROM project_audit").get().n, 2);
  db.sql.close();
});
test("archive racing a task mutation aborts rather than using stale confirmation counts", async () => {
  const { db, s } = setup();
  const p = await project(s);
  await tasks(s, p, [{ title: "one", category: "BACKEND" }]);
  db.beforeBatch = () => claim(s);
  await assert.rejects(s.execute("archive_project", { projectName: "Example", reason: "request" }), (e) => e.code === "CONFLICT");
  assert.equal((await s.listProjects()).length, 1);
  assert.equal(db.sql.prepare("SELECT count(*) AS n FROM project_audit").get().n, 0);
  db.sql.close();
});
test("role override priority and frontend DTO contract with event ordering", async () => {
  const { db, s } = setup();
  const p = await project(s);
  await s.execute("upsert_role", { name: "qa", category: "TEST", instructions: "project override", projectName: "Example" });
  assert.equal((await s.execute("get_role", { name: "qa", projectName: "example" })).role.instructions, "project override");
  assert.equal((await s.execute("get_role", { name: "qa" })).role.projectId, null);
  assert.equal((await s.listRoles("EXAMPLE")).find((r) => r.name === "qa").isOverride, true);
  assert.deepEqual(await s.listRoles("missing"), []);
  const [t] = await tasks(s, p, [{ title: "Frontend", category: "FRONTEND" }]);
  assert.equal((await s.getTaskDetail(p, t.id)).version, 0);
  assert.deepEqual(Object.keys((await s.getBoard(p)).columns), ["TODO", "IN_PROGRESS", "BLOCKED", "DONE"]);
  assert.equal((await s.getDependencies(p)).nodes[0].claimable, true);
  const events = await s.eventsAfter(0);
  assert.deepEqual(events.map((e) => e.type), ["project.created", "tasks.created"]);
  assert.equal((await s.eventsAfter(events[0].id))[0].id, events[1].id);
  db.sql.close();
});
test("SQL failure late in batch rolls back state, logs and outbox", async () => {
  const { db, s } = setup();
  const p = await project(s);
  await tasks(s, p, [{ title: "one", category: "BACKEND" }]);
  db.sql.exec("CREATE TRIGGER reject_events BEFORE INSERT ON board_event BEGIN SELECT RAISE(ABORT,'test failure'); END;");
  await assert.rejects(claim(s));
  assert.equal(db.sql.prepare("SELECT status FROM task").get().status, "TODO");
  assert.equal(db.sql.prepare("SELECT count(*) AS n FROM task_log").get().n, 1);
  assert.equal(db.sql.prepare("SELECT count(*) AS n FROM mutation_guard").get().n, 0);
  db.sql.close();
});
test("creation normalizes absent, blank and unknown category to OTHER", async () => {
  const { db, s } = setup();
  const p = await project(s);
  const created = await tasks(s, p, [
    { title: "missing" },
    { title: "blank", category: "   " },
    { title: "empty", category: "" },
    { title: "unknown", category: "CUSTOM" },
    { title: "normalized", category: " backend " }
  ]);
  assert.deepEqual(created.map((t) => t.category), ["OTHER", "OTHER", "OTHER", "OTHER", "BACKEND"]);
  db.sql.close();
});
