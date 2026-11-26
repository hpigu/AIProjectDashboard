import test from 'node:test';
import assert from 'node:assert/strict';
import { createHandler } from '../src/index.js';
import { handleMcp } from '../src/mcp.js';
const request = (name,args) => new Request('https://mcp.example.test/mcp',{method:'POST',headers:{'Content-Type':'application/json',Accept:'application/json, text/event-stream'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:args}})});
const management = {
 prepare_project_workflow:{projectName:"project",expectedRevision:0},
 create_project:{name:'project'},create_tasks:{projectId:1,tasks:[{title:'task',description:'',category:'BACKEND',dependsOnIndexes:[],dependsOnTaskIds:[]}]},
 reset_task_claim:{taskId:1},update_task_details:{taskId:1,title:'edited',expectedVersion:1},set_task_dependencies:{taskId:1,prerequisiteTaskIds:[],expectedVersion:1},
 preview_archive_project:{projectName:'project'},archive_project:{projectName:'project',reason:'approved'},restore_project:{projectName:'project',reason:'approved'},upsert_role:{name:'qa',instructions:'approved'}
};
test('all management tools deny by default before reaching the domain service',async()=>{
 let calls=0; const service={execute:async()=>{calls++;return {ok:true};}};
 for(const [name,args] of Object.entries(management)) {
  const body=await (await handleMcp(request(name,args),service)).json(); assert.equal(body.result.isError,true,name);
 }
 assert.equal(calls,0);
 const forged=await (await handleMcp(request('create_project',{name:'project',deviceId:'admin'}),service)).json();
 assert.equal(forged.error.code,-32602); assert.equal(calls,0);
});
test('management uses the authenticated device allowlist and fails closed for absent or malformed configuration',async()=>{
 let calls=0; const handler=createHandler({authenticate:async()=>({deviceId:'worker'}),serviceFactory:()=>({execute:async()=>{calls++;return {ok:true};}})});
 for(const config of [undefined,'not-json','{}','[null]','[]','["admin"]','["worker",4]']) {
  const body=await (await handler(request('create_project',{name:'project'}),{BOARD_MODE:'mcp',MANAGEMENT_DEVICE_IDS:config})).json(); assert.equal(body.result.isError,true);
 }
 assert.equal(calls,0);
 const allowed=await (await handler(request('create_project',{name:'project'}),{BOARD_MODE:'mcp',MANAGEMENT_DEVICE_IDS:'["worker"]'})).json();
 assert.notEqual(allowed.result.isError,true); assert.equal(calls,1);
});
test('signed acceptance and claim-token lifecycle tools do not require management rights',async()=>{
 let calls=0; const service={execute:async()=>{calls++;return {ok:true};}};
 for(const [name,args] of Object.entries({record_acceptance_event:{actor:'owner',payload:'{}',signature:'0'.repeat(64)},get_acceptance:{taskId:1},claim_next_task:{projectName:'p',category:'BACKEND',assignee:'worker'},report_implementation:{taskId:1,claimToken:'claim',summary:'self report',verificationResults:[{name:'test',result:'PASSED',detail:''}]}})) {
  const body=await (await handleMcp(request(name,args),service)).json(); assert.notEqual(body.result.isError,true);
 }
 assert.equal(calls,4);
});

test('management audit records only authenticated identity and authorization attempt before invoking service',async()=>{
 const audit=[]; const handler=createHandler({authenticate:async()=>({deviceId:'leader-device'}),serviceFactory:()=>({
  auditManagement:async(...event)=>audit.push(event), execute:async()=>({ok:true})
 })});
 await handler(request('create_project',{name:'fixture'}),{BOARD_MODE:'mcp',MANAGEMENT_DEVICE_IDS:'["leader-device"]'});
 await handler(request('create_project',{name:'fixture'}),{BOARD_MODE:'mcp',MANAGEMENT_DEVICE_IDS:'[]'});
 assert.deepEqual(audit,[['leader-device','create_project',true],['unverified-caller','create_project',false]]);
});

test('workflow preparation receives authenticated transport principal, never an argument-supplied identity',async()=>{
 const captured=[];const service={auditManagement:async()=>{},execute:async(...args)=>{captured.push(args);return {ok:true};}};
 await handleMcp(request('prepare_project_workflow',{projectName:'p',expectedRevision:0}),service,{managementAuthorized:true,managementActor:'verified-leader'});
 assert.deepEqual(captured[0][2],{managementActor:'verified-leader'});
 const spoof=await(await handleMcp(request('prepare_project_workflow',{projectName:'p',expectedRevision:0,managementActor:'forged'}),service,{managementAuthorized:true,managementActor:'verified-leader'})).json();
 assert.equal(spoof.error.code,-32602);assert.equal(captured.length,1);
 const signed=await(await handleMcp(request('confirm_project_workflow',{actor:'owner',payload:'{}',signature:'0'.repeat(64)}),service)).json();
 assert.notEqual(signed.result.isError,true);assert.deepEqual(captured[1][2],{managementActor:undefined});
});
