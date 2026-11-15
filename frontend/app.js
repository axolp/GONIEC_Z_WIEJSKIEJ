import { loadBillVotings } from './votings.js';
import { renderRelatedComments } from './presidential-comments.js';
const $ = id => document.getElementById(id);
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const date = value => { if (!value) return 'Brak danych'; const d = new Date(value); return Number.isNaN(d.getTime()) ? 'Brak danych' : new Intl.DateTimeFormat('pl-PL', {dateStyle:'medium', ...(String(value).includes('T') ? {timeStyle:'short'} : {})}).format(d); };
const safeUrl = value => { try { if (typeof value !== 'string' || !value.trim()) return ''; const u = new URL(value, location.origin); if (!['https:','http:'].includes(u.protocol) || u.username || u.password) return ''; if (u.origin === location.origin && !/^\/api\/bills\/[^/]+\/pdf$/.test(u.pathname)) return ''; return escape(u.href); } catch { return ''; } };
const link = (url, label) => safeUrl(url) ? `<a class="source-link" href="${safeUrl(url)}" target="_blank" rel="noopener noreferrer">${label} <span aria-hidden="true">↗</span><span class="sr-only"> (otwiera nową kartę)</span></a>` : '';
let bills = [], demo = false, detailRequest = 0, polling, presidentialFeed = null;
async function loadPresidentialComments() {
  try {
    const feed = await api('/api/presidential-comments');
    if (feed.mode !== 'snapshot' || !Array.isArray(feed.comments)) throw new Error('Niepoprawne stanowiska Prezydenta.');
    presidentialFeed = feed;
  } catch {
    presidentialFeed = null;
  }
}
async function api(path, options) { const r = await fetch(path, options); const data = await r.json(); if (!r.ok) throw new Error(data.error || data.detail || `Błąd serwera (${r.status}).`); return data; }
function badge(bill) { return `<span class="badge ${bill.status === 'analyzed' ? '' : 'pending'}">${bill.status === 'analyzed' ? 'Opracowanie dostępne' : 'Oczekuje na analizę'}</span>`; }
function render() {
  const query = $('search').value.trim().toLocaleLowerCase('pl');
  const visible = bills.filter(b => ($('filter').value === 'all' || b.status === $('filter').value) && [b.title,b.number,b.summary?.lead,...(b.summary?.keyChanges || [])].join(' ').toLocaleLowerCase('pl').includes(query));
  $('result-count').textContent = `${visible.length} z ${bills.length} projektów${demo ? ' · dane przykładowe' : ''}`;
  $('bills').innerHTML = visible.length ? visible.map(b => `<article class="bill-card"><div class="card-meta"><span>DRUK NR ${escape(b.number)}</span><span>${escape(date(b.date))}</span></div>${badge(b)}<h3><a href="#projekt/${encodeURIComponent(b.id)}">${escape(b.title)}</a></h3><p>${escape(b.summary?.lead || 'Opracowanie tego projektu nie jest jeszcze dostępne. Możesz zapoznać się z dokumentem źródłowym.')}</p><div class="card-bottom"><span>${escape(b.term)}. kadencja Sejmu</span><a href="#projekt/${encodeURIComponent(b.id)}">Czytaj projekt <span aria-hidden="true">→</span><span class="sr-only">: ${escape(b.title)}</span></a></div></article>`).join('') : '<div class="empty"><h3>Brak pasujących projektów</h3><p>Zmień wyszukiwaną frazę lub wybierz inny status.</p></div>';
}
async function load() {
  $('bills').setAttribute('aria-busy','true');
  try { const data = await api('/api/bills'); if (!Array.isArray(data.bills)) throw new Error('Niepoprawna odpowiedź serwera.'); bills = data.bills; demo = data.demo === true; render(); }
  catch (e) { $('bills').innerHTML = `<div class="empty"><h3>Nie udało się wczytać projektów</h3><p>${escape(e.message)}</p><button id="retry">Spróbuj ponownie</button></div>`; $('retry').onclick = load; }
  finally { $('bills').setAttribute('aria-busy','false'); }
}
async function status() {
  try {
    const s = await api('/api/status');
    const wasDemo = demo; demo = s.demo === true;
    $('sync-state').textContent = demo ? 'Tryb przykładowy — backend niedostępny' : s.syncing ? 'Trwa sprawdzanie nowych projektów…' : s.error ? `Błąd synchronizacji: ${s.error}` : 'Połączenie z backendem aktywne';
    $('sync-state').classList.toggle('warning', demo || !!s.error);
    $('last-checked').textContent = date(s.lastCheckedAt); $('last-success').textContent = date(s.lastSuccessAt);
    $('sync').disabled = !!s.syncing; $('sync').textContent = s.syncing ? 'Synchronizacja w toku…' : 'Sprawdź nowe projekty ↻';
    if (wasDemo !== demo) render();
    if (s.syncing && !polling) polling = setInterval(async () => { const active = await status(); if (!active) { clearInterval(polling); polling = null; await load(); } }, 2000);
    return !!s.syncing;
  } catch (e) { $('sync-state').textContent = `Status niedostępny: ${e.message}`; return false; }
}
$('sync').onclick = async () => {
  $('sync').disabled = true; $('sync-message').textContent = 'Wysyłanie żądania…';
  try { await api('/api/sync',{method:'POST'}); $('sync-message').textContent = 'Zlecono sprawdzenie nowych projektów.'; await status(); await load(); }
  catch (e) { $('sync-message').textContent = e.message; }
  finally { if (!polling) $('sync').disabled = false; }
};
const items = values => Array.isArray(values) && values.length ? `<ul>${values.map(v => `<li>${escape(v)}</li>`).join('')}</ul>` : '<p>Brak informacji w opracowaniu.</p>';
function setViewVisibility() {
  const match = location.hash.match(/^#projekt\/(.+)$/);
  $('intro').classList.toggle('is-detail',!!match);
  $('listing').hidden = !!match; $('detail').hidden = !match;
  return match;
}
async function route() {
  const token = ++detailRequest;
  const match = setViewVisibility();
  if (!match) return;
  $('detail').innerHTML = '<p class="empty" role="status">Wczytywanie projektu…</p>';
  try {
    const b = await api(`/api/bills/${encodeURIComponent(decodeURIComponent(match[1]))}`);
    if (token !== detailRequest) return;
    const s = b.summary || {};
    $('detail').innerHTML = `<a class="back" href="#listing">← Wszystkie projekty</a><div class="detail-meta">DRUK NR ${escape(b.number)} · ${escape(b.term)}. KADENCJA · ${escape(date(b.date))}</div>${badge(b)}<h2>${escape(b.title)}</h2><p class="detail-lead">${escape(s.lead || 'Ten projekt oczekuje na opracowanie.')}</p><div class="detail-layout"><div><section class="analysis"><h3><span>01</span> Co się zmieni?</h3>${items(s.keyChanges)}</section><section class="analysis"><h3><span>02</span> Kogo dotyczy projekt?</h3>${items(s.affectedGroups)}</section><section class="analysis"><h3><span>03</span> Kiedy zmiany wejdą w życie?</h3><p>${escape(s.effectiveDate || 'Brak informacji w opracowaniu.')}</p></section>${s.caveats ? `<section class="caveat"><h3>Na co zwrócić uwagę</h3><p>${escape(s.caveats)}</p></section>` : ''}${renderRelatedComments(presidentialFeed, b)}<section class="analysis bill-votings" aria-labelledby="votings-title"><h3 id="votings-title">Jak głosowali posłowie?</h3><div id="votings-content" aria-live="polite"></div></section>${b.textPreview ? `<details><summary>Fragment tekstu projektu</summary><p class="preview">${escape(b.textPreview)}</p></details>` : ''}</div><aside class="document-panel"><p class="eyebrow">SPRAWDŹ U ŹRÓDŁA</p><h3>Oficjalne dokumenty</h3><p>Opracowanie to punkt wyjścia. Pełną treść znajdziesz w dokumentach Sejmu.</p>${link(b.sourceUrl,'Karta projektu w Sejmie')}${link(b.pdfUrl,'Otwórz dokument PDF')}<hr><h3>Jak powstało opracowanie?</h3><p>${escape(s.method || 'Nie podano metody opracowania.')}</p>${demo ? '<p class="warning">Dane przykładowe. Nie są wynikiem aktualnej synchronizacji.</p>' : ''}</aside></div>`;
    $('detail').focus();
    await loadBillVotings({billId:b.id, container:$('votings-content'), fetchData:api, isCurrent:() => token === detailRequest});
  } catch(e) { if (token === detailRequest) $('detail').innerHTML = `<a class="back" href="#listing">← Wszystkie projekty</a><div class="empty"><h2>Nie udało się otworzyć projektu</h2><p>${escape(e.message)}</p></div>`; }
}
$('search').addEventListener('input',render); $('filter').addEventListener('change',render);
for (const [id, list] of [['cards-view',false],['list-view',true]]) $(id).onclick = () => { $('bills').classList.toggle('list',list); $('cards-view').setAttribute('aria-pressed',String(!list)); $('list-view').setAttribute('aria-pressed',String(list)); };
window.addEventListener('hashchange',route);
setViewVisibility();
await Promise.all([load(),status(),loadPresidentialComments()]); await route();
setInterval(status,30000);
