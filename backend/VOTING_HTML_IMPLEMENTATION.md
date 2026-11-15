# Goniec z Wiejskiej — oficjalne strony wyników

## Wynik i kontrakt

Zmiany wyłącznie w `backend/`, bez commitów, bez edycji root `data/`, frontendu ani root dokumentacji. POC pozostaje przypisany ręcznie do projektu `10-2693` i głosowania `10-66-103`. Podany przykład `68/40` nie został przypisany do tego projektu.

Dotychczasowy endpoint i wszystkie dotychczasowe pola odpowiedzi pozostają. Do `Voting` dodano opcjonalne pola:

```text
clubResults: [{
  club: string,
  members: integer,
  totalVoted: integer,
  yes: integer,
  no: integer,
  abstain: integer,
  notParticipating: integer
}],
clubResultsSourceUrl: string,
clubResultsVerifiedAt: string
```

Nieobecne nowe pola są pomijane w JSON. Stary konstruktor `Voting` pozostaje jako przeciążenie, a istniejące snapshoty i konstrukcje testowe nie wymagają nowych argumentów. Liczniki w niepustych `clubResults` są wymagane; brak pola nie staje się zerem. `clubResultsVerifiedAt` to czas pobrania i walidacji HTML, UTC ISO Instant. `clubResultsSourceUrl` jest dokładnym oficjalnym URL zbudowanym dla właściwej kadencji, posiedzenia i głosowania.

HTML nigdy nie jest zamieniany na imienne `votes`. Odpowiedź może mieć `mode:"live"`, potwierdzone `clubResults` i `votes:[]`. W takim przypadku usługa zachowuje `stale:true` i `error` z komunikatem o braku danych imiennych. W tej sytuacji stale wskazuje niepełną dostępność danych, nie automatycznie przeterminowanie samych agregatów; ich czas potwierdzenia jest podany oddzielnie. Ostrzeżenie i źródło przechodzą przez zapis/odczyt cache i fallback po restarcie. Aktualne klubowe dane nie udają kompletu imiennego.

## Kolejność źródeł i pobieranie

1. Najpierw istniejący endpoint API szczegółów głosowania, z walidacją tożsamości, wyników i głosów imiennych.
2. Przy awarii, niekompletnych lub niespójnych danych API: serwerowe pobranie `https://www.sejm.gov.pl/Sejm{term}.nsf/Glosowanie.xsp?posiedzenie={sitting}&glosowanie={votingNumber}`.
3. Jeżeli HTML przejdzie walidację, zwracane są klubowe wyniki z ich źródłem, bez imiennych głosów.
4. Jeśli oba źródła zawiodą, dotychczasowa usługa pozostawia poprzedni cache lub zwraca oznaczony snapshot. Istniejący cache nie jest kasowany. Komunikat awarii zawiera przyczynę z API i HTML.

Gdy API zwróci poprawne głosy imienne, HTML nie jest pobierany. Rozszerzenie nie dodaje wyszukiwania głosowań po podobnym tytule ani automatycznych powiązań z ustawami. Głosowania nadal pochodzą wyłącznie z ręcznego mapowania koordynatora.

Klient nie przyjmuje URL HTML od użytkownika. Destynacja ma stały host `www.sejm.gov.pl`, HTTPS i ścieżkę zbudowaną z dodatnich identyfikatorów kadencji 10. Nie podąża za przekierowaniami. Wspólny transport daje timeout połączenia i całej odpowiedzi (wraz z body). HTML ma limit `min(MAX_JSON_BYTES, 2 MiB)`. API i HTML mają odrębne timeouty; w najgorszym przypadku zadanie w tle może wykorzystać dwa kolejne limity czasu. Żądanie HTTP użytkownika nadal nie czeka na sieć.

## Parser i tożsamość

Dodano jsoup 1.21.2 jako parser DOM. Kod nie wykonuje JavaScriptu, nie ładuje zasobów/linków znalezionych w dokumencie i rozpoznaje kodowanie z BOM/meta lub domyślnego UTF-8. [Dokumentacja jsoup](https://jsoup.org/apidocs/org/jsoup/Jsoup) opisuje używany wariant parsowania InputStream.

Parser wymaga:

- kompletnego zakończenia HTML i tabeli, bez rozpoznanej blokady/captcha;
- nagłówka H1 potwierdzającego numer głosowania i posiedzenia;
- potwierdzenia kadencji X w treści albo prawidłowego canonical URL do tego samego głosowania; sprzeczny canonical jest odrzucany;
- jednego jednoznacznego numeru druku wynikającego z ręcznego źródła `druk.xsp?nr=` / `PrzebiegProc.xsp?nr=` albo jawnego numeru druku w tytule mapowania, obecnego również w przedmiocie HTML;
- przedmiotu odczytanego między nagłówkiem głosowania a sekcją wyników, a nie z nawigacji lub podobnego tytułu;
- potwierdzenia całości projektu dla kategorii final, a poprawki dla amendment; kategoria i domyślny wybór nie są wyprowadzane z kolejności głosowań;
- prawidłowej daty i czasu HTML oraz zgodności dnia z ręcznym mapowaniem;
- wszystkich pięciu liczbowych sum ogólnych: głosowało, za, przeciw, wstrzymało się, nie głosowało; znane liczniki snapshotu muszą być z nimi zgodne.

Konserwatywna reguła tożsamości może odrzucić prawidłową stronę, która nie zawiera wymaganego jednoznacznego oznaczenia kadencji, druku lub przedmiotu. Wtedy zwracany jest fallback z błędem; nie akceptujemy jej na podstawie samego podobieństwa tytułu. Parser nie zmienia mapowania POC na 68/40.

## Tabela klubów i sumy

[Oficjalna strona przykładowego głosowania](https://www.sejm.gov.pl/Sejm10.nsf/agent.xsp?NrGlosowania=1&NrKadencji=10&NrPosiedzenia=68&symbol=glosowania) potwierdza strukturę „Wyniki głosowania wg klubów” oraz nagłówki `Klub/Koło`, `Liczba czł.`, `Głosowało`, `Za`, `Przeciw`, `Wstrzymało się`, `Nie głosowało`. Użyto tych nagłówków, nie pozycji kolumn. Obsługiwany jest też wariant `Liczba członków`. Tabele kworum bez kolumn za/przeciw/wstrzymanie nie są interpretowane jako zwykły wynik ustawy.

Znak ASCII `-` jest zamieniany na 0 wyłącznie w rozpoznanej komórce liczbowej. Pusta komórka, `?`, brak wymaganej kolumny, powtórzony nagłówek, niekompletny wiersz, scalone komórki danych lub niejednoznaczna tabela są odrzucane. Nazwy klubów są zachowywane, a duplikaty odrzucane. Opcjonalny wiersz Razem/Suma/Ogółem jest sprawdzany, ale nie trafia do listy klubów.

Każdy klub musi spełniać `yes+no+abstain=totalVoted` i `totalVoted+notParticipating=members`. Sumy wszystkich klubów muszą zgadzać się ze wszystkimi ogólnymi licznikami głosowania, a liczba członków nie może przekroczyć 460. Usunięty wiersz nie jest zastępowany danymi domyślnymi. Walidacja źródła, liczb i sum jest wykonywana również przy odczycie cache. Cache live dopuszcza pełne `votes` albo niepuste, potwierdzone `clubResults` ze źródłem i czasem. Sam pusty zbiór nie staje się live.

## Próba pobrania rzeczywistego HTML

Spróbowano pobrać dokładnie `https://www.sejm.gov.pl/Sejm10.nsf/Glosowanie.xsp?posiedzenie=66&glosowanie=103`:

- shell `curl`: exit 6, `Could not resolve host: www.sejm.gov.pl`;
- narzędzie web: strona niedostępna przez to narzędzie;
- przykład 68/40 również nie był dostępny jako bezpośrednia strona przez web.

Nie uzyskano oryginalnego HTML 66/103 i nie utworzono `/tmp/sejm-66-103.html`. Nie zapisano pod tą nazwą fixture, zrekonstruowanego HTML ani wyników z innego głosowania. Wyszukiwanie oficjalnego serwisu pozwoliło potwierdzić nazwy kolumn na innych głosowaniach, ale nie zastępuje testu na rzeczywistym HTML 66/103.

## Testy i wynik weryfikacji

Dodano jawną fixture `src/test/resources/votings/synthetic-club-results.html`. Zawiera oznaczenie SYNTHETIC, fikcyjne nazwy klubów oraz małe, testowe liczniki; nie jest oryginałem strony ani rzeczywistym wynikiem. Przestawiono w niej kolumny, aby sprawdzić odczyt po nagłówkach.

Nowe testy `VotingHtmlParserTest` i `VotingHtmlFallbackTest` obejmują: odczyt tabeli i liczbowych myślników, zgodność sum, brak tworzenia głosów imiennych, błędne ID/kadencję/druk/kategorię, captcha, ucięty HTML, brak kolumny/wiersza, duplikaty klubów, nieprawidłowe liczby, API failure + HTML success, HTML failure → snapshot, zapis i ponowny odczyt club-only live z pochodzeniem oraz brak HTML requestu przy poprawnym API. Stary konstruktor i JSON bez nowych pól są pokryte testem zgodności.

Istniejące testy klienta API używają lokalnego HTTP i mają wyłączone zewnętrzne pobieranie HTML przez spy, żeby błędna odpowiedź testowego API nie inicjowała prawdziwego połączenia do Sejmu.

`./backend/mvnw test`: exit 127 z powodu braku Mavena. W środowisku brak JVM; testy nie zostały skompilowane ani wykonane. Nie instalowano narzędzi i nie raportuje się testów jako przechodzących. Wykonano przegląd statyczny źródeł, XML/YAML, bilansu literałów/nawiasów Java oraz sum syntetycznej fixture. Do potwierdzenia pozostają kompilacja/testy JVM i parser na oryginalnym HTML 66/103 w środowisku z dostępem do Sejmu. Nie wprowadzano danych testowych do produkcyjnego mapowania.
