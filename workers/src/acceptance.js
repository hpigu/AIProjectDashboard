import { effectiveWorkflowPolicy, hasWorkflow } from './workflow.js';
/** Signed attestations only: never execute commands or fetch artifact URLs. */
const encoder = new TextEncoder();
const kinds = ['PROJECT_POLICY', 'CONTRACT', 'RUN', 'REVIEW', 'EXCEPTION', 'INTEGRATION', 'DISPATCH', 'RELEASE'];
function require(ok, message) { if (!ok) { const e = new Error(message); e.name = 'BoardError'; e.status = 400; throw e; } }
function text(o, k) { require(typeof o?.[k] === 'string' && o[k].trim(), `驗收欄位不可空白：${k}`); return o[k]; }
export async function digest(s) { return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(s))), x => x.toString(16).padStart(2, '0')).join(''); }
const latest = (rows, kind) => rows.filter(e => e.kind === kind).at(-1);
const taskDigest = t => digest(JSON.stringify([t.title,t.description ?? '',t.category]));
const subject = rows => digest(rows.filter(e => ['CONTRACT','RUN','EXCEPTION'].includes(e.kind)).map(e => e.eventId + '\n').join(''));
async function rows(s, taskId) { return (await s.all('SELECT payload,actor,created_at FROM acceptance_event WHERE task_id=? ORDER BY id', taskId)).map(r => ({ ...JSON.parse(r.payload), _verifiedActor: r.actor, _receivedAt: r.created_at })); }
const canonical = v => JSON.stringify(v && typeof v === 'object' && !Array.isArray(v) ? Object.fromEntries(Object.keys(v).sort().map(k => [k, v[k]])) : v);
function validateGit(git) {
  for (const f of ['baseBranch','integrationTarget','reviewPolicy','mergeAuthority','pullRequestPolicy','requiredChecks']) text(git,f);
  require(['ACCEPTED','INTEGRATED'].includes(text(git,'doneWhen')), 'Git 流程須明訂 DONE 時機');
}
const immutableCodeRef = value => typeof value === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64}|sha256:[a-f0-9]{64})$/.test(value);
function validateContract(b, hash) {
  for (const f of ['sourceRef','sourceVersion','codeRef','taskDigest','flowRef','flowVersion','implementationActor']) text(b,f);
  require(immutableCodeRef(b.codeRef), '受測版本必須是完整 Git SHA 或 sha256 digest，不能使用可變分支／標籤');
  require(b.taskDigest === hash, '需求描述已改變，請重新確認契約');
  require(Array.isArray(b.requirements) && b.requirements.length, '契約必須有需求');
  require(Array.isArray(b.questions) && Array.isArray(b.assumptions), '問題與推測須明確列出');
  const ids = new Set();
  for (const r of b.requirements) { require(!ids.has(text(r,'id')), '需求 ID 重複'); ids.add(r.id); text(r,'acceptance'); require(['CONFIRMED','PENDING','ASSUMPTION'].includes(text(r,'state')), '需求狀態不合法'); }
  require(Array.isArray(b.cases) && b.cases.length, '契約必須有測試案例');
  const cases = new Set();
  for (const c of b.cases) { require(!cases.has(text(c,'id')), '案例 ID 重複'); cases.add(c.id); text(c,'kind'); text(c,'scenario'); require(Array.isArray(c.requirementIds) && c.requirementIds.length && c.requirementIds.every(i => ids.has(i)), '案例須連結存在的需求'); }
  if (b.browserApplicable === false) text(b,'browserExemptionReason');
  for (const f of ['baseBranch','integrationTarget','reviewPolicy','mergeAuthority','pullRequestPolicy','requiredChecks']) text(b.gitPolicy,f);
  require(['ACCEPTED','INTEGRATED'].includes(text(b.gitPolicy,'doneWhen')), 'Git 流程須明訂 DONE 時機');
}
export async function acceptanceSummary(s, taskId) {
  const task = await s.task(taskId), events = await rows(s, taskId), contract = latest(events,'CONTRACT');
  const hash = await taskDigest(task), subjectDigest = await subject(events), blockers = [], passedCases = [];
  if (!contract) blockers.push('尚無已核准驗收契約；舊紀錄與代理聲明未驗證');
  else {
    const b = contract.body, cid = contract.eventId;
    if (!immutableCodeRef(b.codeRef)) blockers.push('受測版本不是不可變識別，請重新確認契約');
    const workflowPolicy = await effectiveWorkflowPolicy(s, task.project_id);
    if (workflowPolicy?.version && b.workflowVersion !== workflowPolicy.version) blockers.push('專案 Git 流程版本已變更，請重新確認契約');
    if (canonical(b.gitPolicy) !== canonical((await effectiveWorkflowPolicy(s, task.project_id))?.gitPolicy)) blockers.push('專案 Git 流程已變更，請重新確認契約');
    if (task.assignee != null && task.assignee !== b.implementationActor) blockers.push('認領者與契約原作者不符，請重新確認');
    if (b.taskDigest !== hash) blockers.push('任務需求已變更，契約失效');
    if (b.questions.length) blockers.push('有待人類決定的需求問題');
    for (const r of b.requirements) {
      if (r.state !== 'CONFIRMED') blockers.push('需求未確認：' + r.id);
      if (!b.cases.some(c => c.requirementIds.includes(r.id))) blockers.push('需求漏驗：' + r.id);
    }
    for (const c of b.cases) {
      const current = events.filter(e => e.body.contractId === cid && e.body.caseId === c.id);
      const run = latest(current,'RUN'), exception = latest(current,'EXCEPTION');
      if (run?.body.result === 'PASSED') passedCases.push(c.id);
      else if (!exception || current.indexOf(exception) < current.indexOf(run)) blockers.push('失敗或未驗證：' + c.id);
    }
    if (b.browserApplicable !== false) for (const scenario of ['NORMAL','ERROR','BOUNDARY','PERMISSION','FAILURE','PERSISTENCE','DOWNSTREAM']) {
      if (!b.cases.some(c => c.scenario === scenario) && !(typeof b.scenarioExemptions?.[scenario] === 'string' && b.scenarioExemptions[scenario].trim())) blockers.push('缺少適用情境：' + scenario);
    }
    if (!b.cases.some(c => c.kind === 'BROWSER') && !(b.browserApplicable === false && typeof b.browserExemptionReason === 'string' && b.browserExemptionReason.trim())) blockers.push('缺少真實瀏覽器流程案例（非 UI 專案須由需求負責人明訂適用性）');
    const review = latest(events,'REVIEW');
    if (!review || review.body.contractId !== cid || review.body.subjectDigest !== subjectDigest || review.body.decision !== 'APPROVED') blockers.push('尚無涵蓋目前證據的獨立核准');
    if (b.gitPolicy.doneWhen === 'INTEGRATED' && latest(events,'INTEGRATION')?.body.contractId !== cid) blockers.push('尚未記錄核准整合');
  }
  return { revision: events.length, taskDigest: hash, subjectDigest, canClose: !blockers.length, passedCases, blockers, events };
}
export async function recordAcceptance(s, { actor, payload, signature }) {
  require(typeof payload === 'string' && payload.length <= 48000, '驗收封包過大或空白');
  const key = await verifyAcceptanceSignature(s, actor, payload, signature);
  const e = JSON.parse(payload), t = await s.task(e.taskId);
  s.active(await s.project(t.project_id));
  require(e.projectId === t.project_id, '驗收專案不符');
  require(kinds.includes(e.kind), '未知驗收事件');
  require(key.projectIds?.map(String).includes(String(t.project_id)) && key.permissions?.includes(e.kind), '驗收身分沒有此專案／操作權限');
  const events = await rows(s,t.id); require(e.expectedRevision === events.length, '驗收紀錄已更新，請重新讀取');
  const b = e.body;
  if (e.kind === 'PROJECT_POLICY') { require(!await hasWorkflow(s,t.project_id), '專案已有版本化流程，請使用流程確認工具'); validateGit(b?.gitPolicy); }
  else if (e.kind === 'CONTRACT') {
    validateContract(b, await taskDigest(t));
    require(t.assignee == null || b.implementationActor === t.assignee, "契約實作者與任務認領者不符");
    const policy = await effectiveWorkflowPolicy(s,t.project_id);
    require(policy && (!policy.version || b.workflowVersion === policy.version) && canonical(policy.gitPolicy) === canonical(b.gitPolicy), '必須先設定符合契約的專案 Git 流程');
  }
  else if (e.kind !== 'DISPATCH') {
    const contract = latest(events,'CONTRACT');
    require(contract && text(b,'contractId') === contract.eventId, '驗收契約版本不符');
    require(text(b,'codeRef') === contract.body.codeRef, '受測程式版本不符');
    if (e.kind === 'RUN') {
      require(actor !== contract.body.implementationActor, '開發代理不能自行簽署執行證據');
      require(key.runnerProfiles?.includes(text(b,'runnerProfile')), '未核准的執行器');
      for (const f of ['caseId','runId','result','artifactUri','artifactSha256','steps','actual','environment']) text(b,f);
      require(/^[a-f0-9]{64}$/.test(b.artifactSha256), '證據必須有 SHA-256');
      require(['PASSED','FAILED','NOT_RUN'].includes(b.result), '未知執行結果'); require(b.environment === 'ISOLATED', '僅接受隔離測試環境');
      require(contract.body.cases.some(c => c.id === b.caseId), '測試案例不在契約內');
    } else if (e.kind === 'REVIEW') {
      require(actor !== t.assignee && actor !== contract.body.implementationActor && !events.some(e => e.kind === 'RUN' && e.body.contractId === contract.eventId && e._verifiedActor === actor), '原作者不能是唯一驗收者');
      require(text(b,'subjectDigest') === await subject(events), '審查未涵蓋目前證據');
      text(b,'reason'); require(['APPROVED','REJECTED'].includes(text(b,'decision')), '未知審查結論');
    } else if (e.kind === 'EXCEPTION') { require(actor !== t.assignee && actor !== contract.body.implementationActor, '開發代理不能自行核准免驗'); for (const f of ['caseId','reason','approvalRef']) text(b,f); require(contract.body.cases.some(c => c.id === b.caseId), '測試案例不在契約內'); }
    else text(b,'ref');
  } else for (const f of ['sessionId','worktree','branch','baseRef','lastSeen','handoff']) text(b,f);
  const eventId = text(e,'eventId'); require(eventId.length <= 100 && !(await s.first('SELECT event_id FROM acceptance_event WHERE event_id=?',eventId)), '重複驗收事件');
  await s.commit([s.stmt('UPDATE task SET version=version+1 WHERE id=? AND version=?',t.id,t.version), ...s.assertChanged(), s.stmt('INSERT INTO acceptance_event(event_id,project_id,task_id,actor,kind,payload) VALUES(?,?,?,?,?,?)',eventId,t.project_id,t.id,actor,e.kind,payload)]);
  return acceptanceSummary(s,t.id);
}
export async function assertCompletable(s, taskId, codeRef) {
  const summary = await acceptanceSummary(s,taskId); require(summary.canClose, '不能結案：' + summary.blockers.join('；'));
  require(latest(summary.events,'CONTRACT').body.codeRef === codeRef, '完成回報版本與受測版本不符');
}

export async function workBlockers(s, task) {
  const contract = latest(await rows(s,task.id),'CONTRACT');
  if (!contract) return [];
  const b = contract.body, issues=[];
  if (b.questions.length) issues.push('有待人類決定的需求問題');
  for (const r of b.requirements) if (r.state !== 'CONFIRMED') issues.push('需求未確認：' + r.id);
  if (b.taskDigest !== await taskDigest(task)) issues.push('任務需求已變更，請重新確認契約');
  return issues;
}

export async function verifyAcceptanceSignature(s, actor, payload, signature) {
  require(typeof payload === "string" && payload.length <= 48000, "驗收封包過大或空白");
  const config = typeof s.env.BOARD_ACCEPTANCE_AUTHORITIES === 'string' ? JSON.parse(s.env.BOARD_ACCEPTANCE_AUTHORITIES || '{}') : {};
  require(config && typeof config === 'object' && !Array.isArray(config), '驗收身分設定必須是物件');
  const secrets = new Set();
  for (const entry of Object.values(config)) {
    require(entry && typeof entry.secret === 'string' && entry.secret.length >= 32 && !secrets.has(entry.secret), '驗收金鑰必須至少 32 字元且各身分不可共用');
    secrets.add(entry.secret);
    require(Array.isArray(entry.permissions) && Array.isArray(entry.projectIds), '驗收身分須明列權限與專案');
    if (entry.permissions.includes('RUN')) require(!['CONTRACT','REVIEW','EXCEPTION','PROJECT_POLICY'].some(p => entry.permissions.includes(p)), '執行器金鑰不得兼任需求核准、審查或例外核准');
  }
  const key = config[actor]; require(key && typeof key.secret === 'string' && key.secret.length >= 32, '驗收身分未設定');
  require(typeof signature === 'string' && /^[a-f0-9]{64}$/.test(signature), '驗收簽章無效');
  const cryptoKey = await crypto.subtle.importKey('raw', encoder.encode(key.secret), { name:'HMAC', hash:'SHA-256' }, false, ['verify']);
  require(await crypto.subtle.verify('HMAC', cryptoKey, Uint8Array.from(signature.match(/../g), b => parseInt(b,16)), encoder.encode(payload)), '驗收簽章無效');
  return key;
}
