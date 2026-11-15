import {test} from 'node:test';
import assert from 'node:assert/strict';
import {aggregateClubs,filterVotes,defaultVoting,renderVoting,renderMembers,mountVotings,loadBillVotings,voteLabel,displayVotingDate,officialVotingUrl} from './votings.js';

// Entirely synthetic fixtures: these are not the results or members of a real vote.
const votes = [
  {MP:1,firstName:'Anna',lastName:'Żak',club:'Historyczny klub A',vote:'YES'},
  {MP:2,firstName:'Jan',lastName:'Kowalski',club:'Historyczny klub A',vote:'NO'},
  {MP:3,firstName:'Ewa',lastName:'Nowak',club:'Klub B',vote:'ABSTAIN'},
  {MP:4,firstName:'Adam',lastName:'Lis',club:'Klub B',vote:'ABSENT'},
  {MP:5,firstName:'Ala',lastName:'Kot',club:'Klub B',vote:'NEW_ENUM'},
];
const voting = {id:'synthetic-final',term:10,sitting:1,votingNumber:2,date:'2026-11-13T12:30:00Z',title:'Syntetyczne głosowanie nad całością',description:'Dokładny syntetyczny przedmiot głosowania',category:'final',sourceUrl:'https://www.sejm.gov.pl/synthetic',pdfUrl:'https://www.sejm.gov.pl/synthetic.pdf',yes:1,no:1,abstain:1,notParticipating:null,totalVoted:3,votes};
const data = {billId:'synthetic',verifiedAt:'2026-11-15',mode:'snapshot',stale:false,error:null,defaultVotingId:voting.id,votings:[{...voting,id:'synthetic-amendment',category:'amendment',title:'Poprawka syntetyczna'},voting]};

test('aggregate preserves historical club and separates unknown votes from nonparticipants',()=>{
  const result=aggregateClubs(votes);
  const a=result.find(r=>r.club==='Historyczny klub A');assert.equal(a.YES,1);assert.equal(a.NO,1);
  const b=result.find(r=>r.club==='Klub B');assert.equal(b.ABSTAIN,1);assert.equal(b.ABSENT,1);assert.equal(b.unknown,1);
  assert.deepEqual(aggregateClubs([]),[]);
  assert.equal(aggregateClubs([{vote:'toString'}])[0].unknown,1);
  assert.match(voteLabel('NEW_ENUM'),/Nieznany głos/);
});
test('surname, historical club and vote filters combine',()=>{
  assert.equal(filterVotes(votes,{name:' ŻA '})[0].MP,1);
  assert.equal(filterVotes(votes,{club:'Klub B',vote:'ABSENT'}).length,1);
  assert.equal(filterVotes(votes,{name:'Żak',club:'Klub B'}).length,0);
  assert.match(renderMembers(votes,{vote:'NEW_ENUM'}),/Nieznany głos/);
});
test('default is explicitly selected final voting and history can show amendments',()=>{
  assert.equal(defaultVoting(data).id,'synthetic-final');
  assert.equal(defaultVoting({...data,defaultVotingId:'missing'}).id,'synthetic-final');
  const html=renderVoting(data);
  assert.match(html,/value="synthetic-final" selected/);
  assert.match(html,/Dokładny syntetyczny przedmiot głosowania/);
  assert.match(html,/13 listopada 2026/);
  assert.match(html,/Głosowanie nad całością/);
  assert.match(html,/Nie głosowali<\/dt><dd>Brak danych/);
  assert.match(renderVoting(data,'synthetic-amendment'),/<h4>Poprawka syntetyczna/);
});
test('snapshot without roll call has explicit absence and no fabricated clubs or zero table',()=>{
  const html=renderVoting({...data,votings:[{...voting,votes:[]}]});
  assert.match(html,/Brak zapisanych głosów imiennych/);
  assert.match(html,/Podział na ugrupowania będzie dostępny po pobraniu danych klubowych lub imiennych z Sejmu/);
  assert.doesNotMatch(html,/<table|Historyczny klub|Głosy imienne posłów/);
  assert.match(html,/Za<\/dt><dd>1/);
  assert.match(renderVoting({...data,votings:[]}),/Brak informacji o głosowaniach/);
});
test('escaping, secure links and stale/error metadata',()=>{
  const container={querySelector(){return null;}};
  mountVotings(container,{...data,demo:true,stale:true,error:'<script>error</script>',votings:[{...voting,title:'<img onerror=bad>',sourceUrl:'javascript:bad',pdfUrl:'https://user:password@example.com/a'}]});
  assert.match(container.innerHTML,/Tryb przykładowy/);assert.match(container.innerHTML,/mogą być nieaktualne/);
  assert.match(container.innerHTML,/&lt;script&gt;/);assert.match(container.innerHTML,/&lt;img/);
  assert.doesNotMatch(container.innerHTML,/<script>|<img|javascript:|password/);
});
test('mounted history and member filters update without resetting the selected vote',()=>{
 const nodes=Object.fromEntries(['[data-voting-select]','[data-member-name]','[data-member-club]','[data-member-vote]','[data-members]'].map(key=>[key,{value:''}]));
 const container={querySelector:key=>nodes[key]};
 mountVotings(container,data);
 nodes['[data-member-name]'].value='Żak';nodes['[data-member-name]'].oninput();
 assert.match(nodes['[data-members]'].innerHTML,/Anna/);assert.doesNotMatch(nodes['[data-members]'].innerHTML,/Kowalski/);
 nodes['[data-voting-select]'].value='synthetic-amendment';nodes['[data-voting-select]'].onchange();
 assert.match(container.innerHTML,/<h4>Poprawka syntetyczna/);
});
test('loading errors are separate, retry works and stale navigation cannot overwrite the current view',async()=>{
 let retry;const container={querySelector:()=>retry={}};let current=true;
 await loadBillVotings({billId:'synthetic',container,fetchData:async()=>{throw new Error('HTTP 500');},isCurrent:()=>current});
 assert.match(container.innerHTML,/Nie udało się pobrać głosowań: HTTP 500/);assert.equal(typeof retry.onclick,'function');
 current=false;const before=container.innerHTML;await retry.onclick();assert.equal(container.innerHTML,before);
 let resolve;current=true;
 const pending=loadBillVotings({billId:'synthetic',container,fetchData:()=>new Promise(r=>resolve=r),isCurrent:()=>current});
 current=false;container.innerHTML='Widok innej ustawy';resolve({...data,votings:[{...voting,clubResults:[{club:'Spóźniony klub',members:1,totalVoted:1,yes:1,no:0,abstain:0,notParticipating:0}],votes:[]}]});await pending;assert.equal(container.innerHTML,'Widok innej ustawy');
 await loadBillVotings({billId:'different',container,fetchData:async()=>data,isCurrent:()=>true});
 assert.match(container.innerHTML,/Niepoprawna odpowiedź/);
});


test('offset-free Sejm wall clock is faithful and independent of browser time zone',()=>{
 const original=process.env.TZ;
 try {
   const results=[];
   for(const zone of ['UTC','America/Los_Angeles','Asia/Tokyo','Europe/Warsaw']) {
     process.env.TZ=zone;
     results.push(displayVotingDate('2026-10-08T20:54:10'));
     assert.match(displayVotingDate('2026-10-08T18:54:10Z'),/20:54.*czas polski/);
     assert.match(displayVotingDate('2026-10-08T20:54:10+02:00'),/20:54.*czas polski/);
   }
   assert.equal(new Set(results).size,1);
   assert.match(results[0],/8 października 2026.*20:54.*czas polski/);
 }finally{if(original===undefined)delete process.env.TZ;else process.env.TZ=original;}
});

test('NO_VOTE stays explicit until its meaning is verified, never merged into ABSENT',()=>{
 const result=aggregateClubs([{club:'Syntetyczny',vote:'NO_VOTE'},{club:'Syntetyczny',vote:'ABSENT'}])[0];
 assert.equal(result.ABSENT,1);assert.equal(result.unknown,1);
 assert.match(voteLabel('NO_VOTE'),/NO_VOTE/);
});
test('manual refresh after snapshot can retrieve roll call, retains data on failure and stops after navigation',async()=>{
 const controls=Object.fromEntries(['[data-votings-refresh]','[data-votings-retry]'].map(key=>[key,{}]));
 const container={querySelector:key=>controls[key] || null};
 let calls=0, fail=false,current=true;
 const fetchData=async()=>{calls++;if(fail)throw new Error('HTTP 500');return calls===1 ? {...data,votings:[{...voting,votes:[]}]} : data;};
 await loadBillVotings({billId:'synthetic',container,fetchData,isCurrent:()=>current});
 assert.match(container.innerHTML,/Brak zapisanych głosów imiennych/);
 assert.match(container.innerHTML,/Odśwież wyniki/);
 await controls['[data-votings-refresh]'].onclick();
 assert.match(container.innerHTML,/Historyczny klub A/);
 fail=true;await controls['[data-votings-refresh]'].onclick();
 assert.match(container.innerHTML,/Pozostawiono poprzednio pobrane dane/);
 assert.match(container.innerHTML,/Historyczny klub A/);
 current=false;await controls['[data-votings-refresh]'].onclick();assert.equal(calls,3);
});


test('club and circle table shows source counts and total including explicit unknown votes',()=>{
 const html=renderVoting(data);
 assert.match(html,/<h4>Jak głosowały kluby i koła\?<\/h4>/);
 assert.match(html,/<th scope="col">Klub \/ koło<\/th>/);
 assert.match(html,/<th scope="col">Łącznie<\/th>/);
 assert.match(html,/<th scope="row">Historyczny klub A<\/th><td>2<\/td><td>2<\/td><td>1<\/td><td>1<\/td><td>0<\/td><td>0<\/td><td>0<\/td>/);
 assert.match(html,/<th scope="row">Klub B<\/th><td>3<\/td><td>1<\/td><td>0<\/td><td>0<\/td><td>1<\/td><td>1<\/td><td>1<\/td>/);
});

// Synthetic club aggregates, not the actual distribution of any parliamentary vote.
const clubResults=[{club:'Syntetyczne koło <A>',members:12,totalVoted:10,yes:7,no:2,abstain:1,notParticipating:2}];
test('official club aggregates work without roll call and never generate member identities',()=>{
 const v={...voting,votes:[],clubResults,clubResultsSourceUrl:'https://www.sejm.gov.pl/Sejm10.nsf/Glosowanie.xsp?posiedzenie=1&glosowanie=2'};
 const html=renderVoting({...data,votings:[v]});
 assert.match(html,/<th scope="col">Członkowie<\/th><th scope="col">Głosowało/);
 assert.match(html,/<th scope="row">Syntetyczne koło &lt;A&gt;<\/th><td>12<\/td><td>10<\/td><td>7<\/td><td>2<\/td><td>1<\/td><td>2<\/td>/);
 assert.match(html,/Brak zapisanych głosów imiennych/);
 assert.match(html,/Źródło podziału klubowego/);
 assert.doesNotMatch(html,/member-list|data-member-name|Anna|Kowalski|<A>|będzie dostępny/);
 assert.equal((html.match(/<table>/g)||[]).length,1);
});
test('official aggregates take precedence without adding roll call counts, which stay separately visible',()=>{
 const html=renderVoting({...data,votings:[{...voting,clubResults}]});
 assert.equal((html.match(/<table>/g)||[]).length,1);
 assert.match(html,/<td>12<\/td><td>10<\/td><td>7<\/td>/);
 assert.doesNotMatch(html,/<th scope="row">Historyczny klub A/);
 assert.match(html,/Anna/);assert.match(html,/Głosy imienne posłów/);
 const old=renderVoting({...data,votings:[{...voting,clubResults:[]}]});
 assert.match(old,/<th scope="row">Historyczny klub A/);
});
test('official HTML links validate identifiers and reject misleading or unsafe supplied URLs',()=>{
 const v={...voting,term:10,sitting:66,votingNumber:103};
 const expected='https://www.sejm.gov.pl/Sejm10.nsf/Glosowanie.xsp?posiedzenie=66&glosowanie=103';
 assert.equal(officialVotingUrl(v),expected);
 for(const clubResultsSourceUrl of ['javascript:alert(1)','https://www.sejm.gov.pl.evil.example/','https://user:password@www.sejm.gov.pl/Sejm10.nsf/Glosowanie.xsp?posiedzenie=66&glosowanie=103','https://www.sejm.gov.pl/Sejm10.nsf/Glosowanie.xsp?posiedzenie=68&glosowanie=40']) {
   assert.equal(officialVotingUrl({...v,clubResultsSourceUrl}),expected);
 }
 for(const sitting of [null,0,-1,1.5,'66', '66" onclick="bad']) assert.equal(officialVotingUrl({...v,sitting}),'');
 const html=renderVoting({...data,votings:[{...v,clubResults:[{...clubResults[0],yes:'<script>bad</script>'}]}]});
 assert.match(html,/posiedzenie=66&amp;glosowanie=103/);
 assert.match(html,/10. kadencja, posiedzenie 66, głosowanie 103/);
 assert.match(html,/Wyniki w PDF/);
 assert.doesNotMatch(html,/<script>|68.*40/);
});

test('history and async refresh preserve selection and focus across actual node replacement without stealing it',async()=>{
 const original=globalThis.document;
 const body={};globalThis.document={activeElement:body};
 let nodes={},html='',resolve,reject;
 const container={
  querySelector:key=>nodes[key] || null,
  get innerHTML(){return html;},
  set innerHTML(value){
   if(Object.values(nodes).includes(document.activeElement))document.activeElement=body;
   html=value;nodes={};
   for(const [selector,marker] of [['[data-votings-refresh]','data-votings-refresh'],['[data-voting-select]','data-voting-select']]){
    if(!value.includes(marker))continue;
    const selected=value.match(/<option value="([^"]+)" selected/);
    nodes[selector]={value:selected?.[1] || '',focus(){document.activeElement=this;}};
   }
  }
 };
 let first=true;
 try{
  await loadBillVotings({billId:'synthetic',container,isCurrent:()=>true,fetchData:()=>{
   if(first){first=false;return Promise.resolve(data);}
   return new Promise((yes,no)=>{resolve=yes;reject=no;});
  }});
  const history=container.querySelector('[data-voting-select]');
  history.focus();history.value='synthetic-amendment';history.onchange();
  assert.notEqual(container.querySelector('[data-voting-select]'),history);
  assert.equal(document.activeElement,container.querySelector('[data-voting-select]'));
  assert.equal(container.querySelector('[data-voting-select]').value,'synthetic-amendment');
  for(const fail of [false,true]){
   const refresh=container.querySelector('[data-votings-refresh]');refresh.focus();
   const pending=refresh.onclick();
   const waiting=container.querySelector('[data-votings-refresh]');
   assert.notEqual(waiting,refresh);assert.equal(document.activeElement,waiting);
   assert.match(html,/aria-disabled="true"/);
   if(fail)reject(new Error('HTTP 500'));else resolve(data);
   await pending;
   assert.equal(document.activeElement,container.querySelector('[data-votings-refresh]'));
   assert.equal(container.querySelector('[data-voting-select]').value,'synthetic-amendment');
   if(fail)assert.match(html,/Pozostawiono poprzednio pobrane dane/);
  }
  const changedHistory=container.querySelector('[data-voting-select]');
  changedHistory.focus();changedHistory.onchange();
  assert.equal(document.activeElement,container.querySelector('[data-voting-select]'),'later history changes must not inherit completed refresh focus intent');
  container.querySelector('[data-votings-refresh]').focus();
  const pending=container.querySelector('[data-votings-refresh]').onclick();
  const outside={};document.activeElement=outside;
  resolve(data);await pending;
  assert.equal(document.activeElement,outside,'async completion must respect a deliberate focus move');
 }finally{if(original===undefined)delete globalThis.document;else globalThis.document=original;}
});
