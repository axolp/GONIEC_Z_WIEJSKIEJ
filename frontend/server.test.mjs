import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from './server.mjs';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
async function request(server, url, method='GET') {
  let code, headers={}, body;
  const res = {setHeader(k,v){headers[k]=v;},writeHead(c,h){code=c;Object.assign(headers,h);},end(data){body=data;}};
  await server.listeners('request')[0]({url,method,headers:{}},res);
  return {code,headers,body:body?.toString(),json:()=>JSON.parse(body)};
}
test('offline: seed list, detail, status, sync refusal and missing project', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(),'sejm-'));
  const original = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error('offline'); };
  try {
    const seedPath=path.join(dir,'seed.json');
    await writeFile(seedPath,JSON.stringify({bills:[{id:'10-3355',title:'Test'}]}));
    const server=createServer({seedPath});
    const list=await request(server,'/api/bills'); assert.equal(list.code,200); assert.equal(list.json().demo,true); assert.equal(list.json().bills[0].id,'10-3355');
    assert.equal((await request(server,'/api/bills/10-3355')).json().title,'Test');
    assert.equal((await request(server,'/api/bills/missing')).code,404);
    const status=(await request(server,'/api/status')).json(); assert.equal(status.demo,true); assert.equal(status.lastSuccessAt,null); assert.equal(status.syncing,false);
    const sync=await request(server,'/api/sync','POST');assert.equal(sync.code,503);assert.match(sync.json().error,/Nie można uruchomić synchronizacji/);
    assert.equal((await request(server,'/api/bills/10-3355/pdf')).code,404);
    await writeFile(seedPath,'broken'); assert.equal((await request(server,'/api/bills')).code,503);
  } finally {globalThis.fetch=original;await rm(dir,{recursive:true,force:true});}
});
test('proxy preserves path, query, method, body and upstream errors without demo substitution',async()=>{
  const original=globalThis.fetch; const calls=[];
  globalThis.fetch=async(url,options)=>{calls.push({url:String(url),options});return new Response(JSON.stringify({error:'Backend error'}),{status:422,headers:{'Content-Type':'application/json','X-Test':'yes'}});};
  try {
    const server=createServer({backend:'http://backend:8080'});
    const result=await request(server,'/api/bills?q=atom');assert.equal(result.code,422);assert.equal(result.json().error,'Backend error');assert.equal(calls[0].url,'http://backend:8080/api/bills?q=atom');assert.equal(result.headers['x-test'],'yes');
    await request(server,'/api/sync','POST');assert.equal(calls[1].options.method,'POST');assert.ok(calls[1].options.body);
  } finally {globalThis.fetch=original;}
});
test('static assets and traversal protection',async()=>{
 const server=createServer();
 for(const url of ['/','/app.js','/styles.css'])assert.equal((await request(server,url)).code,200);
 assert.equal((await request(server,'/package.json')).code,404);
 assert.equal((await request(server,'/../data/seed-bills.json')).code,404);
 assert.equal((await request(server,'/','POST')).code,405);
 assert.equal((await request(server,'/','HEAD')).body,undefined);
});
test('BACKEND_URL configures default upstream',async()=>{
 const old=process.env.BACKEND_URL,original=globalThis.fetch;process.env.BACKEND_URL='http://backend:8080';
 globalThis.fetch=async url=>{assert.equal(String(url),'http://backend:8080/api/status');return new Response('{}');};
 try{assert.equal((await request(createServer(),'/api/status')).code,200);}finally{globalThis.fetch=original;if(old===undefined)delete process.env.BACKEND_URL;else process.env.BACKEND_URL=old;}
});
test('upstream body failure falls back before writing response headers',async()=>{
 const original=globalThis.fetch;
 globalThis.fetch=async()=>({status:200,headers:new Headers(),arrayBuffer:async()=>{throw new Error('connection reset');}});
 try {
   const server=createServer();let writes=0;let result;
   await server.listeners('request')[0]({url:'/api/status',method:'GET',headers:{}},{writeHead(code){writes++;assert.equal(writes,1);assert.equal(code,200);},end(body){result=JSON.parse(body);}});
   assert.equal(result.demo,true);assert.equal(result.lastSuccessAt,null);
 }finally{globalThis.fetch=original;}
});

test('presidential snapshot remains available offline without changing bill analysis', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error('offline'); };
  try {
    const server = createServer();
    const result = await request(server, '/api/presidential-comments');
    assert.equal(result.code, 200);
    assert.equal(result.json().mode, 'snapshot');
    assert.equal(result.json().demo, true);
    assert.equal(result.json().comments[0].billId, '10-2693');
    const bills = (await request(server, '/api/bills')).json().bills;
    assert.equal(bills[0].id, '10-3355');
    assert.equal(bills[0].status, 'analyzed');
    assert.equal((await request(server, '/presidential-comments.js')).code, 200);
    const missing = createServer({presidentialPath:'/tmp/simply-sejm-nonexistent-comments.json'});
    assert.equal((await request(missing, '/api/presidential-comments')).code, 503);
  } finally { globalThis.fetch = original; }
});

test('real POC seed fallback exposes both bills and the matching veto without altering summaries', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error('offline'); };
  try {
    const server = createServer();
    const list = await request(server, '/api/bills');
    assert.equal(list.code, 200);
    assert.equal(list.json().demo, true);
    for (const id of ['10-3355','10-2693']) {
      const listed = list.json().bills.find(b => b.id === id);
      assert.ok(listed?.summary?.lead?.trim());
      const detail = await request(server, '/api/bills/' + id);
      assert.equal(detail.code, 200);
      assert.deepEqual(detail.json(), {...listed,demo:true});
      assert.equal(detail.headers['X-Simply-Sejm-Mode'], 'demo');
    }
    const feed = (await request(server, '/api/presidential-comments')).json();
    const veto = feed.comments.find(c => c.billId === '10-2693');
    assert.equal(veto.date, '2026-11-13');
    assert.match(veto.title, /Weto/);
  } finally { globalThis.fetch = original; }
});

test('valid upstream cache remains authoritative even when the POC record is absent', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async url => String(url).endsWith('/api/bills')
    ? new Response(JSON.stringify({bills:[]}), {status:200})
    : new Response(JSON.stringify({detail:'Nie znaleziono projektu',status:404}), {status:404});
  try {
    const server=createServer();
    assert.deepEqual((await request(server,'/api/bills')).json(), {bills:[]});
    assert.equal((await request(server,'/api/bills/10-2693')).code,404);
  } finally { globalThis.fetch=original; }
});

test('votings offline snapshot follows bill mapping and never replaces upstream HTTP errors',async()=>{
 const dir=await mkdtemp(path.join(os.tmpdir(),'sejm-votings-'));
 const votingsPath=path.join(dir,'bill-votings.json');
 const original=globalThis.fetch;
 const snapshot={billId:'10-2693',verifiedAt:'2026-11-15',sourceUrl:'https://www.sejm.gov.pl/',defaultVotingId:'synthetic',mode:'snapshot',stale:true,error:null,votings:[{id:'synthetic',votes:[],clubResults:[{club:'Syntetyczne koło',members:2,totalVoted:1,yes:1,no:0,abstain:0,notParticipating:1}],clubResultsSourceUrl:'https://www.sejm.gov.pl/Sejm10.nsf/Glosowanie.xsp?posiedzenie=1&glosowanie=2',yes:1,no:0,abstain:0,notParticipating:null}]};
 try {
   await writeFile(votingsPath,JSON.stringify({bills:[snapshot]}));
   const server=createServer({votingsPath});
   globalThis.fetch=async()=>{throw new Error('offline');};
   const result=await request(server,'/api/bills/10-2693/votings');assert.equal(result.code,200);assert.deepEqual(result.json(),{...snapshot,demo:true});
   const unmapped=await request(server,'/api/bills/10-3355/votings');
   assert.equal(unmapped.code,200);assert.deepEqual(unmapped.json().votings,[]);assert.equal(unmapped.json().billId,'10-3355');assert.equal(unmapped.json().demo,true);
   assert.equal((await request(server,'/api/bills/unknown/votings')).code,404);
   assert.equal((await request(server,'/votings.js')).code,200);
   for(const code of [404,500]){
     globalThis.fetch=async()=>new Response(JSON.stringify({detail:'Błąd backendu'}),{status:code});
     const response=await request(server,'/api/bills/10-2693/votings');assert.equal(response.code,code);assert.equal(response.json().demo,undefined);
   }
   globalThis.fetch=async()=>{throw new Error('offline');};
   await writeFile(votingsPath,'broken');assert.equal((await request(server,'/api/bills/10-2693/votings')).code,503);
   assert.equal((await request(createServer({votingsPath:path.join(dir,'missing')}),'/api/bills/10-2693/votings')).code,503);
 }finally{globalThis.fetch=original;await rm(dir,{recursive:true,force:true});}
});

test('coordinator snapshot is served with real totals and explicitly absent roll call',async()=>{
 const original=globalThis.fetch;globalThis.fetch=async()=>{throw new Error('offline');};
 try{
   const response=await request(createServer(),'/api/bills/10-2693/votings');
   assert.equal(response.code,200);const data=response.json();
   assert.equal(data.demo,true);assert.equal(data.defaultVotingId,'10-66-103');
   const voting=data.votings.find(v=>v.id===data.defaultVotingId);
   assert.equal(voting.yes,242);assert.equal(voting.no,61);assert.equal(voting.abstain,136);
   assert.equal(voting.notParticipating,21);assert.equal(voting.totalVoted,439);assert.deepEqual(voting.votes,[]);
 }finally{globalThis.fetch=original;}
});

test('paperboy SVG is allowlisted with correct MIME and supports HEAD without leaking other files',async()=>{
 const server=createServer();
 const svg=await request(server,'/assets/paperboy.svg');assert.equal(svg.code,200);
 assert.equal(svg.headers['Content-Type'],'image/svg+xml; charset=utf-8');
 assert.equal(svg.headers['X-Content-Type-Options'],'nosniff');
 assert.match(svg.body,/<svg[^>]+viewBox="0 0 720 560"/);assert.match(svg.body,/Goniec z Wiejskiej/);
 assert.doesNotMatch(svg.body,/<script|<image|https?:\/\/(?!www.w3.org)/);
 const head=await request(server,'/assets/paperboy.svg','HEAD');assert.equal(head.code,200);assert.equal(head.body,undefined);assert.equal(head.headers['Content-Type'],svg.headers['Content-Type']);
 for(const url of ['/assets/','/assets/other.svg','/assets/../package.json'])assert.equal((await request(server,url)).code,404);
 const html=(await request(server,'/')).body;
 assert.match(html,/<title>Goniec z Wiejskiej/);assert.match(html,/src="\/assets\/paperboy.svg"/);
 assert.doesNotMatch(html,/Simply Sejm|simplysejm|simply<strong>/i);
});

test('rejects hostile request targets before fetch or demo fallback; Compose origin and redirects remain pinned',async()=>{
 const original=globalThis.fetch;const calls=[];
 globalThis.fetch=async(url,options)=>{calls.push({url:String(url),options});return new Response(null,{status:302,headers:{location:'http://other.example/api/bills'}});};
 try{
  const server=createServer({backend:'http://backend:8080'});
  for(const target of ['http://evil.example/api/bills','https://evil.example/api/status','//evil.example/api/bills','///evil.example/api/bills','/\\evil.example/api/bills','/api/..\\..\\evil.example/api/bills','/%2f%2fevil.example/api/bills','/%5cevil.example/api/status','/\t/evil.example/api/bills','/api/bills#fragment']){
   const response=await request(server,target);
   assert.equal(response.code,400,target);assert.equal(response.json().demo,undefined);assert.equal(calls.length,0);
  }
  const result=await request(server,'/api/bills?source=https://example.org');
  assert.equal(result.code,302);assert.equal(calls.length,1);
  assert.equal(calls[0].url,'http://backend:8080/api/bills?source=https://example.org');
  assert.equal(calls[0].options.redirect,'manual');
  // Dot-segment normalization cannot replace the configured authority either.
  await request(server,'/api/../api/status');
  assert.equal(new URL(calls[1].url).origin,'http://backend:8080');
 }finally{globalThis.fetch=original;}
});
