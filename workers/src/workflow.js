import { digest, verifyAcceptanceSignature } from './acceptance.js';
export const FIELDS = ['baseBranch','integrationTarget','pullRequestPolicy','requiredChecks','reviewPolicy','mergeAuthority','doneWhen'];
const require = (ok,message) => { if(!ok) { const e=new Error(message); e.name='BoardError'; throw e; } };
const text = v => typeof v==='string' && v.trim().length>0;
const clean = value => value==null?'':value.trim();
const policy = value => Object.fromEntries(FIELDS.map(f=>[f,value?.[f]??'']));
const proposal = row => row?JSON.parse(row.proposal_json):null;
function normalizeSource(entry) {
 require(entry&&FIELDS.includes(entry.field)&&['PROJECT_RULE','USER_DECISION','SUGGESTION'].includes(entry.kind),'來源欄位不合法');
 for(const key of ['ref','version','detail']) require(entry[key]==null||typeof entry[key]==='string','來源內容不合法');
 const result={field:entry.field,kind:entry.kind,ref:clean(entry.ref),version:clean(entry.version),detail:clean(entry.detail)};
 require(result.ref.length<=1000&&result.version.length<=200&&result.detail.length<=2000,'來源內容過長');
 require(entry.kind!=='PROJECT_RULE'||text(result.ref)&&text(result.version),'專案規則來源必須有文件與版本');
 return result;
}
function normalizeQuestion(q) {
 require(q&&FIELDS.includes(q.field)&&text(q.question)&&q.question.trim().length<=1000,'問題內容不合法');
 return {field:q.field,question:q.question.trim()};
}
export async function hasWorkflow(s,id) { return !!await s.first('SELECT id FROM project_workflow_revision WHERE project_id=? LIMIT 1',id); }
export async function effectiveWorkflowPolicy(s,id) {
 const active=await s.first("SELECT * FROM project_workflow_revision WHERE project_id=? AND state='CONFIRMED' ORDER BY revision DESC LIMIT 1",id);
 if(active) return {version:active.revision,gitPolicy:policy(proposal(active).gitPolicy)};
 const legacy=await s.first("SELECT payload FROM acceptance_event WHERE project_id=? AND kind='PROJECT_POLICY' ORDER BY id DESC LIMIT 1",id);
 return legacy ? {version:0,gitPolicy:policy(JSON.parse(legacy.payload).body.gitPolicy)} : null;
}
export async function workflowSummary(s,p) {
 const rows=await s.all('SELECT * FROM project_workflow_revision WHERE project_id=? ORDER BY revision DESC',p.id);
 const activeRow=rows.find(r=>r.state==='CONFIRMED'),activeProposal=proposal(activeRow);
 const active=activeRow?{version:activeRow.revision,gitPolicy:policy(activeProposal.gitPolicy),sources:activeProposal.sources,confirmedBy:activeRow.actor,approvalRef:activeRow.approval_ref,confirmedAt:activeRow.created_at,previewDigest:activeRow.preview_digest}:null;
 const old=await s.first("SELECT payload FROM acceptance_event WHERE project_id=? AND kind='PROJECT_POLICY' ORDER BY id DESC LIMIT 1",p.id);
 const event=old?JSON.parse(old.payload):null,legacy=event?{gitPolicy:policy(event.body.gitPolicy),eventId:event.eventId}:null;
 let draft=null;
 if(rows[0]?.state==='DRAFT') {
  const row=rows[0],body=proposal(row),gitPolicy=policy(body.gitPolicy),baseline=active?.gitPolicy??legacy?.gitPolicy??{};
  const missingFields=FIELDS.filter(f=>!text(gitPolicy[f]));
  draft={version:row.revision,baseVersion:row.base_revision,gitPolicy,sources:body.sources,questions:body.questions,missingFields,readyForConfirmation:!missingFields.length&&!body.questions.length,previewDigest:row.preview_digest,changes:FIELDS.filter(f=>(baseline[f]??'')!==gitPolicy[f]).map(field=>({field,before:baseline[field]??'',after:gitPolicy[field]}))};
 }
 const revision=rows[0]?.revision??0;
 return {projectId:p.id,projectName:p.name,revision,status:draft?'DRAFT':active?'CONFIRMED':legacy?'LEGACY':'UNCONFIGURED',active,draft,legacy,
 planningPrompt:`請為專案「${p.name}」${active?'檢查或修改':'規劃'} Git 流程。先讀專案文件與已確認設定，列出依據，只詢問缺項與矛盾；完整摘要確認後，透過獨立核准工具保存，不自動合併或發布。`,
 approvalPayload:draft?.readyForConfirmation?{kind:'CONFIRM_PROJECT_WORKFLOW',projectId:p.id,draftId:draft.version,expectedRevision:revision,previewDigest:draft.previewDigest,approved:true}:null};
}
export async function prepareWorkflow(s,a) {
 const p=await s.namedProject(a.projectName);s.active(p);
 require(text(s.trustedActor)&&s.trustedActor.length<=100,'缺少可信管理身分');
 const summary=await workflowSummary(s,p);
 require(Number.isSafeInteger(a.expectedRevision)&&a.expectedRevision===summary.revision,'專案流程已更新，請重新讀取');
 require(a.gitPolicy==null || typeof a.gitPolicy==='object'&&!Array.isArray(a.gitPolicy),'gitPolicy 必須是物件');
 const basis=summary.draft??summary.active??summary.legacy,gitPolicy=policy(basis?.gitPolicy);
 for(const [field,value] of Object.entries(a.gitPolicy??{})) {
  require(FIELDS.includes(field),'Git 流程欄位不合法');if(value==null)continue;
  require(typeof value==='string'&&value.trim().length<=2000,'Git 流程欄位不合法');gitPolicy[field]=value.trim();
 }
 let sources=structuredClone(a.sources??basis?.sources??[]);
 const questions=a.questions??basis?.questions??[];
 require(Array.isArray(sources)&&Array.isArray(questions),'來源與問題必須是陣列');
 for(const field of FIELDS) if(a.gitPolicy?.[field]!=null && (basis?.gitPolicy?.[field]??'')!==gitPolicy[field]) {
  const fresh=a.sources?.some(e=>e&&e.field===field)??false;
  if(!fresh) {
   sources=sources.filter(e=>e?.field!==field);
   if(text(gitPolicy[field])) sources.push({field,kind:'SUGGESTION',ref:'',version:'',detail:'代理提出，等待需求負責人確認'});
  }
 }
 require(sources.length<=100&&questions.length<=50,'來源或問題過多');
 sources=sources.map(normalizeSource);
 const cleanQuestions=questions.map(normalizeQuestion);
 require(!gitPolicy.doneWhen||['ACCEPTED','INTEGRATED'].includes(gitPolicy.doneWhen),'DONE 時機不合法');
 const version=summary.revision+1,baseVersion=summary.active?.version??0;
 const previewDigest=await digest(JSON.stringify([p.id,version,baseVersion,...FIELDS.map(f=>gitPolicy[f]),sources.map(e=>[e.field,e.kind,e.ref,e.version,e.detail]),cleanQuestions.map(q=>[q.field,q.question])]));
 const body={gitPolicy,sources,questions:cleanQuestions};
 await s.commit([s.stmt("INSERT INTO project_workflow_revision(project_id,revision,state,base_revision,proposal_json,preview_digest,actor) VALUES(?,?,'DRAFT',?,?,?,?)",p.id,version,baseVersion,JSON.stringify(body),previewDigest,s.trustedActor),s.event('project.workflow_changed',{projectId:p.id,revision:version})]);
 return workflowSummary(s,p);
}
export async function confirmWorkflow(s,a) {
 const key=await verifyAcceptanceSignature(s,a.actor,a.payload,a.signature),body=JSON.parse(a.payload);
 require(body&&body.kind==='CONFIRM_PROJECT_WORKFLOW'&&body.approved===true&&text(body.approvalRef)&&body.approvalRef.trim().length<=1000,'確認封包不合法');
 const p=await s.project(body.projectId);s.active(p);
 require(key.projectIds.map(String).includes(String(p.id))&&key.permissions.includes('PROJECT_POLICY'),'簽署者沒有專案流程核准權限');
 const summary=await workflowSummary(s,p),draft=summary.draft;
 require(draft&&body.expectedRevision===summary.revision&&body.draftId===draft.version&&body.previewDigest===draft.previewDigest,'草稿或確認版本不符');
 require(draft.readyForConfirmation,'仍有缺項或待決問題');
 const version=summary.revision+1,confirmed={gitPolicy:draft.gitPolicy,sources:draft.sources,questions:draft.questions};
 await s.commit([s.stmt("INSERT INTO project_workflow_revision(project_id,revision,state,base_revision,proposal_json,preview_digest,actor,approval_ref) VALUES(?,?,'CONFIRMED',?,?,?,?,?)",p.id,version,draft.baseVersion,JSON.stringify(confirmed),draft.previewDigest,a.actor,body.approvalRef.trim()),s.event('project.workflow_changed',{projectId:p.id,revision:version})]);
 return workflowSummary(s,p);
}
