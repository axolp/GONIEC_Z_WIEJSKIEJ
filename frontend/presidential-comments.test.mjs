import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { renderPresidentialFeed, renderRelatedComments, relatedComments, hasBillSummary } from './presidential-comments.js';

const bill = {id:'10-2693', status:'analyzed', summary:{lead:'Treść streszczenia.'}};
const feed = JSON.parse(await readFile(new URL('../data/presidential-comments.json', import.meta.url)));

test('verified veto is associated only with the confirmed parliamentary print', () => {
  assert.equal(relatedComments(feed, '10-2693').length, 1);
  assert.equal(relatedComments(feed, '10-3355').length, 0);
  assert.equal(relatedComments(feed, '9-2693').length, 0);
  assert.match(renderRelatedComments(feed, {...bill, id:'10-3355'}), /Brak informacji o stanowisku prezydenta/);
  assert.match(renderRelatedComments(feed, {...bill, id:'9-2693'}), /Brak informacji o stanowisku prezydenta/);
  assert.match(renderRelatedComments(null, bill), /Nie udało się pobrać stanowisk/);
  assert.match(renderRelatedComments({comments:[]}, bill), /Brak informacji o stanowisku prezydenta/);
  assert.match(renderRelatedComments(feed, bill), /Stanowisko prezydenta/);
  const mixed = {...feed, comments:[...feed.comments, {...feed.comments[0], billId:'10-9999', title:'Inna ustawa'}]};
  assert.doesNotMatch(renderRelatedComments(mixed, bill), /Inna ustawa/);
});

test('feed discloses editorial snapshot and presents attributed arguments and official sources', () => {
  const html = renderPresidentialFeed(feed, [bill]);
  assert.match(html, /nie aktualizuje się automatycznie/);
  assert.match(html, /nie obejmuje wszystkich wet/);
  assert.match(html, /2026/);
  assert.match(html, /Zdaniem Prezydenta/);
  assert.match(html, /Formalne uzasadnienie/);
  assert.match(html, /prezydent\.pl/);
  assert.match(html, /nie są oceną prawną Gońca z Wiejskiej/);
  assert.throws(() => renderPresidentialFeed({}), /Niepoprawna/);
});

test('presidential content is escaped and malicious source links are omitted', () => {
  const c = {...feed.comments[0], title:'<img src=x onerror=alert(1)>', summary:'<script>bad</script>',
    sourceUrl:'javascript:alert(1)', documentUrl:'https://www.prezydent.pl.evil.example/file.pdf'};
  const html = renderPresidentialFeed({...feed, comments:[c]}, [bill]);
  assert.doesNotMatch(html, /<script>|<img|javascript:|evil\.example/);
  assert.match(html, /&lt;script&gt;/);
});


test('presidential elements require an analyzed bill with nonblank summary content in both views', () => {
  for (const summary of [undefined, null, {}, {lead:null}, {lead:''}, {lead:' \n\t '}, {method:'Metoda bez treści'}]) {
    const emptyBill = {...bill, summary};
    assert.equal(hasBillSummary(emptyBill), false);
    assert.equal(renderRelatedComments(feed, emptyBill), '');
    assert.doesNotMatch(renderPresidentialFeed(feed, [emptyBill]), /class="president-card"/);
  }
  const pending = {...bill, status:'pending'};
  assert.equal(renderRelatedComments(feed, pending), '');
  assert.doesNotMatch(renderPresidentialFeed(feed, [pending]), /class="president-card"/);
  assert.match(renderRelatedComments(feed, bill), /class="president-card"/);
  assert.match(renderPresidentialFeed(feed, [bill]), /class="president-card"/);
  assert.doesNotMatch(renderPresidentialFeed(feed, [{...bill,id:'9-2693'}]), /class="president-card"/);
});


test('available nuclear-energy bill displays an explicit lack of presidential information', async () => {
  const {bills} = JSON.parse(await readFile(new URL('../data/seed-bills.json', import.meta.url)));
  const available = bills.find(b => b.id === '10-3355');
  assert.ok(hasBillSummary(available));
  const html = renderRelatedComments(feed, available);
  assert.match(html, /<h3>Stanowisko prezydenta<\/h3>/);
  assert.match(html, /Brak informacji o stanowisku prezydenta/);
  assert.doesNotMatch(html, /president-card|WETO|nie zajął stanowiska/);
});

test('failed feed is distinct from verified absence and all detail states disclose snapshot limits',()=>{
 for(const unavailable of [null,{error:true}]){
  const html=renderRelatedComments(unavailable,bill);
  assert.match(html,/role="alert"/);assert.match(html,/Nie udało się pobrać/);
  assert.doesNotMatch(html,/Brak informacji o stanowisku/);
 }
 for(const current of [feed,{mode:'snapshot',comments:[]},{error:true}]){
  const html=renderRelatedComments(current,bill);
  assert.match(html,/nie pełny rejestr/);assert.match(html,/Synchronizacja projektów jej nie aktualizuje/);
  assert.match(html,/Brak wpisu nie oznacza braku stanowiska/);
 }
});
