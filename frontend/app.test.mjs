import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('presidential content is rendered only in bill details with a nonblank summary', async () => {
  const feed = JSON.parse(await readFile(new URL('../data/presidential-comments.json', import.meta.url)));
  const html = await readFile(new URL('./index.html', import.meta.url), 'utf8');
  assert.doesNotMatch(html, /presidential-section|presidential-comments|Stanowisko [Pp]rezydenta|Jak głosowali posłowie/);
  const {bills: availableBills} = JSON.parse(await readFile(new URL('../data/seed-bills.json', import.meta.url)));
  let bill = availableBills.find(b => b.id === '10-2693');
  assert.ok(bill?.summary?.lead);
  const snapshot = JSON.parse(await readFile(new URL('../data/bill-votings.json', import.meta.url)));
  let showVotings = false, listDemo = true, statusDemo = false, detailDemo = false;
  let focused = null, sectionClicks;
  const returnAnchor = {isConnected:true,textContent:'Czytaj projekt',getAttribute:()=> '#projekt/10-2693',focus(){focused=this;}};
  const replacementAnchor = {...returnAnchor,isConnected:true};
  const elements = new Map();
  const events = {};
  const element = id => {
    if (!elements.has(id)) elements.set(id, {innerHTML:'',value:id === 'filter' ? 'all' : '',classList:{values:new Set(),toggle(name,on){if(on)this.values.add(name);else this.values.delete(name);}},querySelector(){return null;},setAttribute(){},addEventListener(name, handler){this[name]=handler;},focus(){focused=id;},scrollIntoView(){this.scrolled=true;},querySelectorAll(){return [returnAnchor.isConnected ? returnAnchor : replacementAnchor];}});
    return elements.get(id);
  };
  const originals = Object.fromEntries(['document','window','location','fetch','setInterval'].map(k => [k, globalThis[k]]));
  Object.assign(globalThis, {
    document:{getElementById:element,addEventListener(name,handler){if(name==='click')sectionClicks=handler;}},
    window:{addEventListener(name, handler){events[name]=handler;}},
    location:{hash:'',origin:'http://localhost:5173'},
    setInterval(){return 1;},
    fetch:async url => ({ok:true,json:async()=>url === '/api/bills' ? {bills:availableBills,demo:listDemo} : url === '/api/status' ? {demo:statusDemo} : url === '/api/presidential-comments' ? feed : url.endsWith('/votings') && showVotings ? snapshot.bills.find(b => b.billId === bill.id) : {...bill,demo:detailDemo}})
  });
  try {
    await import('./app.js?visibility-test');
    const assertLanding = () => {
      assert.equal(element('listing').hidden,false);
      assert.equal(element('intro').classList.values.has('is-detail'),false);
      assert.equal(element('detail').hidden,true);
      assert.doesNotMatch(element('bills').innerHTML,/president-card|Stanowisko [Pp]rezydenta|Jak głosowali posłowie/);
      assert.equal(elements.has('presidential-comments'),false);
    };
    assertLanding();
    assert.equal(focused,null,'initial load must not steal focus');
    for (const hash of ['#about','#main']) { location.hash=hash; await events.hashchange(); assert.equal(focused,null); }
    assert.match(element('result-count').textContent,/dane przykładowe/,'list demo provenance survives live backend status');
    element('bills').click({target:{closest:()=>returnAnchor}});
    assert.match(element('bills').innerHTML, /#projekt\/10-2693/);
    assert.match(element('bills').innerHTML, /#projekt\/10-3355/);
    for (const query of ['2693', bill.title]) {
      element('search').value=query; element('search').input();
      assert.match(element('bills').innerHTML, /#projekt\/10-2693/);
      assert.doesNotMatch(element('bills').innerHTML, /#projekt\/10-3355/);
      assertLanding();
    }
    element('search').value=''; element('search').input();
    element('list-view').onclick(); assertLanding();
    element('cards-view').onclick(); assertLanding();
    location.hash='#projekt/10-2693'; await events.hashchange();
    assert.doesNotMatch(element('detail').innerHTML,/Dane przykładowe/,'live detail does not inherit demo list');
    for(const hash of ['#about','#main']){
      let prevented=false;
      sectionClicks({target:{closest:()=>({getAttribute:()=>hash})},preventDefault(){prevented=true;}});
      assert.equal(prevented,true);assert.equal(location.hash,'#projekt/10-2693');
      assert.equal(element(hash.slice(1)).scrolled,true);assert.equal(element('detail').hidden,false);
    }
    // Same URL after a reload still resolves to project details.
    await events.hashchange();assert.equal(element('detail').hidden,false);
    assert.equal(element('detail').hidden,false);
    assert.equal(element('intro').classList.values.has('is-detail'),true);
    assert.match(element('detail').innerHTML,/president-card/);
    assert.match(element('detail').innerHTML,/Jak głosowali posłowie/);
    assert.match(element('votings-content').innerHTML,/Nie udało się pobrać głosowań/);
    assert.ok(element('detail').innerHTML.includes(bill.summary.lead));
    assert.match(element('detail').innerHTML, /WETO/);
    assert.match(element('detail').innerHTML, /13 lis 2026/);
    assert.ok(element('detail').innerHTML.includes(bill.summary.lead));
    assert.doesNotMatch(element('detail').innerHTML, /Brak informacji o stanowisku prezydenta/);
    listDemo=false;statusDemo=true;detailDemo=true;
    await element('sync').onclick();
    assert.doesNotMatch(element('result-count').textContent,/dane przykładowe/,'live list does not inherit offline status');
    showVotings = true; await events.hashchange();
    assert.match(element('detail').innerHTML,/Dane przykładowe/,'detail demo provenance comes from detail response');
    assert.match(element('votings-content').innerHTML,/242/);
    assert.match(element('votings-content').innerHTML,/Brak zapisanych głosów imiennych/);
    assert.match(element('votings-content').innerHTML,/Odśwież wyniki/);
    bill = availableBills.find(b => b.id === '10-3355');
    location.hash='#projekt/10-3355'; await events.hashchange();
    assert.match(element('detail').innerHTML, /Stanowisko prezydenta/);
    assert.match(element('detail').innerHTML, /Brak informacji o stanowisku prezydenta/);
    assert.doesNotMatch(element('detail').innerHTML, /president-card/);
    for (const summary of [undefined,null,{}, {lead:null},{lead:''},{lead:' \n\t '}]) {
      bill={...bill,summary}; await events.hashchange();
      assert.doesNotMatch(element('detail').innerHTML,/president-card|Stanowisko [Pp]rezydenta/);
    }
    element('search').value='2693';element('search').input();
    element('filter').value='analyzed';element('list-view').onclick();
    returnAnchor.isConnected=false; // The background list refresh replaced the original node.
    location.hash='#listing'; await events.hashchange(); assertLanding();
    assert.equal(focused,replacementAnchor);assert.equal(element('search').value,'2693');
    assert.equal(element('filter').value,'analyzed');assert.equal(element('bills').classList.values.has('list'),true);
    focused=null;location.hash='#about';await events.hashchange();assert.equal(focused,null);
    location.hash='#main';await events.hashchange();assert.equal(focused,null);
  } finally {
    for (const [key,value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key]; else globalThis[key]=value;
    }
  }
});
