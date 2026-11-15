# Goniec z Wiejskiej — głosowania nad ustawą

Aktualizacja: opcjonalne agregaty klubowe i fallback oficjalnego HTML opisano w [VOTING_HTML_IMPLEMENTATION.md](VOTING_HTML_IMPLEMENTATION.md). Rozszerzenie dopuszcza live bez votes, gdy dostępne są zweryfikowane clubResults; poniższy raport opisuje pierwotny zakres.

## Zakres i źródło

Zmiany wyłącznie w `backend/`. Nie zmieniano `frontend/`, root `data/`, root dokumentacji ani nie tworzono commitów. Nie uruchamiano dodatkowych agentów.

Przeczytano oficjalną [dokumentację API Sejmu, sekcję szczegółów głosowania](https://api.sejm.gov.pl/sejm.html#_szczeg%C3%B3%C5%82y_g%C5%82osowania). Dokumentacja opisuje `GET /sejm/term10/votings/{sitting}/{votingNumber}`, imienne `votes` z polem `MP` i URL wyników `/pdf`. Wspólny transport `SejmClient` ogranicza rozmiar JSON oraz czas całej odpowiedzi, także jej body.

Autorytatywny format pliku koordynatora został odczytany z `data/bill-votings.json`: `{ "bills": [pełna odpowiedź kontraktu] }`, bez osobnego pola `snapshot`. Wpisy są ręcznym mapowaniem i migawkami. Plik jest tylko odczytywany. Obecna migawka dotyczy `10-2693`, głosowania `10-66-103`, z pustą listą głosów imiennych.

Próba pobrania szczegółów `https://api.sejm.gov.pl/sejm/term10/votings/66/103` przez web zakończyła się timeoutem. Nie uzyskano surowego JSON szczegółów i nie utworzono `/tmp/sejm-voting-66-103.json`. Nie zastąpiono go danymi wymyślonymi, testowymi ani samą listą głosowań. Implementacja obsługuje pełną odpowiedź, kiedy API będzie dostępne dla uruchomionego backendu.

## Finalny kontrakt HTTP

`GET /api/bills/{id}/votings` zwraca:

```text
{
  billId: string,
  verifiedAt: string | null,
  sourceUrl: string | null,
  defaultVotingId: string | null,
  votings: [{
    id: string, term: integer, sitting: integer, votingNumber: integer,
    date: string, title: string, description: string,
    category: "final" | "amendment" | "other",
    sourceUrl: string, pdfUrl: string,
    yes: integer | null, no: integer | null, abstain: integer | null,
    notParticipating: integer | null, totalVoted: integer | null,
    votes: [{ MP: integer, firstName: string, lastName: string,
              club: string | null, vote: string }]
  }],
  mode: "snapshot" | "live",
  stale: boolean,
  error: null | string
}
```

Pola null pozostają w JSON, niezależnie od globalnej konfiguracji Jackson NON_NULL. Niepotwierdzone liczniki nie są wyliczane. W szczególności `notParticipating:null` nie jest zastępowane przez `460 - totalVoted`. Obecny snapshot ma znane liczniki, w tym potwierdzone przez koordynatora 21 nieuczestniczących; brak danych imiennych pozostaje `votes:[]`.

`votes:[]` oznacza brak danych imiennych, a nie brak głosowania. Niepusta lista z API jest traktowana jako komplet danych imiennych i walidowana względem dostępnych liczników. Kategorie, kolejność i `defaultVotingId` pochodzą wyłącznie z ręcznego mapowania: ostatnie głosowanie nad poprawką nie staje się automatycznie głosowaniem nad całością. W trybie live top-level `sourceUrl` zachowuje źródło ręcznego powiązania, natomiast URL-e poszczególnych głosowań wskazują oficjalny endpoint szczegółów i PDF.

`verifiedAt` w snapshot jest zachowywane dosłownie z pliku koordynatora. W live jest czasem pobrania i walidacji pełnych danych, w UTC jako ISO Instant. `date` głosowania jest zachowywane z API, bez dopisywania niepotwierdzonej strefy czasowej.

Głosy zachowują oryginalne wartości API: `YES`, `NO`, `ABSTAIN`, `ABSENT`, `NO_VOTE`, `VOTE_VALID`, `VOTE_INVALID`, `PRESENT`. `NO_VOTE` nie jest zamieniane na `ABSENT`; semantyka tych wartości nie jest utożsamiana. Koordynator został poinformowany, że frontend wcześniej traktował `NO_VOTE` jako unknown. Backend nie zmienia tego przez zgadywanie i nie edytuje frontendu.

### Rozróżnienie braku danych i błędów

- 200 z danymi snapshot: migawka jest dostępna od razu, odświeżanie działa w tle. Dla głosowań wymagających pobrania `stale:true` oznacza, że odpowiedź jest fallbackiem, nie świeżo pobranym kompletem. Podczas pierwszego pobierania `error:null`; po porażce `error` wyjaśnia awarię.
- 200 live: ostatni poprawny komplet z API lub dysku. `stale:false` tylko w okresie świeżości, bez błędów. Po nieudanym odświeżeniu dane zostają, ale odpowiedź ma `stale:true` i `error`.
- 200 z pustą listą: ustawa istnieje w backendzie i nie ma powiązań w poprawnie odczytanym pliku, albo poprawny wpis mapowania jawnie zawiera pustą listę. Nie uruchamia się wyszukiwania głosowań na podstawie tytułu. Dla istniejącej ustawy bez wpisu `verifiedAt`, `sourceUrl` i `defaultVotingId` są null.
- 404 ProblemDetail: poprawny składniowo identyfikator ustawy nie istnieje ani w backendzie, ani w poprawnym mapowaniu. Sam wpis w mapowaniu wystarcza do rozpoznania `10-2693`, nawet przed pojawieniem się tej ustawy w cache Bill.
- 503 ProblemDetail: mapowania nie da się odczytać i nie ma fallbacku dla żądanego projektu. Awaria pliku nie udaje braku głosowań.
- 400 ProblemDetail: nieprawidłowy format identyfikatora ustawy.

Błędy HTTP korzystają z istniejącego `ApiErrors` i pola `detail`. Błąd odświeżania przy istniejącym fallbacku jest polem `error` odpowiedzi 200.

## Odświeżanie i cache

Oddzielny `VotingController` i `VotingService` nie zmieniają odpowiedzi szczegółów Bill ani synchronizacji PDF. Odczyt głosowań zwraca dostępne dane, a przeterminowane dane odświeża na żądanie w osobnym workerze. Jedno zadanie na dany wpis, jeden worker, kolejka maksymalnie 100 zadań. Nie ma oczekiwania na sieć w żądaniu HTTP ani cyklicznego pobierania nieodwiedzanych ustaw. Domyślny TTL to 30 minut, odstęp ponowienia po błędzie 60 sekund. Walidacja interwałów zapobiega zerowym/ujemnym wartościom.

Cache znajduje się w `${DATA_DIR}/votings/{billId}.json`, oddzielnie od istniejącego cache Bill/PDF. Zawiera wersję formatu, SHA-256 ręcznego wpisu, czas pobrania i pełną odpowiedź live. Nowy plik zastępuje stary dopiero po pobraniu i walidacji wszystkich powiązanych głosowań oraz zapisaniu pliku tymczasowego. Wspierane jest atomowe przeniesienie, z fallbackiem na replace dla filesystemów bez ATOMIC_MOVE. Nie kasuje się cache przy błędzie pliku, sieci, walidacji ani zmianie mapowania. Usuwane są wyłącznie własne pliki tymczasowe.

Zmiana ręcznego wpisu unieważnia użycie starego cache dla tego wpisu, ale nie usuwa go z dysku. Zapisana odpowiedź musi odpowiadać ID, liczbie i kolejności mapowanych głosowań, ich kategoriom i domyślnemu ID. Zadanie sprawdza aktualność mapowania przed zapisem. Błędna podmiana pliku pozostawia ostatnie poprawne mapowanie w pamięci z jawnym błędem. Po restarcie, przy niedostępnym mapowaniu, poprawny cache dyskowy danego projektu jest dopuszczany jako oznaczony stale fallback; bez niego odpowiedź to 503.

Walidacja obejmuje kadencję 10, zgodność ID z sitting/votingNumber, unikalne billId i voting ID, defaultVotingId należące do listy, dozwolone kategorie, daty i oficjalne URL-e. Liczniki są ograniczone do 0–460. Gdy yes/no/abstain/totalVoted są znane, suma musi być zgodna. Przy niepustych danych imiennych kontrolowane są unikalne MP i liczby YES/NO/ABSTAIN. Przy znanych totalVoted/notParticipating sprawdzana jest kompletność liczby rekordów. Licznik ABSENT jest porównywany z notParticipating wyłącznie gdy wszystkie głosy należą do jednoznacznego zestawu YES/NO/ABSTAIN/ABSENT; nie zgaduje się znaczenia NO_VOTE. Sprzeczna odpowiedź API nie jest utrwalana. Snapshot z pustymi votes jest prawidłowy.

Mapowania i cache mają limit pliku 10 MiB, do 100 ustaw i 100 głosowań na ustawę; pojedyncza lista imienna ma maksymalnie 460 rekordów. Dostęp do pliku mapowań jest synchronizowany, a zmiana wykrywana po rozmiarze i czasie modyfikacji. Istniejące cache Bill i PDF nie są ruszane.

## Konfiguracja

| Zmienna | Domyślnie |
| --- | --- |
| `BILL_VOTINGS_PATH` | `../data/bill-votings.json` |
| `VOTINGS_BASE_URL` | `https://api.sejm.gov.pl/sejm/term10/votings` |
| `VOTINGS_REFRESH_MINUTES` | `30` |
| `VOTINGS_RETRY_SECONDS` | `60` |
| `DATA_DIR` | istniejące `../data/cache` |
| `CONNECT_TIMEOUT_SECONDS` | istniejące `10` |
| `REQUEST_TIMEOUT_SECONDS` | istniejące `60` |
| `MAX_JSON_BYTES` | istniejące `20971520` |

Ścieżki względne liczone od cwd backendu. Używany jest istniejący CORS `/api/**`. PDF wyników pozostaje linkiem do oficjalnego źródła; nie dodano pobierania ani cache plików PDF głosowań. Wdrożenie zakłada jedną instancję zapisującą katalog cache, tak jak dotychczasowy backend.

## Testy i ograniczenia weryfikacji

Dodano `VotingValidationTest`, `VotingServiceTest`, `VotingClientTest`, `VotingControllerTest` i pomocnicze `VotingFixtures`.

Zakres: autorytatywny format pliku, null i puste votes, MP w JSON, brak normalizacji NO_VOTE, sumy i sprzeczne głosy, unikalność MP i zgodność ID/defaultVotingId; natychmiastowy snapshot i jedno odświeżenie w tle; trwały cache po restarcie; zachowanie starego pliku przy awarii; rozróżnienie 200 empty/404/503; zachowanie ostatniego mapowania po uszkodzeniu; kategorie i ręczny default; lokalny serwer HTTP z formatem API, błędnym ID/licznikami, przekroczeniem limitu i zatrzymanym body; serializacja odpowiedzi i ProblemDetail.

`./backend/mvnw test` uruchomiono: exit 127, brak Mavena. Java/JVM są niedostępne. Testy nie zostały skompilowane ani wykonane i nie są raportowane jako przechodzące. Nie instalowano narzędzi. Oficjalne dane imienne 66/103 nie zostały pobrane z powodu timeoutu, więc nie potwierdzono działania end-to-end na tym głosowaniu. Sprawdzono statycznie źródła, literały i bilans nawiasów Java, konfigurację YAML oraz składnię istniejącego launchera. To nie zastępuje kompilacji ani testów JVM.

Kontrakt finalny pozostaje zgodny z przekazanym formatem i uzupełnieniami koordynatora. Świadome doprecyzowania: null dla nieznanych danych, UTC verifiedAt w live, statusy 404/503 zgodnie z review oraz zachowanie surowego NO_VOTE. Brak źródłowych imiennych danych snapshotu pozostaje jawny. Do weryfikacji w środowisku docelowym pozostają testy JVM i rzeczywiste pobranie szczegółów głosowania.
