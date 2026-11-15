import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = fileURLToPath(new URL('.', import.meta.url));
const types = {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml; charset=utf-8'};
export function createServer({ backend = process.env.BACKEND_URL || 'http://localhost:8080', seedPath = path.resolve(root, '../data/seed-bills.json'), presidentialPath = path.resolve(root, '../data/presidential-comments.json'), votingsPath = path.resolve(root, '../data/bill-votings.json'), timeout = 4000 } = {}) {
  return http.createServer(async (req, res) => {
    const send = (code, data) => { res.writeHead(code, {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'}); res.end(JSON.stringify(data)); };
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/api' || url.pathname.startsWith('/api/')) {
      try {
        const upstream = new URL(req.url, backend);
        const headers = { ...req.headers }; delete headers.host; delete headers.connection;
        const response = await fetch(upstream, {method:req.method, headers, ...(req.method !== 'GET' && req.method !== 'HEAD' ? {body:req, duplex:'half'} : {}), signal:AbortSignal.timeout(timeout), redirect:'manual'});
        const body = Buffer.from(await response.arrayBuffer());
        res.writeHead(response.status, Object.fromEntries([...response.headers].filter(([key]) => !['transfer-encoding','connection','content-encoding','content-length'].includes(key))));
        res.end(body); return;
      } catch {
        if (req.method !== 'GET') return send(503, {error:'Tryb przykładowy — backend niedostępny. Nie można uruchomić synchronizacji. Uruchom backend na porcie 8080.'});
        const votingMatch = url.pathname.match(/^\/api\/bills\/([^/]+)\/votings$/);
        if (votingMatch) {
          try {
            const snapshot = JSON.parse(await readFile(votingsPath,'utf8'));
            if (!Array.isArray(snapshot.bills)) throw new Error('Invalid votings snapshot');
            const billId = decodeURIComponent(votingMatch[1]);
            const data = snapshot.bills.find(b => b.billId === billId);
            if (!data) {
              const seed = JSON.parse(await readFile(seedPath,'utf8'));
              const bills = Array.isArray(seed) ? seed : seed.bills;
              if (!Array.isArray(bills)) throw new Error('Invalid seed');
              const bill = bills.find(b => b.id === billId);
              if (!bill) return send(404,{error:'Nie znaleziono projektu.'});
              return send(200,{billId,verifiedAt:null,sourceUrl:bill.sourceUrl,defaultVotingId:null,votings:[],mode:'snapshot',stale:false,error:null,demo:true});
            }
            if (data.mode !== 'snapshot' || !Array.isArray(data.votings)) throw new Error('Invalid bill votings');
            return send(200,{...data,demo:true});
          } catch { return send(503,{error:'Backend niedostępny. Nie można odczytać zapisanych głosowań.'}); }
        }
        if (url.pathname === '/api/presidential-comments') {
          try {
            const feed = JSON.parse(await readFile(presidentialPath, 'utf8'));
            if (feed.mode !== 'snapshot' || !Array.isArray(feed.comments)) throw new Error('Invalid presidential feed');
            return send(200, {...feed, demo:true});
          } catch { return send(503, {error:'Nie można odczytać zapisanych stanowisk Prezydenta.'}); }
        }
        if (url.pathname === '/api/status') return send(200, {lastCheckedAt:null,lastSuccessAt:null,syncing:false,error:'Tryb przykładowy — backend niedostępny',intervalMinutes:null,demo:true});
        if (url.pathname === '/api/bills' || /^\/api\/bills\/[^/]+$/.test(url.pathname)) {
          try {
            const seed = JSON.parse(await readFile(seedPath,'utf8'));
            const bills = Array.isArray(seed) ? seed : seed.bills;
            if (!Array.isArray(bills)) throw new Error('Invalid seed');
            res.setHeader('X-Simply-Sejm-Mode','demo');
            if (url.pathname === '/api/bills') return send(200,{bills,demo:true});
            const bill = bills.find(b => String(b.id) === decodeURIComponent(url.pathname.slice('/api/bills/'.length)));
            return bill ? send(200,bill) : send(404,{error:'Nie znaleziono projektu.'});
          } catch { return send(503,{error:'Backend niedostępny. Brak poprawnego pliku data/seed-bills.json z danymi przykładowymi.'}); }
        }
        return send(404,{error:'Nie znaleziono endpointu.'});
      }
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') return send(405,{error:'Metoda niedozwolona.'});
    const files = {'/assets/paperboy.svg':'assets/paperboy.svg','/':'index.html','/index.html':'index.html','/app.js':'app.js','/votings.js':'votings.js','/presidential-comments.js':'presidential-comments.js','/styles.css':'styles.css'};
    const file = files[url.pathname];
    if (!file) return send(404,{error:'Nie znaleziono strony.'});
    try { const data = await readFile(path.join(root,file)); res.writeHead(200,{'Content-Type':types[path.extname(file)],'X-Content-Type-Options':'nosniff'}); res.end(req.method === 'HEAD' ? undefined : data); }
    catch { send(500,{error:'Nie można odczytać strony.'}); }
  });
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  createServer().listen(Number(process.env.PORT || 5173), '0.0.0.0', () => console.log('Goniec z Wiejskiej: http://localhost:' + (process.env.PORT || 5173)));
}
