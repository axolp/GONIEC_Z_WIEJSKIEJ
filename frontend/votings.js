const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const labels = {YES:'Za', NO:'Przeciw', ABSTAIN:'Wstrzymał(a) się', ABSENT:'Nie głosował(a)'};
const categories = {final:'Głosowanie nad całością', amendment:'Poprawka', other:'Inne głosowanie'};
const count = value => Number.isInteger(value) && value >= 0 ? String(value) : 'Brak danych';
export function displayVotingDate(value) {
  if (!value) return 'Brak danych';
  const localTimestamp = typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?$/.test(value);
  // An offset-free Sejm timestamp is a Polish wall-clock reading, not a browser-local instant.
  const d = new Date(localTimestamp ? value + 'Z' : value);
  const hasTime = String(value).includes('T');
  if (Number.isNaN(d.getTime())) return 'Brak danych';
  const formatted = new Intl.DateTimeFormat('pl-PL', {dateStyle:'long', ...(hasTime ? {timeStyle:'short',timeZone:localTimestamp ? 'UTC' : 'Europe/Warsaw'} : {timeZone:'UTC'})}).format(d);
  return formatted + (hasTime ? ' (czas polski)' : '');
}
const date = displayVotingDate;
function sourceLink(url, label) {
  try {
    const u = new URL(url);
    if (u.protocol !== 'https:' || u.username || u.password) return '';
    return `<a class="source-link" href="${escape(u.href)}" target="_blank" rel="noopener noreferrer">${escape(label)} ↗<span class="sr-only"> (otwiera nową kartę)</span></a>`;
  } catch { return ''; }
}
export function voteLabel(vote) { return Object.hasOwn(labels,vote) ? labels[vote] : `Nieznany głos (${String(vote ?? 'brak wartości')})`; }
const clubName = row => typeof row.club === 'string' && row.club.trim() ? row.club : 'Brak danych o klubie';
export function aggregateClubs(votes = []) {
  const clubs = new Map();
  for (const row of votes) {
    const club = clubName(row);
    if (!clubs.has(club)) clubs.set(club, {club, YES:0,NO:0,ABSTAIN:0,ABSENT:0,unknown:0,total:0});
    const result = clubs.get(club);
    result[Object.hasOwn(labels,row.vote) ? row.vote : 'unknown']++;
    result.total++;
  }
  return [...clubs.values()].sort((a,b) => a.club.localeCompare(b.club,'pl'));
}
export function filterVotes(votes, {name='',club='',vote=''} = {}) {
  const query = name.trim().toLocaleLowerCase('pl');
  return votes.filter(row => String(row.lastName ?? '').toLocaleLowerCase('pl').includes(query)
    && (!club || clubName(row) === club) && (!vote || String(row.vote ?? '') === vote));
}
export function defaultVoting(data) {
  return data.votings.find(v => v.id === data.defaultVotingId) || data.votings.find(v => v.category === 'final') || data.votings[0];
}
export function validateVotings(data, billId) {
  if (data?.billId !== billId || !Array.isArray(data.votings) || !['snapshot','live'].includes(data.mode)
    || data.votings.some(v => !v || typeof v.id !== 'string' || !Array.isArray(v.votes))) throw new Error('Niepoprawna odpowiedź z głosowaniami.');
  return data;
}
export function officialVotingUrl(voting) {
  const numbers = [voting.term,voting.sitting,voting.votingNumber];
  if (!numbers.every(n => Number.isSafeInteger(n) && n > 0)) return '';
  const fallback = `https://www.sejm.gov.pl/Sejm${voting.term}.nsf/Glosowanie.xsp?posiedzenie=${voting.sitting}&glosowanie=${voting.votingNumber}`;
  try {
    const u = new URL(voting.clubResultsSourceUrl);
    if (u.protocol === 'https:' && u.hostname === 'www.sejm.gov.pl' && !u.port && !u.username && !u.password
      && u.pathname.toLowerCase() === `/sejm${voting.term}.nsf/glosowanie.xsp`
      && u.searchParams.get('posiedzenie') === String(voting.sitting) && u.searchParams.get('glosowanie') === String(voting.votingNumber)) return u.href;
  } catch { /* Missing or unsafe source: construct the official URL from validated identifiers. */ }
  return fallback;
}
function clubTable(voting) {
  const heading = '<h4>Jak głosowały kluby i koła?</h4>';
  const official = Array.isArray(voting.clubResults) && voting.clubResults.length > 0;
  if (!official && !voting.votes.length) return heading + '<p>Podział na ugrupowania będzie dostępny po pobraniu danych klubowych lub imiennych z Sejmu.</p>';
  const rows = official ? voting.clubResults.map(r => ({club:clubName(r),members:r.members,totalVoted:r.totalVoted,yes:r.yes,no:r.no,abstain:r.abstain,notParticipating:r.notParticipating}))
    : aggregateClubs(voting.votes).map(r => ({club:r.club,members:r.total,totalVoted:r.YES+r.NO+r.ABSTAIN,yes:r.YES,no:r.NO,abstain:r.ABSTAIN,notParticipating:r.ABSENT,unknown:r.unknown}));
  return heading + `<p class="small">Kluby i koła według danych z tego głosowania; bez przypisywania obecnej przynależności. ${official ? 'Rozkład z oficjalnej tabeli Sejmu; liczba członków według źródła.' : 'Rozkład obejmuje zapisane głosy imienne. Łącznie oznacza liczbę zapisanych pozycji, a nie pełny skład klubu. Głosowało obejmuje rozpoznane głosy za, przeciw i wstrzymujące się.'}</p>${official ? sourceLink(officialVotingUrl(voting),'Źródło podziału klubowego — Sejm (HTML)') : ''}<div class="table-scroll" tabindex="0" role="region" aria-label="Rozkład głosów według klubów i kół"><table><caption>Rozkład głosów według klubów i kół</caption><thead><tr><th scope="col">Klub / koło</th><th scope="col">${official ? 'Członkowie' : 'Łącznie'}</th><th scope="col">Głosowało</th><th scope="col">Za</th><th scope="col">Przeciw</th><th scope="col">Wstrzymanie</th><th scope="col">Nie głosowali</th>${official ? '' : '<th scope="col">Nieznany głos</th>'}</tr></thead><tbody>${rows.map(r => `<tr><th scope="row">${escape(r.club)}</th><td>${count(r.members)}</td><td>${count(r.totalVoted)}</td><td>${count(r.yes)}</td><td>${count(r.no)}</td><td>${count(r.abstain)}</td><td>${count(r.notParticipating)}</td>${official ? '' : `<td>${count(r.unknown)}</td>`}</tr>`).join('')}</tbody></table></div>`;
}
export function renderMembers(votes, filters) {
  const rows = filterVotes(votes,filters);
  return `<p class="small" role="status">Wyświetlono ${rows.length} z ${votes.length} zapisanych głosów.</p>${rows.length ? `<ul class="member-list">${rows.map(r => `<li><span><strong>${escape(r.firstName)} ${escape(r.lastName)}</strong><small>${escape(clubName(r))}</small></span><span>${escape(voteLabel(r.vote))}</span></li>`).join('')}</ul>` : '<p>Brak głosów pasujących do filtrów.</p>'}`;
}
export function renderVoting(data, selectedId) {
  if (!data.votings.length) return `<p>Brak informacji o głosowaniach dla tej ustawy.</p>${sourceLink(data.sourceUrl,'Źródło danych o głosowaniach')}`;
  const v = data.votings.find(v => v.id === selectedId) || defaultVoting(data);
  const votes = v.votes;
  const clubs = [...new Set(votes.map(clubName))].sort((a,b) => a.localeCompare(b,'pl'));
  const enums = [...new Set(votes.map(r => String(r.vote ?? '')))];
  return `${data.votings.length > 1 ? `<label class="voting-history">Historia głosowań<select data-voting-select>${data.votings.map(item => `<option value="${escape(item.id)}" ${item.id === v.id ? 'selected' : ''}>${escape(date(item.date))} · ${escape(categories[item.category] || 'Nieznana kategoria')} · ${escape(item.title)}</option>`).join('')}</select></label>` : ''}
    <p class="eyebrow">${escape(categories[v.category] || 'Nieznana kategoria')} · ${escape(v.term)}. kadencja · posiedzenie ${escape(v.sitting)} · głosowanie ${escape(v.votingNumber)}</p>
    <h4>${escape(v.title || 'Brak tytułu głosowania')}</h4><p><strong>Data głosowania:</strong> ${escape(date(v.date))}</p><p class="voting-description">${escape(v.description || 'Brak opisu przedmiotu głosowania.')}</p>
    <dl class="voting-totals">${[['yes','Za'],['no','Przeciw'],['abstain','Wstrzymanie'],['notParticipating','Nie głosowali'],['totalVoted','Głosowało']].map(([key,label]) => `<div><dt>${label}</dt><dd>${count(v[key])}</dd></div>`).join('')}</dl>
    <div class="voting-sources">${sourceLink(v.sourceUrl,'Oficjalny wynik głosowania')}${sourceLink(officialVotingUrl(v),`Wynik Sejmu (HTML) — ${v.term}. kadencja, posiedzenie ${v.sitting}, głosowanie ${v.votingNumber}`)}${sourceLink(v.pdfUrl,'Wyniki w PDF')}</div>
    ${clubTable(v)}${votes.length ? `<details class="voting-members"><summary>Głosy imienne posłów (${votes.length})</summary><div class="voting-filters"><label>Szukaj nazwiska<input type="search" data-member-name placeholder="Nazwisko posła lub posłanki"></label><label>Klub w dniu głosowania<select data-member-club><option value="">Wszystkie kluby</option>${clubs.map(c => `<option value="${escape(c)}">${escape(c)}</option>`).join('')}</select></label><label>Głos<select data-member-vote><option value="">Wszystkie głosy</option>${enums.map(e => `<option value="${escape(e)}">${escape(voteLabel(e))}</option>`).join('')}</select></label></div><div data-members aria-live="polite">${renderMembers(votes)}</div></details>` : '<p>Brak zapisanych głosów imiennych</p>'}`;
}
export function mountVotings(container, data, {onRefresh,refreshing=false,refreshError} = {}) {
  const intro = `<p class="small">${data.demo ? 'Tryb przykładowy — backend niedostępny. ' : ''}${data.mode === 'snapshot' ? 'Zapisana migawka danych' : 'Dane pobrane z API'} · Weryfikacja: ${escape(date(data.verifiedAt))}.</p>${data.stale ? '<p class="warning">Dane mogą być nieaktualne.</p>' : ''}${data.error ? `<p class="warning">Problem z odświeżeniem danych: ${escape(data.error)}</p>` : ''}`;
  const render = selected => {
    container.innerHTML = intro + (onRefresh ? `<div class="voting-refresh"><button type="button" data-votings-refresh ${refreshing ? 'disabled' : ''}>${refreshing ? 'Odświeżanie wyników…' : 'Odśwież wyniki'}</button>${refreshing ? '<p role="status">Pobieranie aktualnych wyników…</p>' : ''}${refreshError ? `<p role="alert">Nie udało się odświeżyć głosowań: ${escape(refreshError)}. Pozostawiono poprzednio pobrane dane.</p>` : ''}</div>` : '') + renderVoting(data,selected);
    const refresh = container.querySelector('[data-votings-refresh]');
    if (refresh) refresh.onclick = onRefresh;
    const select = container.querySelector('[data-voting-select]');
    if (select) select.onchange = () => render(select.value);
    const controls = ['name','club','vote'].map(key => container.querySelector(`[data-member-${key}]`));
    if (controls.every(Boolean)) {
      const update = () => {
        const v = data.votings.find(v => v.id === select?.value) || defaultVoting(data);
        container.querySelector('[data-members]').innerHTML = renderMembers(v.votes,{name:controls[0].value,club:controls[1].value,vote:controls[2].value});
      };
      controls[0].oninput=update; controls[1].onchange=update; controls[2].onchange=update;
    }
  };
  render();
}
export async function loadBillVotings({billId, container, fetchData, isCurrent}) {
  let lastData = null, pending = false;
  const run = async () => {
    if (!isCurrent() || pending) return;
    pending = true;
    if (lastData) mountVotings(container,lastData,{onRefresh:run,refreshing:true});
    else container.innerHTML = '<p role="status">Wczytywanie głosowań…</p>';
    try {
      const data = validateVotings(await fetchData(`/api/bills/${encodeURIComponent(billId)}/votings`),billId);
      if (!isCurrent()) return;
      lastData = data;
      mountVotings(container,data,{onRefresh:run});
    } catch (e) {
      if (!isCurrent()) return;
      if (lastData) mountVotings(container,lastData,{onRefresh:run,refreshError:e.message});
      else {
        container.innerHTML = `<p role="alert">Nie udało się pobrać głosowań: ${escape(e.message)}</p><button type="button" data-votings-retry>Spróbuj ponownie pobrać głosowania</button>`;
        const retry = container.querySelector('[data-votings-retry]');
        if (retry) retry.onclick = run;
      }
    } finally { pending = false; }
  };
  await run();
}
