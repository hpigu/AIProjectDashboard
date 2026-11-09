import { env, attest, authorities, signed, gitPolicy, CODE_REF } from './acceptance-fixture.js';
/** Real SQLite engine compatibility tests; not a substitute for actual D1 tests. */
import { test } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import { BoardService } from "../src/service.js";
class SqliteD1 {
  constructor() {
    this.sql = new DatabaseSync(":memory:");
    for(const file of readdirSync(new URL("../migrations/",import.meta.url)).filter(n=>n.endsWith(".sql")).sort()) this.sql.exec(readFileSync(new URL("../migrations/"+file,import.meta.url),"utf8"));
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
  const s=new BoardService(db,env);
  const execute=s.execute.bind(s);s.execute=(a,b,c={managementActor:"fixture-management"})=>execute(a,b,c);
  return { db, s };
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
  await attest((name,args) => s.execute(name,args), c.task, "c".repeat(40));
  await complete(s, c, { expectedVersion: (await s.task(t.id)).version, changedFiles: "src/a", commitRef: "c".repeat(40) });
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
  await attest((name,args) => s.execute(name,args), c.task);
  await complete(s, c, { commitRef: CODE_REF });
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

test('completed task rejects expired tokens and can reopen without one', async () => {
  const { db, s } = setup();
  const p = await project(s);
  await tasks(s, p, [{ title: 'done', category: 'BACKEND' }]);
  const c = await claim(s);
  const args = { taskId: c.task.id, summary: 'done', verificationResults: [{ name: 'test', result: 'PASSED' }] };
  await attest((name,args) => s.execute(name,args), c.task);
  await s.execute('complete_task', { ...args, claimToken: c.claimToken, commitRef: CODE_REF });
  await assert.rejects(s.execute('complete_task', { ...args, claimToken: c.claimToken }), e => e.status === 403);
  await assert.rejects(s.execute('update_task_status', { taskId: c.task.id, status: 'DONE', claimToken: c.claimToken }), e => e.status === 400);
  assert.equal((await s.execute('update_task_status', { taskId: c.task.id, status: 'IN_PROGRESS' })).changed, true);
  db.sql.close();
});

test('signed acceptance rejects declarations, forged signatures, wrong version and non-independent review', async () => {
  const {s,db} = setup(); const p = await project(s);
  await tasks(s,p,[{title:'Acceptance fixture',category:'BACKEND'}]); const c = await claim(s);
  assert.equal((await s.execute('get_acceptance',{taskId:c.task.id})).canClose,false);
  await assert.rejects(complete(s,c), /不能結案/);
  await assert.rejects(s.execute('record_acceptance_event',{actor:'fixture-owner',payload:'{}',signature:'0'.repeat(64)}),/簽章/);
  const {summary,send,contractId} = await attest((n,a)=>s.execute(n,a),c.task);
  assert.equal(summary.canClose,true);
  await assert.rejects(complete(s,c,{commitRef:'wrong-code'}),/版本/);
  await assert.rejects(send('fixture-runner','RUN',{contractId,codeRef:'wrong',caseId:'C1',runnerProfile:'isolated-browser'}),/版本/);
  await assert.rejects(send('fixture-runner','REVIEW',{contractId,codeRef:CODE_REF,subjectDigest:summary.subjectDigest,decision:'APPROVED',reason:'self'}),/權限/);
  assert.equal((await s.execute('get_acceptance',{taskId:c.task.id})).events.find(e=>e.kind==='RUN')._verifiedActor,'fixture-runner');
  await complete(s,c,{commitRef:CODE_REF});
  assert.equal((await s.task(c.task.id)).status,'DONE');
  await assert.rejects(s.execute('report_implementation',{taskId:c.task.id,summary:'late report',verificationResults:[{name:'self',result:'PASSED'}]}),/只有已認領/); db.sql.close();
});

test('requirement edits and new failed runs invalidate accepted evidence and require new independent approval',async()=>{
  const {s,db}=setup(); const p=await project(s); await tasks(s,p,[{title:'flow',category:'BACKEND'}]); const c=await claim(s);
  const {send,contractId}=await attest((n,a)=>s.execute(n,a),c.task);
  await send('fixture-runner','RUN',{contractId,codeRef:CODE_REF,caseId:'C1',runId:'failed-run',runnerProfile:'isolated-browser',result:'FAILED',artifactUri:'fixture://failed',artifactSha256:'b'.repeat(64),steps:'reload',actual:'data missing',environment:'ISOLATED'});
  let summary=await s.execute('get_acceptance',{taskId:c.task.id}); assert.equal(summary.canClose,false);
  assert.ok(summary.blockers.some(b=>b.includes('失敗或未驗證')));
  await assert.rejects(complete(s,c,{commitRef:CODE_REF}),/不能結案/);
  await s.execute('block_task',{taskId:c.task.id,claimToken:c.claimToken,reasonType:'USER_INPUT',detail:'clarify'});
  await s.execute('update_task_details',{taskId:c.task.id,description:'changed requirement',expectedVersion:(await s.task(c.task.id)).version});
  summary=await s.execute('get_acceptance',{taskId:c.task.id}); assert.ok(summary.blockers.some(b=>b.includes('需求已變更'))); db.sql.close();
});

test('unresolved requirements, missing cases, missing runs and unconfigured Git flow block completion', async()=>{
 const {s,db}=setup(), p=await project(s); await tasks(s,p,[{title:'pending',category:'BACKEND'}]); const c=await claim(s);
 let state=await s.execute('get_acceptance',{taskId:c.task.id}), seq=0;
 const emit=async(kind,body)=>{
   const args=signed('fixture-owner',{eventId:`pending-${++seq}`,taskId:c.task.id,projectId:p,expectedRevision:state.revision,kind,body});
   state=await s.execute('record_acceptance_event',args);
 };
 const contract={sourceRef:'requirements.md',sourceVersion:'v1',codeRef:'b'.repeat(40),taskDigest:state.taskDigest,flowRef:'flows.md',flowVersion:'v1',implementationActor:'worker',questions:['Who may approve?'],assumptions:[],requirements:[{id:'R1',state:'CONFIRMED',acceptance:'Save data'},{id:'R2',state:'PENDING',acceptance:'Business decision pending'}],cases:[{id:'C1',kind:'BROWSER',scenario:'Save and refresh',requirementIds:['R1']}],gitPolicy};
 await assert.rejects(emit('CONTRACT',contract),/Git/);
 await emit('PROJECT_POLICY',{gitPolicy}); await emit('CONTRACT',contract);
 assert.equal(state.canClose,false);
 for(const expected of ['人類決定','需求未確認','需求漏驗','失敗或未驗證']) assert.ok(state.blockers.some(b=>b.includes(expected)));
 await assert.rejects(complete(s,c,{commitRef:'b'.repeat(40)}),/不能結案/); db.sql.close();
});

test('exceptions require authorized approval and are invalidated by a later failed run; policy changes invalidate contracts',async()=>{
 const {s,db}=setup(),p=await project(s); await tasks(s,p,[{title:'exceptions',category:'BACKEND'}]); const c=await claim(s);
 const {send,contractId}=await attest((n,a)=>s.execute(n,a),c.task);
 const failed={contractId,codeRef:CODE_REF,caseId:'C1',runId:'f1',runnerProfile:'isolated-browser',result:'FAILED',artifactUri:'fixture://failure',artifactSha256:'c'.repeat(64),steps:'reload',actual:'failed',environment:'ISOLATED'};
 await send('fixture-runner','RUN',failed);
 await assert.rejects(send('fixture-runner','EXCEPTION',{contractId,codeRef:CODE_REF,caseId:'C1',reason:'skip',approvalRef:'self'}),/權限/);
 await send('fixture-owner','EXCEPTION',{contractId,codeRef:CODE_REF,caseId:'C1',reason:'Approved fixture exception',approvalRef:'owner-decision-v1'});
 let state=await s.execute('get_acceptance',{taskId:c.task.id});
 assert.equal(state.blockers.some(b=>b.includes('失敗或未驗證')),false);
 await send('fixture-runner','RUN',{...failed,runId:'f2'});
 state=await s.execute('get_acceptance',{taskId:c.task.id}); assert.ok(state.blockers.some(b=>b.includes('失敗或未驗證')));
 await send('fixture-owner','PROJECT_POLICY',{gitPolicy:{...gitPolicy,integrationTarget:'new-target'}});
 state=await s.execute('get_acceptance',{taskId:c.task.id}); assert.ok(state.blockers.some(b=>b.includes('Git 流程已變更'))); db.sql.close();
});

test('runner and reviewer identities remain independent even when they have extra permissions',async()=>{
 const {s,db}=setup(),p=await project(s); await tasks(s,p,[{title:'independent',category:'BACKEND'}]); const c=await claim(s);
 const {contractId,summary}=await attest((n,a)=>s.execute(n,a),c.task);
 const expanded=structuredClone(authorities); expanded['fixture-runner'].permissions.push('REVIEW'); expanded['worker']={...expanded['fixture-runner'],secret:'worker-secret'.repeat(4)};
 s.env={BOARD_ACCEPTANCE_AUTHORITIES:JSON.stringify(expanded)};
 const event={eventId:'self-review',taskId:c.task.id,projectId:p,expectedRevision:summary.revision,kind:'REVIEW',body:{contractId,codeRef:CODE_REF,subjectDigest:summary.subjectDigest,decision:'APPROVED',reason:'self'}};
 await assert.rejects(s.execute('record_acceptance_event',signed('fixture-runner',event)),/執行器金鑰/);
 s.env = env;
 const before=(await s.execute('get_acceptance',{taskId:c.task.id})).revision;
 db.beforeBatch=()=>db.sql.exec('UPDATE board_revision SET revision=revision+1 WHERE id=1');
 await assert.rejects(s.execute('record_acceptance_event',signed('fixture-reviewer',{...event,eventId:'race-review'})),e=>e.code==='CONFLICT');
 assert.equal((await s.execute('get_acceptance',{taskId:c.task.id})).revision,before); db.sql.close();
});

test('a contract with unresolved business decisions blocks only that candidate and never masquerades as no TODO',async()=>{
 const {s,db}=setup(),p=await project(s); const created=await tasks(s,p,[{title:'blocked by business',category:'BACKEND'},{title:'independent',category:'BACKEND'}]);
 let state=await s.execute('get_acceptance',{taskId:created[0].id}), seq=0;
 const send=async(kind,body)=>{state=await s.execute('record_acceptance_event',signed('fixture-owner',{eventId:`business-${++seq}`,taskId:created[0].id,projectId:p,expectedRevision:state.revision,kind,body}));};
 await send('PROJECT_POLICY',{gitPolicy});
 await send('CONTRACT',{browserApplicable:false,browserExemptionReason:'Backend fixture',sourceRef:'spec',sourceVersion:'v1',codeRef:'b'.repeat(40),taskDigest:state.taskDigest,flowRef:'flow',flowVersion:'v1',implementationActor:'worker',questions:['Business rule undecided'],assumptions:[],requirements:[{id:'R',state:'PENDING',acceptance:'await owner'}],cases:[{id:'C',kind:'API',scenario:'NORMAL',requirementIds:['R']}],gitPolicy});
 const winner=await claim(s); assert.equal(winner.task.id,created[1].id);
 const blocked=await claim(s); assert.equal(blocked.outcome,'BLOCKED_BY_REQUIREMENT'); assert.ok(blocked.blockedCandidates[0].requirementIssues.length); db.sql.close();
});

test('UI contracts enumerate applicable browser scenarios or explicit owner exemptions',async()=>{
 const {s,db}=setup(),p=await project(s); await tasks(s,p,[{title:'UI scope',category:'FRONTEND'}]); const c=await claim(s,'FRONTEND');
 const fixture=await attest((n,a)=>s.execute(n,a),c.task);
 const body={...fixture.summary.events.find(e=>e.kind==='CONTRACT').body,browserApplicable:true,cases:[{id:'C1',kind:'BROWSER',scenario:'NORMAL',requirementIds:['R1']}]};
 await fixture.send('fixture-owner','CONTRACT',body);
 let summary=await s.execute('get_acceptance',{taskId:c.task.id});
 for(const scenario of ['ERROR','BOUNDARY','PERMISSION','FAILURE','PERSISTENCE','DOWNSTREAM']) assert.ok(summary.blockers.some(b=>b.includes(scenario)));
 body.scenarioExemptions=Object.fromEntries(['ERROR','BOUNDARY','PERMISSION','FAILURE','PERSISTENCE','DOWNSTREAM'].map(k=>[k,'Owner explicitly excludes this scenario in a limited fixture']));
 await fixture.send('fixture-owner','CONTRACT',body);
 summary=await s.execute('get_acceptance',{taskId:c.task.id}); assert.equal(summary.blockers.some(b=>b.includes('缺少適用情境')),false);
 assert.equal(summary.canClose,false); db.sql.close();
});

test('handoff to a different implementation actor invalidates prior acceptance even with unchanged requirements',async()=>{
 const {s,db}=setup(),p=await project(s); await tasks(s,p,[{title:'handoff',category:'BACKEND'}]); const c=await claim(s);
 await attest((n,a)=>s.execute(n,a),c.task);
 assert.equal((await s.execute('get_acceptance',{taskId:c.task.id})).canClose,true);
 await s.execute('reset_task_claim',{taskId:c.task.id,note:'explicit handoff'});
 const replacement=await claim(s,'BACKEND','replacement-author');
 const summary=await s.execute('get_acceptance',{taskId:c.task.id});
 assert.equal(summary.canClose,false); assert.ok(summary.blockers.some(b=>b.includes('認領者與契約原作者不符')));
 await assert.rejects(complete(s,replacement,{commitRef:CODE_REF}),/不能結案/); db.sql.close();
});

test('mutable code refs and shared signing secrets are rejected; task field delimiters cannot collide',async()=>{
 const {s,db}=setup(),p=await project(s); await tasks(s,p,[{title:'a\nb',description:'c',category:'BACKEND'}]); const c=await claim(s);
 const fixture=await attest((n,a)=>s.execute(n,a),c.task);
 const body={...fixture.summary.events.find(e=>e.kind==='CONTRACT').body,codeRef:'main'};
 await assert.rejects(fixture.send('fixture-owner','CONTRACT',body),/完整 Git SHA/);
 const shared=structuredClone(authorities); shared['fixture-reviewer'].secret=shared['fixture-runner'].secret;
 s.env={BOARD_ACCEPTANCE_AUTHORITIES:JSON.stringify(shared)};
 await assert.rejects(s.execute('record_acceptance_event',signed('fixture-owner',{eventId:'shared-key',taskId:c.task.id,projectId:p,expectedRevision:fixture.summary.revision,kind:'PROJECT_POLICY',body:{gitPolicy}})),/不可共用/);
 s.env=env;
 const before=(await s.execute('get_acceptance',{taskId:c.task.id})).taskDigest;
 await s.execute('block_task',{taskId:c.task.id,claimToken:c.claimToken,reasonType:'USER_INPUT',detail:'spec changed'});
 await s.execute('update_task_details',{taskId:c.task.id,title:'a',description:'b\nc',expectedVersion:(await s.task(c.task.id)).version});
 const after=await s.execute('get_acceptance',{taskId:c.task.id});
 assert.notEqual(after.taskDigest,before); assert.ok(after.blockers.some(b=>b.includes('需求已變更'))); db.sql.close();
});

test('implementation self reports append failures without completing or unlocking dependencies',async()=>{
 const {s,db}=setup(),p=await project(s);
 const created=await tasks(s,p,[{title:'implementation',category:'BACKEND'},{title:'dependent',category:'FRONTEND',dependsOnIndexes:[1]}]); const c=await claim(s);
 const args={taskId:c.task.id,claimToken:c.claimToken,summary:'Implemented but self test fails',verificationResults:[{name:'self-test',result:'FAILED',detail:'Observed data loss'}]};
 await assert.rejects(s.execute('report_implementation',{...args,claimToken:'wrong'}));
 await assert.rejects(s.execute('report_implementation',{...args,verificationResults:[{name:'self-test',result:'NOT_RUN'}]}));
 const beforeVersion=(await s.task(c.task.id)).version;
 const report=await s.execute('report_implementation',args);
 assert.equal((await s.task(c.task.id)).version,beforeVersion+1);
 assert.equal(report.changed,false); assert.equal(report.task.status,'IN_PROGRESS'); assert.equal(report.evidence.verifications[0].result,'FAILED');
 assert.equal((await s.execute('get_acceptance',{taskId:c.task.id})).canClose,false);
 assert.equal((await claim(s,'FRONTEND')).outcome,'BLOCKED_BY_DEPENDENCY');
 assert.ok((await s.task(c.task.id)).claim_token_hash);
 await s.execute('report_implementation',{...args,verificationResults:[{name:'manual check',result:'NOT_RUN',detail:'Test environment unavailable'}]});
 assert.equal(db.sql.prepare('SELECT count(*) AS n FROM task_completion_evidence WHERE task_id=?').get(c.task.id).n,2);
 assert.equal((await s.task(created[1].id)).status,'TODO'); db.sql.close();
});

test('stale acceptance on DONE prerequisites blocks dependents while independent tasks remain claimable',async()=>{
 const {s,db}=setup(),p=await project(s);
 const made=await tasks(s,p,[{title:'prerequisite',category:'INFRA'},{title:'dependent',category:'BACKEND',dependsOnIndexes:[1]}]);
 const c=await claim(s,'INFRA'); const fixture=await attest((n,a)=>s.execute(n,a),c.task);
 await complete(s,c,{commitRef:CODE_REF});
 assert.equal((await s.waiting(p)).has(made[1].id),false);
 await fixture.send('fixture-runner','RUN',{contractId:fixture.contractId,codeRef:CODE_REF,caseId:'C1',runId:'regression',runnerProfile:'isolated-browser',result:'FAILED',artifactUri:'fixture://regression',artifactSha256:'f'.repeat(64),steps:'recheck',actual:'regression found',environment:'ISOLATED'});
 assert.equal((await s.task(c.task.id)).status,'DONE');
 let blocked=await claim(s); assert.equal(blocked.outcome,'BLOCKED_BY_DEPENDENCY');
 assert.equal(blocked.blockedCandidates[0].waitingFor[0].acceptanceUnverified,true);
 assert.ok(blocked.blockedCandidates[0].waitingFor[0].reason.includes('失敗或未驗證'));
 const [independent]=await tasks(s,p,[{title:'independent',category:'BACKEND'}]);
 assert.equal((await claim(s)).task.id,independent.id);
 db.sql.close();
});

test('historical DONE declarations require explicit signed upgrade before unlocking; implementation authors cannot exempt themselves',async()=>{
 const {s,db}=setup(),p=await project(s);
 const made=await tasks(s,p,[{title:'legacy done',category:'INFRA'},{title:'dependent',category:'BACKEND',dependsOnIndexes:[1]}]);
 // Deliberately seed historical data; no legacy claim is silently trusted.
 db.sql.prepare("UPDATE task SET status='DONE',assignee='historical-worker' WHERE id=?").run(made[0].id);
 const blocked=await claim(s); assert.equal(blocked.blockedCandidates[0].waitingFor[0].acceptanceUnverified,true);
 await attest((n,a)=>s.execute(n,a),{...made[0],assignee:'historical-worker'});
 assert.equal((await claim(s)).task.id,made[1].id);
 await tasks(s,p,[{title:'self-exception',category:'FRONTEND'}]); const c=await claim(s,'FRONTEND','fixture-owner');
 const fixture=await attest((n,a)=>s.execute(n,a),c.task);
 await assert.rejects(fixture.send('fixture-owner','EXCEPTION',{contractId:fixture.contractId,codeRef:CODE_REF,caseId:'C1',reason:'self waiver',approvalRef:'self'}),/不能自行核准/);
 db.sql.close();
});

test('project workflow supports empty projects, partial planning, explicit signed confirmation and revisioned reuse', async()=>{
 const {s}=setup(); const p=await project(s);
 let state=await s.execute('get_project_workflow',{projectName:'example'});
 assert.equal(state.status,'UNCONFIGURED');assert.equal(state.revision,0);assert.equal(state.active,null);
 state=await s.execute('prepare_project_workflow',{projectName:'EXAMPLE',expectedRevision:0,gitPolicy:{baseBranch:'feature/base'},sources:[{field:'baseBranch',kind:'PROJECT_RULE',ref:'AGENTS.md',version:'v1',detail:'documented rule'}],questions:[{field:'integrationTarget',question:'整合到哪裡？'}]});
 assert.equal(state.draft.version,1);assert.equal(state.draft.missingFields.length,6);assert.equal(state.draft.readyForConfirmation,false);
 const approve=state=>signed('fixture-owner',{...state.approvalPayload,approvalRef:'conversation://explicit-user-confirmation'});
 assert.equal(state.approvalPayload,null);
 await assert.rejects(s.execute('confirm_project_workflow',signed('fixture-owner',{kind:'CONFIRM_PROJECT_WORKFLOW',projectId:p,draftId:state.draft.version,expectedRevision:state.revision,previewDigest:state.draft.previewDigest,approved:true,approvalRef:'ref'})),/缺項/);
 state=await s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:1,gitPolicy:{...gitPolicy,baseBranch:'feature/base'},questions:[]});
 assert.ok(state.draft.sources.some(e=>e.field==='baseBranch'&&e.kind==='PROJECT_RULE'));assert.equal(state.draft.readyForConfirmation,true);assert.equal(state.active,null);
 await assert.rejects(s.execute('confirm_project_workflow',signed('fixture-owner',{...state.approvalPayload,previewDigest:'0'.repeat(64),approvalRef:'ref'})),/版本/);
 await assert.rejects(s.execute('confirm_project_workflow',{actor:'fixture-owner',payload:approve(state).payload,signature:'0'.repeat(64)}),/簽章/);
 const original=state;
 state=await s.execute('confirm_project_workflow',approve(state));
 assert.equal(state.active.version,3);assert.equal(state.revision,3);assert.equal(state.status,'CONFIRMED');assert.equal(state.draft,null);assert.equal(state.active.confirmedBy,'fixture-owner');
 await assert.rejects(s.execute('confirm_project_workflow',approve(original)),/版本/);
 state=await s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:3,gitPolicy:{reviewPolicy:'new review'}});
 assert.equal(state.active.version,3);assert.equal(state.draft.baseVersion,3);assert.equal(state.draft.gitPolicy.baseBranch,'feature/base');assert.deepEqual(state.draft.changes,[{field:'reviewPolicy',before:gitPolicy.reviewPolicy,after:'new review'}]);
 state=await s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:4,gitPolicy:{baseBranch:''},sources:[],questions:[]});
 assert.equal(state.draft.missingFields.includes('baseBranch'),true);assert.ok(state.draft.sources.every(e=>e.kind==='SUGGESTION'));assert.equal(state.active.version,3);
 await s.execute('create_project',{name:'Other'});
 assert.equal((await s.execute('get_project_workflow',{projectName:'Other'})).status,'UNCONFIGURED');
 assert.equal((await s.getProjectWorkflow(p)).revision,5);
});

test('workflow confirmation requires scoped owner authority and board revision fencing; archived projects cannot change',async()=>{
 const {s,db}=setup();await project(s);
 const draft=await s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:0,gitPolicy});
 const payload={...draft.approvalPayload,approvalRef:'human-confirmation'};
 await assert.rejects(s.execute('confirm_project_workflow',signed('fixture-runner',payload)),/權限/);
 const scoped=structuredClone(authorities);scoped['fixture-owner'].projectIds=['99'];
 await assert.rejects(new BoardService(db,{BOARD_ACCEPTANCE_AUTHORITIES:JSON.stringify(scoped)}).execute('confirm_project_workflow',signed('fixture-owner',payload)),/權限/);
 const mixed=structuredClone(authorities);mixed['fixture-owner'].permissions.push('RUN');
 await assert.rejects(new BoardService(db,{BOARD_ACCEPTANCE_AUTHORITIES:JSON.stringify(mixed)}).execute('confirm_project_workflow',signed('fixture-owner',payload)),/不得兼任/);
 db.beforeBatch=()=>db.sql.exec('UPDATE board_revision SET revision=revision+1 WHERE id=1');
 await assert.rejects(s.execute('confirm_project_workflow',signed('fixture-owner',payload)),/更新/);
 assert.equal((await s.execute('get_project_workflow',{projectName:'Example'})).active,null);
 await s.execute('archive_project',{projectName:'Example',reason:'fixture',inProgressConfirmed:true});
 await assert.rejects(s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:1,gitPolicy}),/封存/);
 await assert.rejects(s.execute('confirm_project_workflow',signed('fixture-owner',payload)),/封存/);
});

test('new workflow revisions block legacy policy bypass and invalidate acceptance even when effective content returns to original',async()=>{
 const {s}=setup();const p=await project(s);
 await tasks(s,p,[{title:'Workflow fixture',description:'fixture',category:'BACKEND',dependsOnIndexes:[],dependsOnTaskIds:[]}]);
 const claimed=await claim(s);const attested=await attest((a,b)=>s.execute(a,b),claimed.task);
 assert.equal(attested.summary.canClose,true);
 let state=await s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:0});
 assert.equal(state.status,'DRAFT');assert.deepEqual(state.draft.gitPolicy,gitPolicy);assert.equal(state.active,null);
 await assert.rejects(attested.send('fixture-owner','PROJECT_POLICY',{gitPolicy}),/版本化流程/);
 state=await s.execute('confirm_project_workflow',signed('fixture-owner',{...state.approvalPayload,approvalRef:'owner-confirmation'}));
 const invalid=await s.execute('get_acceptance',{taskId:claimed.task.id});
 assert.equal(invalid.canClose,false);assert.ok(invalid.blockers.some(b=>b.includes('流程版本')));
 const contract=invalid.events.find(e=>e.kind==='CONTRACT').body;
 await assert.rejects(attested.send('fixture-owner','CONTRACT',contract),/符合契約/);
 await attested.send('fixture-owner','CONTRACT',{...contract,workflowVersion:state.active.version});
 assert.ok(!(await s.execute('get_acceptance',{taskId:claimed.task.id})).blockers.some(b=>b.includes('流程版本')));
});

test('workflow provenance cannot present suggestions as project rules or reuse stale sources after field changes',async()=>{
 const {s}=setup();await project(s);
 await assert.rejects(s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:0,gitPolicy:{baseBranch:'base'},sources:[{field:'baseBranch',kind:'PROJECT_RULE',ref:'AGENTS.md'}]}),/文件與版本/);
 let state=await s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:0,gitPolicy:{baseBranch:'base'},sources:[{field:'baseBranch',kind:'PROJECT_RULE',ref:'AGENTS.md',version:'v1'}]});
 assert.equal(state.draft.sources[0].kind,'PROJECT_RULE');
 state=await s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:1,gitPolicy:{baseBranch:'different'}});
 assert.deepEqual(state.draft.sources,[{field:'baseBranch',kind:'SUGGESTION',ref:'',version:'',detail:'代理提出，等待需求負責人確認'}]);
 state=await s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:2,gitPolicy:{},sources:[]});
 assert.deepEqual(state.draft.sources,[]);
 const stale=s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:2,gitPolicy:{baseBranch:'oops'}});
 await assert.rejects(stale,/更新/);
});

test('workflow management identity is transport context, not caller arguments; provenance and preview are normalized',async()=>{
 const {s,db}=setup();const p=await project(s);
 await assert.rejects(new BoardService(db,env).execute('prepare_project_workflow',{projectName:'Example',expectedRevision:0,gitPolicy,managementActor:'forged'}),/可信管理身分/);
 let state=await s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:0,gitPolicy:{baseBranch:' base '},sources:[{field:'baseBranch',kind:'PROJECT_RULE',ref:' AGENTS.md ',version:' v1 ',detail:' rule '}]});
 assert.equal(state.draft.previewDigest,'f3ebf5b15ddaaf87880dc3b7536ca59e8a62758acf13f3d869466335dbb284a5');
 assert.equal(Object.keys(state.draft.gitPolicy).length,7);assert.equal(state.draft.gitPolicy.baseBranch,'base');assert.equal(state.draft.gitPolicy.doneWhen,'');
 assert.deepEqual(state.draft.sources,[{field:'baseBranch',kind:'PROJECT_RULE',ref:'AGENTS.md',version:'v1',detail:'rule'}]);
 assert.equal((await s.first('SELECT actor FROM project_workflow_revision WHERE project_id=?',p)).actor,'fixture-management');
 assert.equal((await s.first("SELECT type FROM board_event WHERE type='project.workflow_changed'")).type,'project.workflow_changed');
 await assert.rejects(s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:1,gitPolicy:{baseBranch:'a'.repeat(2001)}}),/欄位不合法/);
 await assert.rejects(s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:1,sources:[{field:'baseBranch',kind:'SUGGESTION',version:'a'.repeat(201)}]}),/過長/);
 await assert.rejects(s.execute('prepare_project_workflow',{projectName:'Example',expectedRevision:1,questions:[{field:'baseBranch',question:'a'.repeat(1001)}]}),/問題內容/);
});
