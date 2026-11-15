const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const displayDate = value => /^\d{4}-\d{2}-\d{2}$/.test(value || '') && !Number.isNaN(Date.parse(value))
  ? new Intl.DateTimeFormat('pl-PL', {dateStyle:'medium', timeZone:'UTC'}).format(new Date(value)) : 'Brak daty';
const sourceLink = (url, label) => {
  try {
    const u = new URL(url);
    if (u.protocol !== 'https:' || u.hostname !== 'www.prezydent.pl' || u.port || u.username || u.password) return '';
    return `<a class="source-link" href="${escape(u.href)}" target="_blank" rel="noopener noreferrer">${label} ↗<span class="sr-only"> (otwiera nową kartę)</span></a>`;
  } catch { return ''; }
};

export function hasBillSummary(bill) {
  return bill?.status === 'analyzed' && typeof bill.summary?.lead === 'string' && bill.summary.lead.trim().length > 0;
}

export function relatedComments(feed, billId) {
  return Array.isArray(feed?.comments) ? feed.comments.filter(c => c.billId === billId) : [];
}

function card(c) {
  return `<article class="president-card"><div class="card-meta"><span>STANOWISKO PREZYDENTA · WETO</span><span>${escape(displayDate(c.date))}</span></div>
    <h3>${escape(c.title)}</h3>${c.billId ? `<p class="small">Powiązany druk: ${escape(c.billId)} (kadencja–numer)</p>` : ''}
    <p>${escape(c.summary)}</p><details><summary>Argumenty wskazane w uzasadnieniu</summary><ul>${(c.arguments || []).map(a => `<li>${escape(a)}</li>`).join('')}</ul><p class="small">${escape(c.method)}</p></details>
    <p class="small">To stanowisko Prezydenta; przedstawione zarzuty nie są oceną prawną Gońca z Wiejskiej.</p>
    <div class="president-sources">${sourceLink(c.sourceUrl,'Komunikat i wypowiedź Prezydenta')}${sourceLink(c.documentUrl,'Formalne uzasadnienie weta — PDF')}</div></article>`;
}

export function renderPresidentialFeed(feed, bills = []) {
  if (!Array.isArray(feed?.comments) || feed.mode !== 'snapshot') throw new Error('Niepoprawna odpowiedź ze stanowiskami Prezydenta.');
  const comments = feed.comments.filter(c => bills.some(b => b.id === c.billId && hasBillSummary(b)));
  return `<p class="small">Zapisane opracowania · weryfikacja źródeł: ${escape(displayDate(feed.verifiedAt))}. Lista nie aktualizuje się automatycznie i nie obejmuje wszystkich wet.</p>
    <div class="president-cards">${comments.length ? comments.map(card).join('') : '<p>Brak zapisanych stanowisk.</p>'}</div>
    ${sourceLink(feed.sourceUrl,'Wszystkie ustawy zawetowane na prezydent.pl')}`;
}

export function renderRelatedComments(feed, bill) {
  if (!hasBillSummary(bill)) return '';
  const comments = relatedComments(feed, bill.id);
  if (!comments.length) return '<section class="analysis president-related"><h3>Stanowisko prezydenta</h3><p>Brak informacji o stanowisku prezydenta</p></section>';
  return `<section class="analysis president-related"><h3>Stanowisko prezydenta</h3><p class="small">Powiązanie potwierdzone numerem druku w źródle. Weryfikacja: ${escape(displayDate(feed.verifiedAt))}.</p>${comments.map(card).join('')}</section>`;
}
