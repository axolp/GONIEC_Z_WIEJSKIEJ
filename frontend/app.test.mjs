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
  let showVotings = false;
  const elements = new Map();
  const events = {};
  const element = id => {
    if (!elements.has(id)) elements.set(id, {innerHTML:'',value:id === 'filter' ? 'all' : '',classList:{values:new Set(),toggle(name,on){if(on)this.values.add(name);else this.values.delete(name);}},querySelector(){return null;},setAttribute(){},addEventListener(name, handler){this[name]=handler;},focus(){}});
    return elements.get(id);
  };
  const originals = Object.fromEntries(['document','window','location','fetch','setInterval'].map(k => [k, globalThis[k]]));
  Object.assign(globalThis, {
    document:{getElementById:element},
    window:{addEventListener(name, handler){events[name]=handler;}},
    location:{hash:'',origin:'http://localhost:5173'},
    setInterval(){return 1;},
    fetch:async url => ({ok:true,json:async()=>url === '/api/bills' ? {bills:availableBills} : url === '/api/status' ? {} : url === '/api/presidential-comments' ? feed : url.endsWith('/votings') && showVotings ? snapshot.bills.find(b => b.billId === bill.id) : bill})
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
    showVotings = true; await events.hashchange();
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
    location.hash=''; await events.hashchange(); assertLanding();
  } finally {
    for (const [key,value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key]; else globalThis[key]=value;
    }
  }
});
