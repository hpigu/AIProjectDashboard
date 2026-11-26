// Test-only identities: separate owner, runner and reviewer keys; never production credentials.
import { createHmac, randomUUID } from 'node:crypto';
export const authorities = Object.fromEntries([
 ['fixture-owner',['PROJECT_POLICY','CONTRACT','EXCEPTION','INTEGRATION','DISPATCH','RELEASE']],
 ['fixture-runner',['RUN']], ['fixture-reviewer',['REVIEW']]
].map(([actor,permissions]) => [actor,{ secret: actor.repeat(4), projectIds: Array.from({length:1000},(_,i)=>String(i+1)), permissions, runnerProfiles:['isolated-browser'] }]));
export const CODE_REF = 'a'.repeat(40);
export const env = { BOARD_ACCEPTANCE_AUTHORITIES: JSON.stringify(authorities) };
export const signed = (actor, event) => { const payload = JSON.stringify(event); return {actor,payload,signature:createHmac('sha256',authorities[actor].secret).update(payload).digest('hex')}; };
export const gitPolicy = {baseBranch:'fixture/base',integrationTarget:'fixture/integration',reviewPolicy:'independent',mergeAuthority:'owner',pullRequestPolicy:'local fixture only',requiredChecks:'fixture-browser',doneWhen:'ACCEPTED'};
export async function attest(execute, task, codeRef=CODE_REF) {
 let summary = await execute('get_acceptance',{taskId:task.id});
 const send = async (actor,kind,body) => {
  const event={eventId:randomUUID(),taskId:task.id,projectId:task.projectId,expectedRevision:summary.revision,kind,body};
  summary = await execute('record_acceptance_event',signed(actor,event)); return event.eventId;
 };
 await send('fixture-owner','PROJECT_POLICY',{gitPolicy});
 const contractId = await send('fixture-owner','CONTRACT',{browserApplicable:false,browserExemptionReason:'Backend protocol fixture has no user interface; signed owner applicability decision',sourceRef:'fixture-requirement',sourceVersion:'v1',codeRef,taskDigest:summary.taskDigest,flowRef:'fixture-flow',flowVersion:'v1',implementationActor:task.assignee ?? 'worker',questions:[],assumptions:[],requirements:[{id:'R1',state:'CONFIRMED',acceptance:'Data persists after refresh and subsequent workflow consumes it'}],cases:[{id:'C1',kind:'API',scenario:'NORMAL',requirementIds:['R1']}],gitPolicy});
 await send('fixture-runner','RUN',{contractId,codeRef,caseId:'C1',runId:randomUUID(),runnerProfile:'isolated-browser',result:'PASSED',artifactUri:'fixture://browser-log',artifactSha256:'a'.repeat(64),steps:'Create data; refresh; use in next step',actual:'Persisted values and subsequent workflow match expectations',environment:'ISOLATED'});
 await send('fixture-reviewer','REVIEW',{contractId,codeRef,subjectDigest:summary.subjectDigest,decision:'APPROVED',reason:'Independent fixture verification'});
 return {summary,send,contractId};
}
