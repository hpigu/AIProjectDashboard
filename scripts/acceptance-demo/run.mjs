// Fixed, trusted local fixture. Never accepts a command or project code to execute.
import { createServer } from 'node:http';
import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes, createHash, createHmac } from 'node:crypto';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { setTimeout as sleep } from 'node:timers/promises';
import { BoardService } from '../../workers/src/service.js';
import { handleMcp } from '../../workers/src/mcp.js';
const root = fileURLToPath(new URL('../../', import.meta.url));
const output = mkdtempSync(join(tmpdir(), 'board-acceptance-demo-'));
const hash = s => createHash('sha256').update(s).digest('hex');
const codeRef = hash(readFileSync(fileURLToPath(import.meta.url)));
const sourceVersion = hash(readFileSync(join(root,'docs/acceptance-demo-flow.md')));
const mechanismFiles=['workers/src/acceptance.js','workers/src/service.js','workers/src/mcp.js','src/main/resources/static/app.js','src/main/resources/static/i18n.js'];
const mechanismHashes=Object.fromEntries(mechanismFiles.map(name=>[name,hash(readFileSync(join(root,name)))]));
const db = new DatabaseSync(join(output, 'fixture.sqlite'));
for (const name of readdirSync(join(root,'workers/migrations')).filter(n => n.endsWith('.sql')).sort()) db.exec(readFileSync(join(root,'workers/migrations', name),'utf8'));
db.exec('CREATE TABLE demo_user(name TEXT PRIMARY KEY, role TEXT NOT NULL)');
const adapter = {
  prepare(sql) { return { bind(...args) { return {
    async first() { return db.prepare(sql).get(...args) ?? null; },
    async all() { return { results: db.prepare(sql).all(...args) }; },
    async run() { const r=db.prepare(sql).run(...args); return {meta:{changes:Number(r.changes),last_row_id:Number(r.lastInsertRowid)}}; }
  }; }}; },
  async batch(statements) { db.exec('BEGIN'); try { const r=[]; for(const s of statements) r.push(await s.run()); db.exec('COMMIT'); return r; } catch(e) { db.exec('ROLLBACK'); throw e; } }
};
// Random per-run keys stay in this supervisor; never enter the page or tested app.
const keys = Object.fromEntries(['owner','runner','reviewer'].map(a=>[a,randomBytes(32).toString('hex')]));
const authorities = {
  owner:{secret:keys.owner,projectIds:[1],permissions:['PROJECT_POLICY','CONTRACT','EXCEPTION','DISPATCH']},
  runner:{secret:keys.runner,projectIds:[1],permissions:['RUN'],runnerProfiles:['fixed-demo-v1']},
  reviewer:{secret:keys.reviewer,projectIds:[1],permissions:['REVIEW']}
};
const service = new BoardService(adapter,{BOARD_ACCEPTANCE_AUTHORITIES:JSON.stringify(authorities)});
const flow = `<!doctype html><html lang="zh-Hant"><meta charset="utf-8"><title>隔離使用者流程</title>
<h1>隔離測試：使用者與權限</h1><form id="create"><label>帳號<input id="name" required maxlength="20"></label><button>新增使用者</button></form>
<p id="result"></p><button id="assign">指派編輯角色</button><button id="login">以新帳號登入</button><button id="write">新增工作紀錄</button><button id="delete">刪除系統設定（應拒絕）</button><pre id="state"></pre>
<script>
let current=localStorage.getItem('demo-current')||'',user=localStorage.getItem('demo-user')||'',records=0;
async function api(action,body={}){const r=await fetch('/fixture/api',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action,user,...body})});const b=await r.json();document.querySelector('#result').textContent=r.status+':'+b.message; await refresh();}
async function refresh(){document.querySelector('#state').textContent=JSON.stringify(await (await fetch('/fixture/state?user='+encodeURIComponent(user))).json());}
document.querySelector('#create').onsubmit=e=>{e.preventDefault();user=document.querySelector('#name').value;localStorage.setItem('demo-user',user);api('create');};
document.querySelector('#assign').onclick=()=>api('assign'); document.querySelector('#login').onclick=()=>{current=user;localStorage.setItem('demo-current',current);api('login');};
document.querySelector('#write').onclick=()=>api('write',{current});document.querySelector('#delete').onclick=()=>api('delete',{current});refresh();
</script></html>`;
let writes=0;
const server = createServer(async (req,res) => {
  try {
    const url=new URL(req.url,'http://127.0.0.1'); let response;
    const bytes=[];for await(const b of req)bytes.push(b); const body=Buffer.concat(bytes).toString();
    if(url.pathname==='/fixture') response=new Response(flow,{headers:{'Content-Type':'text/html;charset=utf-8'}});
    else if(url.pathname==='/fixture/state') response=Response.json({user:db.prepare('SELECT * FROM demo_user WHERE name=?').get(url.searchParams.get('user'))??null,writes});
    else if(url.pathname==='/fixture/api') {
      const a=JSON.parse(body); let status=200,message='OK';
      if(a.action==='create') {
        if(!/^[a-z][a-z0-9]{0,19}$/.test(a.user)) {status=400;message='帳號格式錯誤';}
        else if(db.prepare('SELECT name FROM demo_user WHERE name=?').get(a.user)){status=409;message='帳號已存在';}
        else {db.prepare('INSERT INTO demo_user VALUES(?,?)').run(a.user,'reader');message='已儲存';}
      } else if(a.action==='assign') {db.prepare('UPDATE demo_user SET role=? WHERE name=?').run('editor',a.user);}
      else if(a.action==='login') {if(!db.prepare('SELECT name FROM demo_user WHERE name=?').get(a.user)){status=403;message='拒絕登入';}}
      else if(a.action==='write') {
        if(db.prepare('SELECT role FROM demo_user WHERE name=?').get(a.current)?.role!=='editor'){status=403;message='拒絕操作';}else writes++;
      } else if(a.action==='delete') {status=403;message='禁止刪除';} else {status=400;message='未知操作';}
      response=Response.json({message},{status});
    } else if(url.pathname==='/mcp') response=await handleMcp(new Request('http://127.0.0.1/mcp',{method:req.method,headers:req.headers,body:body||undefined}),service,{managementAuthorized:true,managementActor:'fixed-demo-supervisor'});
    else if(req.method!=='GET') response=new Response('Read only',{status:405});
    else if(url.pathname==='/api/projects') response=Response.json(await service.listProjects(url.searchParams));
    else if(url.pathname==='/api/runtime-config') response=Response.json({});
    else if(url.pathname==='/api/events') {res.writeHead(200,{'Content-Type':'text/event-stream'});res.write(': fixture\n\n'); req.on('close',()=>res.end());return;}
    else {
      const m=/^\/api\/projects\/(\d+)\/(board|dependencies|tasks\/(\d+)(\/acceptance)?)$/.exec(url.pathname);
      if(m) response=Response.json(m[2]==='board'?await service.getBoard(+m[1]):m[2]==='dependencies'?await service.getDependencies(+m[1]):m[4]?await service.getAcceptance(+m[1],+m[3]):await service.getTaskDetail(+m[1],+m[3]));
      else {
        const path=resolve(root,'src/main/resources/static',url.pathname==='/'?'index.html':'.'+url.pathname);
        assert.ok(path.startsWith(join(root,'src/main/resources/static')+'/'));
        response=new Response(readFileSync(path),{headers:{'Content-Type':{'.html':'text/html','.js':'application/javascript','.css':'text/css','.woff2':'font/woff2'}[extname(path)]||'application/octet-stream'}});
      }
    }
    res.writeHead(response.status,Object.fromEntries(response.headers));res.end(Buffer.from(await response.arrayBuffer()));
  } catch(e) {res.writeHead(500);res.end(e.message);}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const base=`http://127.0.0.1:${server.address().port}`;
const log=[];
const record=(name,actual)=>{log.push({name,actual,at:new Date().toISOString()});console.log(name+': '+actual);};
async function call(name,args) {
  const r=await fetch(base+'/mcp',{method:'POST',headers:{'Content-Type':'application/json',Accept:'application/json, text/event-stream'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:args}})});
  const result=(await r.json()).result;
  if(result?.isError)throw new Error(result.content[0].text);
  return JSON.parse(result.content[0].text);
}
let chrome,ws;
try {
  const p=await call('create_project',{name:'隔離驗收範例'});assert.equal(p.project.id,1);
  await call('create_tasks',{projectId:1,tasks:[{title:'新增使用者並驗證角色權限',description:'依固定流程規格驗證資料持久化與禁止操作',category:'BACKEND',dependsOnIndexes:[],dependsOnTaskIds:[]}]});
  const claimed=await call('claim_next_task',{projectName:'隔離驗收範例',category:'BACKEND',assignee:'developer'});const taskId=claimed.task.id;
  const complete=()=>call('complete_task',{taskId,claimToken:claimed.claimToken,summary:'實作完成聲明',commitRef:codeRef,verificationResults:[{name:'代理自稱通過',result:'PASSED',detail:'此聲明不能取代受控證據'}]});
  async function rejected(name,fn){await assert.rejects(fn);record(name,'REJECTED as required');}
  await rejected('僅代理 PASSED 不可完成',complete);
  let n=0;
  async function sign(actor,kind,body,overrides={}){
    const a=await call('get_acceptance',{taskId});const event={eventId:`demo-${++n}`,projectId:1,taskId,expectedRevision:a.revision,kind,body,...overrides};const payload=JSON.stringify(event);
    return call('record_acceptance_event',{actor,payload,signature:createHmac('sha256',keys[actor]).update(payload).digest('hex')});
  }
  const gitPolicy={baseBranch:'fixture-start',integrationTarget:'fixture-review',reviewPolicy:'independent reviewer',mergeAuthority:'human',pullRequestPolicy:'no merge in this demo',requiredChecks:'seven browser scenarios',doneWhen:'ACCEPTED'};
  await sign('owner','PROJECT_POLICY',{gitPolicy});
  const a=await call('get_acceptance',{taskId});
  const scenarios=['NORMAL','ERROR','BOUNDARY','PERMISSION','FAILURE','PERSISTENCE','DOWNSTREAM'];
  const contract={sourceRef:'docs/acceptance-demo-flow.md',sourceVersion,codeRef,taskDigest:a.taskDigest,flowRef:'docs/acceptance-demo-flow.md',flowVersion:sourceVersion,implementationActor:'developer',questions:[],assumptions:[],requirements:[{id:'R1',state:'CONFIRMED',acceptance:'新增與角色設定確實儲存，重新整理後仍正確；editor可新增紀錄且不可刪系統設定。'}],cases:scenarios.map(s=>({id:s,kind:'BROWSER',scenario:s,requirementIds:['R1']})),gitPolicy};
  await sign('owner','CONTRACT',{...contract,questions:['故意留未決業務問題']});await rejected('未決需求不能完成',complete);
  const accepted=await sign('owner','CONTRACT',contract);const contractId=accepted.events.filter(e=>e.kind==='CONTRACT').at(-1).eventId;
  await rejected('無證據不能完成',complete);
  await rejected('未授權角色不能自稱受控執行器',()=>sign('owner','RUN',{contractId,codeRef}));
  const forgedPayload=JSON.stringify({eventId:'forged',projectId:1,taskId,expectedRevision:accepted.revision,kind:'RUN',body:{}});
  await rejected('偽簽章被擋',()=>call('record_acceptance_event',{actor:'runner',payload:forgedPayload,signature:'0'.repeat(64)}));
  // Only launch known browser code, with a fresh profile and no credentials from this process.
  const executable=process.env.CHROME_BIN||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
  chrome=spawn(executable,['--headless=new',`--user-data-dir=${join(output,'chrome')}`,'--remote-debugging-port=0','--no-first-run','--no-default-browser-check','--disable-background-networking','--disable-component-update','--no-proxy-server','about:blank'],{env:{PATH:process.env.PATH,HOME:output,TMPDIR:tmpdir()},stdio:'ignore'});
  const portFile=join(output,'chrome/DevToolsActivePort');for(let i=0;i<150&&!existsSync(portFile);i++)await sleep(100);assert.ok(existsSync(portFile),'browser started');
  const port=readFileSync(portFile,'utf8').split('\n')[0];const target=await(await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,{method:'PUT'})).json();
  ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});let sequence=0;const pending=new Map();
  ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id&&pending.has(m.id)){const {resolve,reject}=pending.get(m.id);pending.delete(m.id);m.error?reject(Error(JSON.stringify(m.error))):resolve(m.result);}};
  const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout: '+method));},15000);pending.set(id,{resolve:r=>{clearTimeout(timer);resolve(r);},reject:e=>{clearTimeout(timer);reject(e);}});ws.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
  async function until(expression){for(let i=0;i<100;i++){if(await evaluate(expression))return;await sleep(100);}throw Error('browser assertion timeout: '+expression);}
  async function click(selector){const p=await evaluate(`(()=>{let r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`);await send('Input.dispatchMouseEvent',{type:'mousePressed',button:'left',clickCount:1,...p});await send('Input.dispatchMouseEvent',{type:'mouseReleased',button:'left',clickCount:1,...p});}
  await send('Page.enable');await send('Page.navigate',{url:base+'/fixture'});await until("!!document.querySelector('#name')");
  await click('#name');await send('Input.insertText',{text:'qauser'});await click('#create button');await until("document.querySelector('#state')?.textContent.includes('reader')");record('NORMAL','user stored with reader role');
  await click('#create button');await until("document.querySelector('#result')?.textContent.startsWith('409')");record('ERROR','duplicate rejected; original row unchanged');
  await evaluate("document.querySelector('#name').value='bad!'");await click('#create button');await until("document.querySelector('#result')?.textContent.startsWith('400')");assert.equal(db.prepare('SELECT count(*) n FROM demo_user').get().n,1);record('BOUNDARY','invalid name rejected without extra row');
  await evaluate("document.querySelector('#name').value='qauser'; localStorage.setItem('demo-user','qauser')");
  await send('Page.reload');await until("document.querySelector('#state')?.textContent.includes('reader')");
  await click('#login');await until("document.querySelector('#result')?.textContent.startsWith('200')");await click('#write');await until("document.querySelector('#result')?.textContent.startsWith('403')");assert.equal(writes,0);record('FAILURE','reader write denied at backend; no side effects');
  await click('#assign');await until("document.querySelector('#state')?.textContent.includes('editor')");await send('Page.reload');await until("document.querySelector('#state')?.textContent.includes('editor')");assert.equal(db.prepare('SELECT role FROM demo_user WHERE name=?').get('qauser').role,'editor');record('PERSISTENCE','role persisted in SQLite and survives reload');
  await click('#login');await click('#write');await until("document.querySelector('#state')?.textContent.includes('"+'"writes":1'+"')");assert.equal(writes,1);record('DOWNSTREAM','new account can use editor workflow');
  await click('#delete');await until("document.querySelector('#result')?.textContent.startsWith('403')");assert.equal(writes,1);record('PERMISSION','forbidden operation returns 403 and leaves data unchanged');
  const screenshot=await send('Page.captureScreenshot',{format:'png'});writeFileSync(join(output,'browser.png'),Buffer.from(screenshot.data,'base64'));
  const report={codeRef,sourceVersion,mechanismHashes,environment:'ISOLATED',source:'fixed browser fixture, real Chrome via CDP',results:log};const reportFile=join(output,'execution.json');writeFileSync(reportFile,JSON.stringify(report,null,2));
  const run={contractId,codeRef,runId:'local-fixed-demo',result:'PASSED',artifactUri:reportFile,artifactSha256:hash(readFileSync(reportFile)),steps:'node scripts/acceptance-demo/run.mjs; see execution.json seven scenarios',actual:'Browser DOM + server SQLite + denied HTTP results asserted',environment:'ISOLATED',runnerProfile:'fixed-demo-v1'};
  await rejected('錯誤受測版本拒絕',()=>sign('runner','RUN',{...run,caseId:'NORMAL',codeRef:'wrong'}));
  await sign('runner','RUN',{...run,caseId:'NORMAL',result:'FAILED',actual:'deliberate failing receipt'});await rejected('必要驗收失敗不可完成',complete);
  await sign('runner','RUN',{...run,caseId:'NORMAL'});await rejected('其餘案例漏驗不可完成',complete);
  for(const caseId of scenarios.slice(1))await sign('runner','RUN',{...run,caseId});
  await rejected('缺少獨立審查不可完成',complete);
  const before=await call('get_acceptance',{taskId});
  const reviewRequest={codeRef,sourceVersion,mechanismHashes,subjectDigest:before.subjectDigest,executionReport:reportFile,sourceFile:fileURLToPath(import.meta.url),decisionFile:join(output,'independent-review.json')};
  writeFileSync(join(output,'review-request.json'),JSON.stringify(reviewRequest,null,2));
  if(process.argv.includes('--independent-review')) {
    console.log('INDEPENDENT_REVIEW_REQUEST '+join(output,'review-request.json'));
    for(let i=0;i<1800&&!existsSync(reviewRequest.decisionFile);i++)await sleep(100);
    assert.ok(existsSync(reviewRequest.decisionFile),'independent review delivered');
    const review=JSON.parse(readFileSync(reviewRequest.decisionFile,'utf8'));
    assert.equal(review.codeRef,codeRef);assert.equal(review.subjectDigest,before.subjectDigest);assert.equal(review.decision,'APPROVED');assert.ok(review.reason);
    record('live independent review',review.reason);
  }
  await sign('reviewer','REVIEW',{contractId,codeRef,subjectDigest:before.subjectDigest,decision:'APPROVED',reason:'固定範例規格、七項實際斷言與資料庫結果一致；只核准此fixture版本'});
  await complete();record('DONE gate','accepted after signed execution and independent authority review');
  await send('Page.navigate',{url:base});await until("document.querySelector('.project-row')");await click('.project-row');await until("document.querySelector('.task-card')");await click('.task-card');await until("document.querySelector('.detail-acceptance')?.textContent.includes('NORMAL')");
  await evaluate("document.querySelector('.detail-acceptance').scrollIntoView({block:'start'})");
  const boardShot=await send('Page.captureScreenshot',{format:'png'});writeFileSync(join(output,'board.png'),Buffer.from(boardShot.data,'base64'));
  const displayed=await evaluate("document.querySelector('.detail-acceptance').textContent");
  for(const caseId of scenarios)assert.ok(displayed.includes(caseId));assert.ok(displayed.includes('R1'));assert.equal((await call('get_acceptance',{taskId})).canClose,true);
  writeFileSync(join(output,'result.json'),JSON.stringify({status:'PASSED',codeRef,output,checks:log,scope:process.argv.includes('--independent-review')?'Real Chrome + SQLite + HTTP MCP with separate live agent review; no company/CI/arbitrary-code sandbox validation':'Real Chrome + SQLite + HTTP MCP multi-client model with fixed review authority; no live LLM agents, company, external CI or arbitrary-code sandbox validation'},null,2));
  console.log(JSON.stringify({status:'PASSED',output,checks:log.length,codeRef}));
} finally {
  ws?.close();if(chrome?.pid)chrome.kill('SIGTERM');server.closeAllConnections();await new Promise(r=>server.close(r));db.close();
}
