# Goniec z Wiejskiej — frontend

Samodzielny frontend po polsku: HTML, CSS i vanilla JavaScript ES modules. Bez zewnętrznych pakietów, fontów i kroku budowania. Nie zmieniono backendu, głównego README, seeda ani dostarczonego Dockerfile.

## Uruchamianie

Z katalogu `frontend/`: `npm run dev` lub `npm start`. Domyślny port: 5173, opcjonalnie `PORT`. Serwer nasłuchuje na `0.0.0.0`. Proxy `/api` używa `BACKEND_URL` lub `http://localhost:8080`. Dla compose: `BACKEND_URL=http://backend:8080`.

Serwer przekazuje metody, query string, body i statusy backendu, w tym błędy. Odpowiedź jest buforowana przed wysłaniem nagłówków, aby błąd odczytu body umożliwiał bezpieczny fallback. Limit oczekiwania na upstream: 4 sekundy.

Gdy backend jest niedostępny, GET listy i szczegółów czyta `../data/seed-bills.json` (tablica lub `{bills: [...]}`). Status jawnie informuje „Tryb przykładowy — backend niedostępny”, daty synchronizacji pozostają puste, a POST synchronizacji zwraca wyjaśniający błąd 503. Brak lub uszkodzenie seeda również zwraca 503. Oficjalny PDF z seeda otwierany jest bezpośrednio, a backendowe względne `/api/bills/{id}/pdf` przechodzi przez proxy.

## Interfejs

Responsywny układ kremowego papieru, leśnej zieleni i ceglastych akcentów, wyszukiwanie lokalne w tytule, numerze i streszczeniu, filtr statusu, karty/lista, szczegóły przez hash URL, źródło i PDF, metoda analizy z danych, daty prób i udanych synchronizacji. Po POST status jest odpytywany co 2 sekundy podczas synchronizacji; w tle co 30 sekund. Są stany ładowania, błędów, ponowienia i pustych wyników. Brak sztucznych statystyk.

Treści danych są escapowane. Linki dopuszczają HTTP/HTTPS bez danych logowania, a lokalne adresy tylko do endpointu PDF. Pola formularzy mają etykiety, przyciski widoku aria-pressed, komunikaty aria-live, widoczny fokus i link pomijający nagłówek. Opracowanie nie zastępuje dokumentu źródłowego.

## Weryfikacja

`npm test` uruchamia `node --test`, bez zależności. Testy bez otwierania portów sprawdzają handler HTTP: proxy, query, POST i body, status błędu upstream, BACKEND_URL, awarię odczytu body przed nagłówkami, fallback listy/szczegółów/statusu, brak projektu, uszkodzony seed, odmowę synchronizacji, pliki statyczne, HEAD oraz brak dostępu do dowolnych ścieżek. Kontrola składni: `node --check app.js` i `node --check server.mjs`.

Próba uruchomienia `node frontend/server.mjs` w środowisku zadania zakończyła się `listen EPERM: operation not permitted 0.0.0.0:5173`. Testów po rzeczywistym połączeniu HTTP ani wizualnej weryfikacji przeglądarkowej nie wykonano z powodu ograniczeń środowiska. Nie eskalowano uprawnień. Testy handlera nie zastępują testu sieciowego.

## Drugi projekt POC — 10-2693

Frontend obsługuje oba rekordy seeda bez specjalnych wyjątków. Test interfejsu korzysta z rzeczywistego rekordu `10-2693`: sprawdza listę, wyszukiwanie po numerze i tytule, szczegóły z niezmienionym streszczeniem oraz powiązane weto z 13.11.2026. Stanowisko jest wyświetlane tylko w szczegółach; strona główna, karty i lista go nie pokazują. Test handlera HTTP sprawdza listę i szczegóły obu rekordów w trybie offline oraz zapisane stanowisko. Nie zmieniano seeda ani dokumentu źródeł.

Istotne przy istniejącym backendzie: `CacheStore.load()` zwraca poprawny cache zamiast importować nowy seed. Synchronizacja w `BillService` pobiera tylko najnowszy projekt, więc nie uzupełni automatycznie starszego druku 2693. Proxy celowo zachowuje odpowiedzi backendu (także brak rekordu/404); nie miesza danych produkcyjnych z seedem. W repozytorium nie znaleziono lokalnego `cache.json`; compose przechowuje go w osobnym wolumenie `sejm-cache`, którego zawartości nie zweryfikowano. Nie usuwano ani nie modyfikowano cache. Uruchomienie backendu z odrębnym, nowym `DATA_DIR` pozwala odczytać aktualny seed bez naruszania istniejącego cache.

Ponowna próba uruchomienia serwera nadal kończy się `listen EPERM 0.0.0.0:5173`. Weryfikacja fallbacku obejmuje handler żądań HTTP bez socketów; nie potwierdza działania przez rzeczywistą sieć ani wyglądu w przeglądarce.

## Głosowania w szczegółach ustawy

Moduł `votings.js` pobiera `/api/bills/{id}/votings` wyłącznie po otwarciu szczegółów. Pokazuje przedmiot, datę, kategorię, wyniki zbiorcze i oficjalne źródła. Wybiera `defaultVotingId`, następnie głosowanie `final`, a w ostateczności pierwszy dostępny rekord, z jawną kategorią. Historia pozwala przełączać głosowania. Rozkład według historycznego klubu i rozwijana lista posłów z wyszukiwaniem nazwiska oraz filtrami klub/głos działają na zapisanych głosach imiennych. Pusta lista nie tworzy zerowych wyników klubów; `null` w sumach oznacza brak danych. Nieznane wartości głosu są jawne i nie zwiększają liczby niegłosujących.

Timestamp bez offsetu jest pokazywany wiernie jako czas polski; timestamp z offsetem/Z jest przeliczany na Europe/Warsaw. Test porównuje wynik w UTC, Warszawie, Tokio i Los Angeles.

Ręczny przycisk „Odśwież wyniki” pozwala pobrać dane po zakończeniu pracy backendu w tle, bez nieskończonego pollingu. Błąd odświeżenia pozostawia wcześniejsze dane. Błąd pierwszego pobrania jest osobnym stanem z ponowieniem i nie usuwa szczegółów ustawy ani sekcji Prezydenta. Kontrola numeru żądania blokuje spóźnione odpowiedzi i odświeżanie po zmianie widoku.

Offline serwer czyta `../data/bill-votings.json` w formacie `{bills:[pełna odpowiedź kontraktu]}` i dodaje `demo:true`. Znana ustawa bez mapowania dostaje pustą listę głosowań i HTTP 200; nieznana — 404. Uszkodzony lub brakujący plik — 503. Backendowe 404/500 są przekazywane bez zastępowania snapshotem. Dockerfile kopiuje plik koordynatora. Zmiany ograniczono do `frontend/`.

Oficjalna dokumentacja https://api.sejm.gov.pl/sejm.html wymienia `NO_VOTE` i `ABSENT`, lecz nie wyjaśnia różnicy między nimi. Do czasu potwierdzenia semantyki `NO_VOTE` jest pokazywane jawnie jako nierozpoznany głos i nie jest utożsamiane z `ABSENT`. Test chroni ten warunek.

Weryfikacja: testy na jawnie syntetycznych głosach obejmują agregację, nieznane enum, filtry, historię, bezpieczne linki i escapowanie, błędy, odświeżanie oraz wyścig nawigacji. Testy interfejsu sprawdzają brak sekcji na landing page/kartach/liście, stan błędu bez utraty szczegółów oraz rzeczywisty snapshot 10-2693 bez imiennych danych. Testy handlera sprawdzają fallback z rzeczywistymi sumami oraz błędy upstream. `npm test` i kontrola składni przechodzą. Próba uruchomienia serwera nadal kończy się `listen EPERM 0.0.0.0:5173`; nie wykonano rzeczywistego połączenia HTTP ani przeglądarkowej kontroli wyglądu. Pobrania danych live nie zweryfikowano; snapshot nie zawiera głosów imiennych.

### Oficjalne wyniki klubowe bez głosów imiennych

Opcjonalne `Voting.clubResults` ma pierwszeństwo przed agregacją `votes`. Tabela pokazuje członków, głosujących, za/przeciw/wstrzymanie i niegłosujących bez zmiany historycznych nazw klubów i kół. Dane imienne pozostają osobnym widokiem; przy pustym `votes` nadal pojawia się komunikat „Brak zapisanych głosów imiennych”, nawet gdy oficjalna tabela klubowa jest dostępna. Nie są tworzone nazwiska na podstawie agregatów.

Starsze rekordy bez `clubResults` zachowują agregację głosów imiennych, w tym osobną kolumnę nierozpoznanych głosów. W takim przypadku „Łącznie” oznacza liczbę zapisanych pozycji, a nie domniemaną liczebność klubu. Niepełne wartości liczbowe są jawnie oznaczane jako brak danych.

Obok PDF jest link do oficjalnego HTML `Glosowanie.xsp` z kadencją, posiedzeniem i numerem. `clubResultsSourceUrl` musi wskazywać HTTPS na www.sejm.gov.pl i to samo głosowanie. Przy braku lub niepoprawnym adresie URL jest budowany wyłącznie z dodatnich bezpiecznych liczb całkowitych. Druk 2693 pozostaje powiązany z 66/103; przykład 68/40 nie jest używany jako jego wynik. Próba otwarcia strony Sejmu narzędziem WWW nie powiodła się; nie potwierdzono dostępności strony przez sieć w środowisku zadania.

Fallback serwera zachowuje opcjonalne pola bez przekształceń — test porównuje całą odpowiedź snapshotu z polami klubowymi i własnym źródłem. Testy syntetyczne obejmują clubResults bez votes, pierwszeństwo bez podwajania, zgodność starych rekordów, brak sztucznych nazwisk, escapowanie, bezpieczne źródła i odrzucanie spóźnionych odpowiedzi po nawigacji. Dotychczasowe testy wyłączności szczegółów oraz sekcji Prezydenta pozostają aktywne.


## Identyfikacja „Goniec z Wiejskiej”

Gazetowy masthead, systemowe szeryfy Georgia/Times, kremowy papier z delikatną fakturą CSS, ciemny tusz, leśna zieleń i ceglasty akcent. Autorska winieta wektorowa `assets/paperboy.svg` przedstawia chłopca w kaszkiecie z gazetą, torbą prasy i rowerem na tle pól. Bez zewnętrznych fontów, obrazów i zależności. Ilustracja ma viewBox, opis dostępności i jawne proporcje w HTML.

Masthead skaluje typografię do szerokości 320/375 px; na małych ekranach dodatkowa linia lokalizacyjna jest pomijana, a link redakcyjny przechodzi pod nazwę. Hero jest zastępowane kompaktowym panelem statusu w szczegółach; ilustracja i tekst powitalny nie zajmują miejsca nad ustawą. Nawigacja przywraca je na stronie głównej. Tabele głosowań mają własny poziomy scroll i nie rozszerzają kolumny szczegółów.

Serwer udostępnia wyłącznie allowlistowany `/assets/paperboy.svg` z `image/svg+xml; charset=utf-8` i `nosniff`; HEAD zwraca te same nagłówki bez body. Testy obejmują asset, HEAD, blokadę innych ścieżek, aktualną markę i kompaktowy widok szczegółów. Dotychczasowe testy ustaw, stanowisk Prezydenta, głosowań i fallbacków pozostają aktywne. Historyczna nazwa nagłówka technicznego `X-Simply-Sejm-Mode` pozostała dla zgodności kontraktu; nie jest treścią interfejsu. Dane źródłowe i przypisane im opisy nie są przepisywane.

Koordynator wyrenderował i zaakceptował winietę SVG. W tym środowisku nie wykonano przeglądarkowej weryfikacji layoutu; ograniczenie uruchomienia portu opisano wyżej. Responsywność 320/375 px sprawdzono na poziomie reguł układu, nie zrzutów z przeglądarki.
