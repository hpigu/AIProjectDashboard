import { workflowSummary, prepareWorkflow, confirmWorkflow } from './workflow.js';
import { acceptanceSummary, recordAcceptance, assertCompletable, workBlockers } from './acceptance.js';
/** Domain service for D1. No HTTP/MCP types or credentials cross this boundary.
 * Every writer fences all reads with a database revision. A failed CHECK in a
 * D1 batch rolls back mutations, audit records and the outbox together. This is
 * deliberately optimistic serialization, not a process-local lock (isolates differ).
 */
const CATEGORIES = ["BACKEND", "FRONTEND", "TEST", "INFRA", "DOC", "OTHER"];
const STATUSES = ["TODO", "IN_PROGRESS", "BLOCKED", "DONE"];
const ACTIONS = new Set([
  "get_project_workflow", "prepare_project_workflow", "confirm_project_workflow",
  "record_acceptance_event",
  "get_acceptance",
  "create_project",
  "create_tasks",
  "list_tasks",
  "claim_next_task",
  "update_task_status",
  "reset_task_claim",
  "block_task",
  "complete_task",
  "report_implementation",
  "update_task_details",
  "set_task_dependencies",
  "preview_archive_project",
  "archive_project",
  "restore_project",
  "get_role",
  "list_roles",
  "upsert_role"
]);
const READ_ACTIONS = new Set(["get_project_workflow", "get_acceptance", "list_tasks", "preview_archive_project", "get_role", "list_roles"]);
class BoardError extends Error {
  constructor(message, status = 400, code = "INVALID_ARGUMENT") {
    super(message);
    this.name = "BoardError";
    this.status = status;
    this.code = code;
  }
}
function check(ok, message, status = 400) {
  if (!ok) throw new BoardError(message, status);
}
function required(value, name, max = Infinity) {
  check(typeof value === "string" && value.trim().length > 0, `${name} 不可為空白`);
  const result = value.trim();
  check(result.length <= max, `${name} 不可超過 ${max} 字`);
  return result;
}
function optional(value, name, max = Infinity) {
  if (value == null) return null;
  check(typeof value === "string", `${name} 必須是字串`);
  check(value.length <= max, `${name} 不可超過 ${max} 字`);
  return value;
}
function id(value, name = "id") {
  check(Number.isSafeInteger(value) && value > 0, `${name} 必須是正整數`);
  return value;
}
function version(value, mandatory = false) {
  if (value == null && !mandatory) return null;
  check(Number.isSafeInteger(value) && value >= 0, "expectedVersion 必須是非負整數");
  return value;
}
function enumeration(value, allowed, name) {
  const result = required(value, name).toUpperCase();
  check(allowed.includes(result), `不合法的 ${name}：${result}`);
  return result;
}
function array(value, name) {
  check(Array.isArray(value), `${name} 必須是陣列`);
  return value;
}
const now = () => (new Date()).toISOString();
const projectDto = (p) => ({ id: p.id, name: p.name, description: p.description, status: p.status });
const taskDto = (t) => ({
  id: t.id,
  projectId: t.project_id,
  title: t.title,
  description: t.description,
  status: t.status,
  category: t.category,
  sortOrder: t.sort_order,
  assignee: t.assignee,
  claimedAt: t.claimed_at
});
const relatedDto = (t) => ({ id: t.id, title: t.title, status: t.status, category: t.category, assignee: t.assignee });
const roleDto = (r) => ({ id: r.id, name: r.name, category: r.category, instructions: r.instructions, projectId: r.project_id });
async function sha256(value) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, "0")).join("");
}
function constantEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
class BoardService {
  constructor(db, env = {}) {
    this.db = db;
    this.env = env;
  }
  stmt(sql, ...params) {
    return this.db.prepare(sql).bind(...params);
  }
  async first(sql, ...params) {
    return this.stmt(sql, ...params).first();
  }
  async all(sql, ...params) {
    return (await this.stmt(sql, ...params).all()).results;
  }
  async auditManagement(actor, tool, authorized) {
    await this.stmt('INSERT INTO management_authorization_audit(actor,tool_name,decision) VALUES(?,?,?)',actor,tool,authorized ? 'AUTHORIZED_ATTEMPT' : 'DENIED').run();
  }
  async execute(action, args = {}, trustedContext = {}) {
    check(ACTIONS.has(action), "不支援的工具");
    check(args && typeof args === "object" && !Array.isArray(args), "arguments 必須是物件");
    /** Request-scoped context: a shared BoardService may have concurrent callers. */
    const context = new BoardService(this.db, this.env);
    context.trustedActor = trustedContext.managementActor;
    if (!READ_ACTIONS.has(action)) context.revision = (await this.first("SELECT revision FROM board_revision WHERE id=1")).revision;
    return context[action](args);
  }
  async commit(statements) {
    try {
      return await this.db.batch([
        this.stmt("UPDATE board_revision SET revision=revision+1 WHERE id=1 AND revision=?", this.revision),
        this.stmt("INSERT INTO mutation_guard(ok) VALUES(changes())"),
        this.stmt("DELETE FROM mutation_guard"),
        ...statements
      ]);
    } catch (error) {
      if (/CHECK constraint failed.*(?:mutation_guard|ok=1)|CHECK constraint failed: ok/i.test(error.message)) {
        throw new BoardError(
          "資料已被其他操作更新，請重新讀取後再操作",
          409,
          "CONFLICT"
        );
      }
      throw error;
    }
  }
  assertChanged() {
    return [this.stmt("INSERT INTO mutation_guard(ok) VALUES(changes())"), this.stmt("DELETE FROM mutation_guard")];
  }
  event(type, payload, at = now()) {
    return this.stmt("INSERT INTO board_event(type,payload,created_at) VALUES(?,?,?)", type, JSON.stringify(payload), at);
  }
  log(t, from, to, note, at) {
    return this.stmt(
      "INSERT INTO task_log(task_id,from_status,to_status,note,created_at) VALUES(?,?,?,?,?)",
      t.id,
      from,
      to,
      note ?? null,
      at
    );
  }
  statusEvent(t, to, at) {
    return this.event("task.status_changed", {
      projectId: t.project_id,
      taskId: t.id,
      title: t.title,
      from: t.status,
      to,
      at
    }, at);
  }
  async project(projectId) {
    const p = await this.first("SELECT * FROM project WHERE id=?", id(projectId, "projectId"));
    check(p, `找不到專案：#${projectId}`, 404);
    return p;
  }
  async namedProject(name, requiredFound = true) {
    const normalized = required(name, "專案名稱", 200).toLowerCase();
    const p = await this.first("SELECT * FROM project WHERE normalized_name=?", normalized);
    if (requiredFound) check(p, `找不到專案：「${name}」`, 404);
    return p;
  }
  active(p) {
    check(
      p.status === "ACTIVE",
      `專案「${p.name}」已封存，現在只能讀取`,
      409
    );
  }
  async task(taskId) {
    const t = await this.first("SELECT * FROM task WHERE id=?", id(taskId, "taskId"));
    check(t, `找不到任務：#${taskId}`, 404);
    return t;
  }
  async editableTask(a, mandatoryVersion = false) {
    version(a.expectedVersion, mandatoryVersion);
    const t = await this.task(a.taskId);
    this.active(await this.project(t.project_id));
    if (a.expectedVersion != null) check(
      t.version === a.expectedVersion,
      "任務版本衝突，請重新讀取後再操作",
      409
    );
    return t;
  }
  async ownership(t, token) {
    if (!t.claim_token_hash) return;
    check(
      typeof token === "string" && token.length > 0,
      "需要認領時取得的 claim token，請提供 claimToken",
      403
    );
    check(
      constantEqual(await sha256(token), t.claim_token_hash),
      "claim token 不正確，無法操作",
      403
    );
  }
  async tasks(projectId) {
    return this.all("SELECT * FROM task WHERE project_id=? ORDER BY sort_order,id", projectId);
  }
  async missingProject() {
    return { projectFound: false, availableProjects: (await this.all("SELECT * FROM project ORDER BY id")).map(projectDto) };
  }
  async create_project(a) {
    const name = required(a.name, "專案名稱", 200), description = optional(a.description, "description");
    const existing = await this.namedProject(name, false);
    if (existing) return { project: projectDto(existing), alreadyExisted: true };
    const at = now();
    try {
      await this.commit([
        this.stmt(
          "INSERT INTO project(name,normalized_name,description,created_at,updated_at) VALUES(?,?,?,?,?)",
          name,
          name.toLowerCase(),
          description,
          at,
          at
        ),
        this.stmt("INSERT INTO board_event(type,payload,created_at) SELECT 'project.created',json_object('projectId',id,'name',name),? FROM project WHERE normalized_name=?", at, name.toLowerCase())
      ]);
    } catch (e) {
      const winner = await this.namedProject(name, false);
      if (winner) return { project: projectDto(winner), alreadyExisted: true };
      throw e;
    }
    return { project: projectDto(await this.namedProject(name)), alreadyExisted: false };
  }
  async create_tasks(a) {
    const inputs = array(a.tasks, "任務清單");
    check(inputs.length > 0 && inputs.length <= 50, "任務清單至少 1 筆，最多 50 筆");
    const p = await this.project(a.projectId);
    this.active(p);
    const next = (await this.first("SELECT coalesce(max(id),0)+1 AS n FROM task")).n;
    const start = (await this.first("SELECT coalesce(max(sort_order),-1)+1 AS n FROM task WHERE project_id=?", p.id)).n;
    const at = now(), statements = [], created = [];
    const existingTasks = new Map((await this.tasks(p.id)).map((t) => [t.id, t]));
    /** Validate the entire batch before building a transaction; errors leave no partial tasks. */
    for (let i = 0; i < inputs.length; i++) {
      const input = inputs[i];
      check(input && typeof input === "object", "任務內容不可為 null");
      const title = required(input.title, "任務標題", 300), description = optional(input.description, "description");
      const raw = typeof input.category === "string" ? input.category.trim().toUpperCase() : "";
      const category = CATEGORIES.includes(raw) ? raw : "OTHER";
      const indexes = input.dependsOnIndexes == null ? [] : array(input.dependsOnIndexes, "dependsOnIndexes");
      const ids = input.dependsOnTaskIds == null ? [] : array(input.dependsOnTaskIds, "dependsOnTaskIds");
      for (const index of indexes) check(Number.isInteger(index) && index >= 1 && index <= i, `第 ${i + 1} 筆前置序號須在自己之前，不可自我或向前依賴`);
      for (const depId of ids) {
        id(depId, "dependsOnTaskIds");
        check(existingTasks.has(depId), "前置任務不存在或屬於其他專案");
      }
      const depIds = [...indexes.map((index) => next + index - 1), ...ids];
      check(new Set(depIds).size === depIds.length, "前置任務 ID 不可重複");
      created.push({
        id: next + i,
        project_id: p.id,
        title,
        description,
        category,
        status: "TODO",
        sort_order: start + i,
        assignee: null,
        claimed_at: null
      });
    }
    for (let i = 0; i < created.length; i++) {
      const t = created[i], input = inputs[i];
      statements.push(this.stmt("INSERT INTO task(id,project_id,title,description,category,sort_order,require_evidence,created_at,updated_at) VALUES(?,?,?,?,?,?,1,?,?)", t.id, p.id, t.title, t.description, t.category, t.sort_order, at, at));
      statements.push(this.log(t, null, "TODO", null, at));
      for (const dep of [...(input.dependsOnIndexes ?? []).map((index) => next + index - 1), ...input.dependsOnTaskIds ?? []]) statements.push(this.stmt(
        "INSERT INTO task_dependency(task_id,depends_on_task_id,created_at) VALUES(?,?,?)",
        t.id,
        dep,
        at
      ));
    }
    statements.push(this.event("tasks.created", { projectId: p.id, count: created.length }, at));
    await this.commit(statements);
    return created.map(taskDto);
  }
  async list_tasks(a) {
    check(a.projectId != null || typeof a.projectName === "string" && a.projectName.trim(), "projectId 與 projectName 至少需擇一提供");
    const p = a.projectId != null ? await this.project(a.projectId) : await this.namedProject(a.projectName, false);
    if (!p) return this.missingProject();
    const status = a.status?.trim() ? enumeration(a.status, STATUSES, "status") : null;
    const category = a.category?.trim() ? a.category.trim().toUpperCase() : null;
    const allTasks = await this.tasks(p.id), tasks = allTasks.filter((t) => (!status || t.status === status) && (!category || t.category === category));
    const waiting = await this.waiting(p.id);
    return { projectFound: true, project: projectDto(p), tasks: tasks.map((t) => ({ ...taskDto(t), waitingFor: waiting.get(
      t.id
    ) ?? [] })), doneCount: allTasks.filter((t) => t.status === "DONE").length, totalCount: allTasks.length };
  }
  async waiting(projectId) {
    const rows = await this.all("SELECT d.task_id,t.* FROM task_dependency d JOIN task t ON t.id=d.depends_on_task_id WHERE t.project_id=? ORDER BY t.sort_order,t.id", projectId);
    const result = new Map(), acceptance = new Map();
    for (const t of rows) {
      let summary = null;
      if (t.status === "DONE") {
        if (!acceptance.has(t.id)) acceptance.set(t.id, await acceptanceSummary(this,t.id));
        summary = acceptance.get(t.id);
        if (summary.canClose) continue;
      }
      const prerequisite = summary ? { ...taskDto(t), acceptanceUnverified:true, reason:summary.blockers.join("；") } : taskDto(t);
      result.set(t.task_id, [...result.get(t.task_id) ?? [], prerequisite]);
    }
    return result;
  }
  async claim_next_task(a) {
    const category = enumeration(a.category, CATEGORIES, "category"), assignee = required(a.assignee, "assignee", 60);
    /** Exactly three attempts. Revision fences protect dependency and archive reads. */
    let contended = false;
    for (let attempt = 0; attempt < 3; attempt++) {
      this.revision = (await this.first("SELECT revision FROM board_revision WHERE id=1")).revision;
      const p2 = await this.namedProject(a.projectName, false);
      if (!p2) return this.missingProject();
      this.active(p2);
      const allTasks = await this.tasks(p2.id);
      const candidates = allTasks.filter((t) => t.category === category && t.status === "TODO");
      const waiting2 = await this.waiting(p2.id), issues = new Map();
      for (const t of candidates) { const blockers = await workBlockers(this,t); if (blockers.length) issues.set(t.id,blockers); }
      const candidate = candidates.find((t) => !waiting2.has(t.id) && !issues.has(t.id));
      if (!candidates.length) return { projectFound: true, project: projectDto(p2), category, outcome: contended ? "CONTENDED" : "NO_TASK", claimed: false, contended };
      if (!candidate) return {
        projectFound: true,
        project: projectDto(p2),
        category,
        outcome: issues.size ? "BLOCKED_BY_REQUIREMENT" : "BLOCKED_BY_DEPENDENCY",
        claimed: false,
        blockedByDependency: waiting2.size > 0,
        blockedByRequirement: issues.size > 0,
        blockedCandidates: candidates.map((t) => ({ task: taskDto(t), requirementIssues: issues.get(t.id) ?? [], waitingFor: waiting2.get(
          t.id
        ) }))
      };
      const token = Array.from(crypto.getRandomValues(new Uint8Array(32)), (b) => b.toString(16).padStart(2, "0")).join(
        ""
      );
      const hash = await sha256(token), at = now();
      try {
        await this.commit([
          this.stmt("UPDATE task SET status='IN_PROGRESS',assignee=?,claimed_at=?,updated_at=? WHERE id=? AND status='TODO'", assignee, at, at, candidate.id),
          ...this.assertChanged(),
          this.stmt("UPDATE task SET claim_token_hash=?,version=version+1 WHERE id=?", hash, candidate.id),
          this.log(candidate, "TODO", "IN_PROGRESS", "認領者：" + assignee, at),
          this.statusEvent(candidate, "IN_PROGRESS", at)
        ]);
        return { projectFound: true, project: projectDto(p2), category, outcome: "CLAIMED", claimed: true, task: taskDto(
          { ...candidate, status: "IN_PROGRESS", assignee, claimed_at: at }
        ), claimToken: token };
      } catch (e) {
        if (e.code !== "CONFLICT") throw e;
        contended = true;
      }
    }
    const p = await this.namedProject(a.projectName), waiting = await this.waiting(p.id), allTasks = await this.tasks(p.id), remaining = allTasks.filter((t) => t.status === "TODO" && t.category === category);
    const issues = new Map();
    for (const t of remaining) { const blockers = await workBlockers(this,t); if (blockers.length) issues.set(t.id,blockers); }
    if (remaining.length && remaining.every(t => waiting.has(t.id) || issues.has(t.id))) return {
      projectFound: true, category, outcome: issues.size ? "BLOCKED_BY_REQUIREMENT" : "BLOCKED_BY_DEPENDENCY", claimed: false,
      blockedByDependency: waiting.size > 0, blockedByRequirement: issues.size > 0,
      blockedCandidates: remaining.map(t => ({ task: taskDto(t), waitingFor: waiting.get(t.id) ?? [], requirementIssues: issues.get(t.id) ?? [] }))
    };
    return { projectFound: true, category, outcome: "CONTENDED", claimed: false, contended: true };
  }
  async transition(t, to, note, extra = [], blockId = null) {
    const at = now(), clearClaim = to === "TODO", clearHash = clearClaim || to === "DONE";
    const statements = [
      ...extra,
      this.stmt("UPDATE task SET status=?,assignee=?,claimed_at=?,claim_token_hash=?,current_block_event_id=?,version=version+1,updated_at=? WHERE id=? AND version=?", to, clearClaim ? null : t.assignee, clearClaim ? null : t.claimed_at, clearHash ? null : t.claim_token_hash, to === "BLOCKED" ? blockId : null, at, t.id, t.version),
      ...this.assertChanged()
    ];
    if (t.status === "BLOCKED" && to !== "BLOCKED") statements.push(this.stmt("UPDATE task_block_event SET cleared_at=? WHERE task_id=? AND cleared_at IS NULL", at, t.id));
    statements.push(this.log(t, t.status, to, note, at), this.statusEvent(t, to, at));
    await this.commit(statements);
    return { task: taskDto(await this.task(t.id)), from: t.status, to, changed: true };
  }
  async update_task_status(a) {
    const to = enumeration(a.status, STATUSES, "status"), t = await this.editableTask(a);
    const transitions = { TODO: ["IN_PROGRESS", "BLOCKED"], IN_PROGRESS: ["DONE", "BLOCKED"], BLOCKED: ["IN_PROGRESS", "TODO"], DONE: ["IN_PROGRESS"] };
    check(t.status === to || transitions[t.status].includes(to), "不合法的狀態轉移");
    check(to !== "DONE", "DONE 必須使用 complete_task 並通過驗收");
    check(to !== "IN_PROGRESS" || t.assignee != null && t.claimed_at != null, "尚未認領，請使用 claim_next_task");
    check(!(t.status === "TODO" && to === "BLOCKED" && !t.assignee), "未認領的 TODO 不能標記 BLOCKED");
    if (t.status === "DONE" && to === "DONE" && typeof a.claimToken === "string" && a.claimToken.trim()) {
      throw new BoardError("claim token 已失效", 403);
    }
    if (["IN_PROGRESS", "BLOCKED"].includes(t.status)) await this.ownership(t, a.claimToken);
    if (t.status === to) return { task: taskDto(t), from: t.status, to, changed: false };
    return this.transition(t, to, optional(a.note, "note"));
  }
  async get_project_workflow(a) { return workflowSummary(this,await this.namedProject(a.projectName)); }
  async prepare_project_workflow(a) { return prepareWorkflow(this,a); }
  async confirm_project_workflow(a) { return confirmWorkflow(this,a); }
  async getProjectWorkflow(projectId) { return workflowSummary(this,await this.project(projectId)); }
  async record_acceptance_event(a) {
    return recordAcceptance(this, a);
  }
  async get_acceptance(a) {
    id(a.taskId, "taskId");
    return acceptanceSummary(this, a.taskId);
  }
  async getAcceptance(projectId, taskId) {
    const t = await this.task(taskId);
    check(t.project_id === projectId, "任務不屬於此專案", 404);
    return acceptanceSummary(this, taskId);
  }
  async reset_task_claim(a) {
    const t = await this.editableTask(a);
    check(["IN_PROGRESS", "BLOCKED"].includes(t.status), "任務已完成或尚未被認領，無需重置");
    return this.transition(t, "TODO", "leader 重置遺失的 claim token" + (a.note ? "：" + optional(
      a.note,
      "note"
    ) : ""));
  }
  async block_task(a) {
    const t = await this.editableTask(a);
    check(["IN_PROGRESS", "BLOCKED"].includes(t.status) && t.assignee, "未認領或已完成的任務不能 BLOCKED");
    await this.ownership(t, a.claimToken);
    const reasonType = enumeration(a.reasonType, ["DEPENDENCY", "USER_INPUT", "TECHNICAL", "ENVIRONMENT", "EXTERNAL", "OTHER"], "reasonType");
    const detail = required(a.detail, "detail"), ids = [...new Set((a.blockingTaskIds == null ? [] : array(
      a.blockingTaskIds,
      "blockingTaskIds"
    )).filter((v) => v != null))];
    check(reasonType !== "DEPENDENCY" || ids.length, "DEPENDENCY 必須指定 blockingTaskIds");
    const existingTasks = new Map((await this.tasks(t.project_id)).map((t2) => [t2.id, t2]));
    const blockingTasks = [];
    for (const dep of ids) {
      id(dep, "blockingTaskIds");
      check(dep !== t.id, "不能把自己列為 blocker");
      const b = existingTasks.get(dep);
      check(b, "blockingTaskIds 必須是存在的同專案任務");
      blockingTasks.push({ id: b.id, title: b.title, status: b.status });
    }
    const blockId = (await this.first("SELECT coalesce(max(id),0)+1 AS n FROM task_block_event")).n, at = now();
    const extra = [
      this.stmt("UPDATE task_block_event SET cleared_at=? WHERE task_id=? AND cleared_at IS NULL", at, t.id),
      this.stmt(
        "INSERT INTO task_block_event(id,task_id,reason_type,detail,blocked_by,blocked_at) VALUES(?,?,?,?,?,?)",
        blockId,
        t.id,
        reasonType,
        detail,
        t.assignee,
        at
      ),
      ...ids.map((dep) => this.stmt(
        "INSERT INTO task_block_dependency(block_event_id,blocking_task_id) VALUES(?,?)",
        blockId,
        dep
      ))
    ];
    const result = await this.transition(t, "BLOCKED", `BLOCKED（${reasonType}）：${detail}`, extra, blockId);
    return { ...result, reasonType, detail, blockingTasks };
  }
  async complete_task(a) {
    return this.submitCompletion(a, true);
  }
  async report_implementation(a) {
    return this.submitCompletion(a, false);
  }
  async submitCompletion(a, finishing) {
    const t = await this.editableTask(a);
    check((finishing ? ["IN_PROGRESS", "BLOCKED", "DONE"] : ["IN_PROGRESS", "BLOCKED"]).includes(t.status), "只有已認領的任務可以回報");
    if (t.status === "DONE" && typeof a.claimToken === "string" && a.claimToken.trim()) {
      throw new BoardError("claim token 已失效", 403);
    }
    await this.ownership(t, a.claimToken);
    const summary = required(a.summary, "summary"), verifications = array(a.verificationResults, "verificationResults");
    check(verifications.length, "必須附上至少一筆 verificationResults");
    const normalized = verifications.map((v) => {
      check(v && typeof v === "object", "verification 不可為空");
      const name = required(v.name, "verification name", 200), result2 = enumeration(v.result, ["PASSED", "FAILED", "NOT_RUN"], "result"), detail = optional(v.detail, "verification detail")?.trim() ?? null;
      check(result2 === "PASSED" || !!detail, "FAILED/NOT_RUN 必須填寫 detail");
      check(!finishing || result2 !== "FAILED", "verification FAILED，不能標記 DONE");
      return { name, result: result2, detail };
    });
    const changedFiles = optional(a.changedFiles, "changedFiles")?.trim() || null, commitRef = optional(a.commitRef, "commitRef", 200)?.trim() || null;
    if (finishing) await assertCompletable(this, t.id, commitRef);
    const evidenceId = (await this.first("SELECT coalesce(max(id),0)+1 AS n FROM task_completion_evidence")).n, at = now();
    const extra = [
      this.stmt("INSERT INTO task_completion_evidence(id,task_id,summary,changed_files,commit_ref,completed_by,completed_at) VALUES(?,?,?,?,?,?,?)", evidenceId, t.id, summary, changedFiles, commitRef, t.assignee ?? "unknown", at),
      ...normalized.map((v) => this.stmt("INSERT INTO task_completion_verification(evidence_id,name,result,detail) VALUES(?,?,?,?)", evidenceId, v.name, v.result, v.detail))
    ];
    if (finishing) {
      const result = await this.transition(t, "DONE", "complete_task：" + summary, extra);
      return { ...result, evidence: await this.evidence(evidenceId) };
    }
    await this.commit([
      this.stmt("UPDATE task SET version=version+1,updated_at=? WHERE id=? AND version=?",at,t.id,t.version), ...this.assertChanged(),
      ...extra, this.log(t,t.status,t.status,"report_implementation（代理自測聲明）：" + summary,at),
      this.event("task.updated",{projectId:t.project_id,taskId:t.id},at)
    ]);
    return { task: taskDto(await this.task(t.id)), from:t.status, to:t.status, changed:false, evidence:await this.evidence(evidenceId) };
  }
  async update_task_details(a) {
    const t = await this.editableTask(a, true);
    check(a.title != null || a.description != null || a.category != null, "至少提供 title、description 或 category");
    check(["TODO", "BLOCKED"].includes(t.status), "只有 TODO/BLOCKED 可修改規格");
    check(a.category == null || t.status === "TODO", "只有 TODO 可修改 category");
    const values = { title: a.title == null ? t.title : required(a.title, "任務標題", 300), description: a.description == null ? t.description : optional(a.description, "description"), category: a.category == null ? t.category : enumeration(a.category, CATEGORIES, "category") };
    const changes = Object.entries(values).filter(([field, value]) => value !== t[field]).map(([field, value]) => ({
      field,
      oldValue: t[field],
      newValue: value
    }));
    if (!changes.length) return { taskId: t.id, version: t.version, changed: false, changes };
    const at = now();
    await this.commit([this.stmt("UPDATE task SET title=?,description=?,category=?,version=version+1,updated_at=? WHERE id=? AND version=?", values.title, values.description, values.category, at, t.id, t.version), ...this.assertChanged(), this.log(t, t.status, t.status, "leader-only update_task_details\n" + JSON.stringify(changes), at), this.event("task.updated", { projectId: t.project_id, taskId: t.id }, at)]);
    return { taskId: t.id, version: t.version + 1, changed: true, changes };
  }
  async set_task_dependencies(a) {
    const t = await this.editableTask(a, true);
    check(t.status === "TODO", "只有 TODO 可修改前置相依");
    const ids = a.prerequisiteTaskIds == null ? [] : array(a.prerequisiteTaskIds, "prerequisiteTaskIds");
    check(new Set(ids).size === ids.length, "前置任務 ID 不可重複");
    check(!ids.includes(t.id), "不可依賴自己");
    const existingTasks = new Map((await this.tasks(t.project_id)).map((t2) => [t2.id, t2]));
    for (const dep of ids) {
      id(dep, "prerequisiteTaskIds");
      check(existingTasks.has(dep), "前置任務不存在或屬於其他專案");
    }
    const rows = await this.all("SELECT d.* FROM task_dependency d JOIN task t ON t.id=d.task_id WHERE t.project_id=?", t.project_id), graph = new Map();
    for (const d of rows) if (d.task_id !== t.id) graph.set(d.task_id, [...graph.get(d.task_id) ?? [], d.depends_on_task_id]);
    graph.set(t.id, ids);
    this.assertAcyclic(graph);
    const oldIds = rows.filter((d) => d.task_id === t.id).map((d) => d.depends_on_task_id).sort((a2, b) => a2 - b), newIds = [
      ...ids
    ].sort((a2, b) => a2 - b);
    if (JSON.stringify(oldIds) === JSON.stringify(newIds)) return {
      taskId: t.id,
      version: t.version,
      changed: false,
      oldPrerequisiteTaskIds: oldIds,
      newPrerequisiteTaskIds: newIds
    };
    const at = now();
    await this.commit([this.stmt("UPDATE task SET version=version+1,updated_at=? WHERE id=? AND version=?", at, t.id, t.version), ...this.assertChanged(), this.stmt("DELETE FROM task_dependency WHERE task_id=?", t.id), ...newIds.map((dep) => this.stmt("INSERT INTO task_dependency(task_id,depends_on_task_id,created_at) VALUES(?,?,?)", t.id, dep, at)), this.log(
      t,
      t.status,
      t.status,
      "leader-only set_task_dependencies\n" + JSON.stringify({ oldIds, newIds }),
      at
    ), this.event("task.updated", { projectId: t.project_id, taskId: t.id }, at)]);
    return { taskId: t.id, version: t.version + 1, changed: true, oldPrerequisiteTaskIds: oldIds, newPrerequisiteTaskIds: newIds };
  }
  assertAcyclic(graph) {
    const visited = new Set(), visiting = new Set();
    const visit = (n) => {
      check(!visiting.has(n), "偵測到任務相依循環", 409);
      if (visited.has(n)) return;
      visiting.add(n);
      for (const dep of graph.get(n) ?? []) visit(dep);
      visiting.delete(n);
      visited.add(n);
    };
    for (const n of graph.keys()) visit(n);
  }
  async preview_archive_project(a) {
    const p = await this.namedProject(a.projectName), tasks = await this.tasks(p.id), counts = {
      todo: 0,
      inProgress: 0,
      blocked: 0,
      done: 0
    };
    for (const t of tasks) counts[{ TODO: "todo", IN_PROGRESS: "inProgress", BLOCKED: "blocked", DONE: "done" }[t.status]]++;
    return { project: projectDto(p), counts, assignees: tasks.filter((t) => t.assignee).map((t) => ({ taskId: t.id, title: t.title, status: t.status, assignee: t.assignee })) };
  }
  async archive_project(a) {
    return this.archive(a, false);
  }
  async restore_project(a) {
    return this.archive(a, true);
  }
  async archive(a, restore) {
    const reason = required(a.reason, "reason"), preview = await this.preview_archive_project(a), p = preview.project;
    check(p.status === (restore ? "ARCHIVED" : "ACTIVE"), restore ? "專案目前為 ACTIVE，無需恢復" : "專案已封存");
    check(restore || !preview.counts.inProgress || a.inProgressConfirmed === true, "有 IN_PROGRESS 任務，必須再次明確確認 inProgressConfirmed=true");
    const at = now(), to = restore ? "ACTIVE" : "ARCHIVED", c = preview.counts;
    await this.commit([
      this.stmt("UPDATE project SET status=?,updated_at=? WHERE id=? AND status=?", to, at, p.id, p.status),
      ...this.assertChanged(),
      this.stmt(
        "INSERT INTO project_audit(project_id,action,reason,todo_count,in_progress_count,blocked_count,done_count,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
        p.id,
        restore ? "RESTORED" : "ARCHIVED",
        reason,
        c.todo,
        c.inProgress,
        c.blocked,
        c.done,
        at
      ),
      this.event(
        "project.status_changed",
        { projectId: p.id, from: p.status, to, at },
        at
      )
    ]);
    return { project: { ...p, status: to }, counts: c, auditedAt: at };
  }
  async listRoles(projectName) {
    let projectId = null;
    if (projectName?.trim()) {
      const p = await this.namedProject(projectName, false);
      if (!p) return [];
      projectId = p.id;
    }
    const rows = await this.all("SELECT * FROM role WHERE project_id IS NULL OR project_id=? ORDER BY project_id,id", projectId), byName = new Map();
    for (const r of rows) byName.set(r.name, { name: r.name, category: r.category, instructions: r.instructions, isOverride: r.project_id != null });
    return [...byName.values()];
  }
  async list_roles(a) {
    if (a.projectName?.trim() && !await this.namedProject(a.projectName, false)) return this.missingProject();
    return { roles: await this.listRoles(a.projectName), projectFound: true };
  }
  async get_role(a) {
    const name = required(a.name, "角色名稱", 60);
    let p = null;
    if (a.projectName?.trim()) {
      p = await this.namedProject(a.projectName, false);
      if (!p) return this.missingProject();
    }
    const r = await this.first("SELECT * FROM role WHERE name=? AND (project_id IS NULL OR project_id=?) ORDER BY project_id DESC LIMIT 1", name, p?.id ?? null);
    return r ? { found: true, role: roleDto(r), isOverride: r.project_id != null, project: p ? projectDto(p) : null } : {
      found: false,
      availableRoleNames: (await this.all("SELECT name FROM role WHERE project_id IS NULL ORDER BY id")).map(
        (r2) => r2.name
      )
    };
  }
  async upsert_role(a) {
    const name = required(a.name, "角色名稱", 60), instructions = required(a.instructions, "instructions"), category = optional(a.category, "category", 30);
    let p = null;
    if (a.projectName?.trim()) {
      p = await this.namedProject(a.projectName, false);
      if (!p) return this.missingProject();
      this.active(p);
    }
    const existing = await this.first("SELECT * FROM role WHERE name=? AND coalesce(project_id,0)=?", name, p?.id ?? 0), at = now();
    await this.commit([existing ? this.stmt(
      "UPDATE role SET category=?,instructions=?,updated_at=? WHERE id=?",
      category,
      instructions,
      at,
      existing.id
    ) : this.stmt("INSERT INTO role(name,category,instructions,project_id,created_at,updated_at) VALUES(?,?,?,?,?,?)", name, category, instructions, p?.id ?? null, at, at)]);
    return { ...await this.get_role(a), created: !existing };
  }
  async listProjects(params = new URLSearchParams()) {
    const status = params.get("status") || (params.get("includeArchived") === "true" ? "ALL" : "ACTIVE"), sort = params.get("sort") || "UPDATED_DESC";
    check(["ACTIVE", "ARCHIVED", "ALL"].includes(status), "status must be ACTIVE, ARCHIVED, or ALL");
    check(["UPDATED_DESC", "NAME", "PROGRESS"].includes(sort), "sort must be UPDATED_DESC, NAME, or PROGRESS");
    const blocked = params.get("blocked");
    check(blocked == null || ["true", "false"].includes(blocked), "blocked 必須是布林值");
    const prefix = (params.get("q") || "").trim().toLowerCase();
    const rows = await this.all("SELECT p.*,max(t.updated_at) AS activity,count(t.id) AS total,sum(t.status='TODO') AS todo,sum(t.status='IN_PROGRESS') AS in_progress,sum(t.status='BLOCKED') AS blocked,sum(t.status='DONE') AS done FROM project p LEFT JOIN task t ON t.project_id=p.id GROUP BY p.id");
    const result = rows.filter((p) => (status === "ALL" || p.status === status) && p.normalized_name.startsWith(prefix)).map((p) => ({ id: p.id, name: p.name, status: p.status, counts: {
      TODO: p.todo || 0,
      IN_PROGRESS: p.in_progress || 0,
      BLOCKED: p.blocked || 0,
      DONE: p.done || 0
    }, total: p.total, hasBlocked: p.blocked > 0, updatedAt: p.activity ?? p.updated_at })).filter((p) => blocked == null || p.hasBlocked === (blocked === "true"));
    const recent = (a, b) => b.updatedAt.localeCompare(a.updatedAt) || a.id - b.id;
    result.sort(sort === "NAME" ? (a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()) || a.id - b.id : sort === "PROGRESS" ? (a, b) => (b.total ? b.counts.DONE / b.total : 0) - (a.total ? a.counts.DONE / a.total : 0) || recent(
      a,
      b
    ) : recent);
    if (!params.has("q") && !params.has("status") && !params.has("blocked") && !params.has("sort")) result.sort((a, b) => Number(
      b.hasBlocked
    ) - Number(a.hasBlocked) || recent(a, b));
    return result;
  }
  async getBoard(projectId) {
    const p = await this.project(projectId), tasks = await this.tasks(p.id), waiting = await this.waiting(p.id), columns = Object.fromEntries(STATUSES.map((s) => [s, []]));
    for (const t of tasks) columns[t.status].push({
      id: t.id,
      title: t.title,
      category: t.category,
      sortOrder: t.sort_order,
      assignee: t.assignee,
      waitingFor: (waiting.get(t.id) ?? []).map((w) => w.id)
    });
    return { id: p.id, name: p.name, description: p.description, columns };
  }
  async getDependencies(projectId) {
    await this.project(projectId);
    const tasks = await this.tasks(projectId), waiting = await this.waiting(projectId), deps = await this.all("SELECT d.* FROM task_dependency d JOIN task t ON t.id=d.task_id WHERE t.project_id=? ORDER BY d.id", projectId), graph = new Map();
    for (const d of deps) graph.set(d.task_id, [...graph.get(d.task_id) ?? [], d.depends_on_task_id]);
    this.assertAcyclic(graph);
    return { nodes: tasks.map((t) => ({
      id: t.id,
      title: t.title,
      category: t.category,
      status: t.status,
      assignee: t.assignee,
      claimable: t.status === "TODO" && !waiting.has(t.id)
    })), edges: deps.map((d) => ({
      prerequisiteTaskId: d.depends_on_task_id,
      taskId: d.task_id
    })) };
  }
  async getHistory(projectId, taskId) {
    await this.project(projectId);
    const t = await this.task(taskId);
    check(t.project_id === projectId, "任務不屬於此專案", 404);
    return (await this.all("SELECT * FROM task_log WHERE task_id=? ORDER BY id", taskId)).map((r) => ({ id: r.id, taskId: r.task_id, fromStatus: r.from_status, toStatus: r.to_status, note: r.note, createdAt: r.created_at }));
  }
  async evidence(evidenceId) {
    const e = await this.first("SELECT * FROM task_completion_evidence WHERE id=?", evidenceId);
    if (!e) return null;
    return {
      id: e.id,
      summary: e.summary,
      changedFiles: e.changed_files,
      commitRef: e.commit_ref,
      completedBy: e.completed_by,
      completedAt: e.completed_at,
      verifications: (await this.all("SELECT * FROM task_completion_verification WHERE evidence_id=? ORDER BY id", e.id)).map((v) => ({ id: v.id, name: v.name, result: v.result, detail: v.detail }))
    };
  }
  async getTaskDetail(projectId, taskId) {
    const t = await this.task(taskId);
    check(t.project_id === projectId, "找不到此專案中的任務", 404);
    const prereq = await this.all("SELECT t.* FROM task_dependency d JOIN task t ON t.id=d.depends_on_task_id WHERE d.task_id=? ORDER BY t.id", taskId), downstream = await this.all("SELECT t.* FROM task_dependency d JOIN task t ON t.id=d.task_id WHERE d.depends_on_task_id=? ORDER BY t.id", taskId);
    const b = t.current_block_event_id ? await this.first("SELECT * FROM task_block_event WHERE id=?", t.current_block_event_id) : null;
    const blocker = b ? {
      id: b.id,
      reasonType: b.reason_type,
      detail: b.detail,
      blockedBy: b.blocked_by,
      blockedAt: b.blocked_at,
      clearedAt: b.cleared_at,
      blockingTasks: await this.all("SELECT t.id,t.title,t.status FROM task_block_dependency d JOIN task t ON t.id=d.blocking_task_id WHERE d.block_event_id=? ORDER BY t.id", b.id)
    } : null;
    const e = await this.first("SELECT id FROM task_completion_evidence WHERE task_id=? ORDER BY id DESC LIMIT 1", taskId);
    return {
      ...taskDto(t),
      createdAt: t.created_at,
      updatedAt: t.updated_at,
      version: t.version,
      prerequisites: prereq.map(relatedDto),
      downstreamTasks: downstream.map(relatedDto),
      history: await this.getHistory(projectId, taskId),
      currentBlocker: blocker,
      completionEvidence: e ? await this.evidence(e.id) : null
    };
  }
  async latestEventId() {
    return (await this.first("SELECT coalesce(max(id),0) AS id FROM board_event")).id;
  }
  async eventsAfter(cursor = 0) {
    check(Number.isSafeInteger(cursor) && cursor >= 0, "event cursor 必須是非負整數");
    return (await this.all("SELECT * FROM board_event WHERE id>? ORDER BY id LIMIT 100", cursor)).map((e) => ({
      id: e.id,
      type: e.type,
      payload: JSON.parse(e.payload),
      createdAt: e.created_at
    }));
  }
}
export {
  BoardError,
  BoardService
};
