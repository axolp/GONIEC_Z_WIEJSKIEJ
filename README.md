# Goniec z Wiejskiej

Goniec z Wiejskiej przynosi wiadomości z parlamentu prostym językiem: streszczenia ustaw, stanowiska Prezydenta i wyniki głosowań. Oprawa łączy gazetową typografię, barwy papieru i tuszu oraz ilustrację chłopca roznoszącego gazety.

Strona zawiera także sekcję „Stanowisko Prezydenta” z opisem powodów weta, datą oraz linkami do wypowiedzi i formalnego uzasadnienia w PDF. Obecnie jest to zweryfikowana migawka, nie automatyczny monitoring prezydent.pl.

Backend: Java 21, Spring Boot, Maven i Apache PDFBox. Frontend: HTML, CSS i JavaScript, z serwerem Node.js bez zależności npm. Dwa komponenty komunikują się przez REST; przeglądarka korzysta z jednego adresu dzięki proxy frontendu.

## Szybki podgląd

Wymagany Node.js 22 lub nowszy:

```sh
npm run dev
```

Otwórz http://localhost:5173. Bez działającego backendu strona pokazuje wyraźnie oznaczony tryb przykładowy z prawdziwym projektem i przygotowanym streszczeniem. W tym trybie nie pobiera nowych projektów.

## Pełna aplikacja

Wymagane Java 21 i Maven 3.9+ oraz dostęp do internetu do pobrania zależności i źródeł Sejmu. W dwóch terminalach:

```sh
cd backend
mvn spring-boot:run
```

```sh
npm run dev
```

Drugi terminal uruchom w katalogu głównym projektu. Backend słucha na porcie 8080, frontend na 5173. Po starcie backend sprawdza dane Sejmu, a następnie powtarza sprawdzenie co 30 minut. Można też użyć przycisku na stronie. Harmonogram działa w procesie aplikacji — wymaga uruchomionego backendu.

Alternatywnie, z zainstalowanym Dockerem i Compose:

```sh
docker compose up --build
```

Przy uruchomieniu lokalnym cache pozostaje w `data/cache/`; Compose używa trwałego wolumenu `sejm-cache`, a seed montuje tylko do odczytu. Frontend obsługuje `PORT` i `BACKEND_URL`. Konfiguracja harmonogramu i magazynu danych znajduje się w `backend/src/main/resources/application.yml`; ta wersja pobiera druki X kadencji.

## Analiza bez API AI

Pierwszy skrót w `data/seed-bills.json` przygotował asystent na podstawie oryginalnego PDF druku 3355. [Źródła i zakres weryfikacji](docs/SOURCES.md).

Kolejne PDF-y są odczytywane przez PDFBox i opracowywane lokalnie metodą ekstraktywną. Wynik zawiera oznaczenie metody; brak rozpoznanej daty lub grupy odbiorców nie jest uzupełniany domysłami. Algorytm nie zastępuje analizy modelu językowego. Skany bez warstwy tekstowej wymagają OCR, którego ta wersja nie zawiera.

Serwis używa oficjalnego API Sejmu jako źródła dokumentów. Nie wymaga klucza ani integracji z zewnętrznym dostawcą AI. Wybór najnowszego projektu opiera się na dacie doręczenia i numerze druku; data modyfikacji starszego dokumentu nie czyni go nowym projektem.

## Sprawdzenie

```sh
npm run test:frontend
mvn -f backend/pom.xml test
```

W środowisku przygotowania projektu dostępny był Node.js; brakowało JDK, Mavena i Dockera, a terminal nie rozwiązywał nazw DNS. Sandbox blokował także otwarcie portu HTTP (`listen EPERM`). Dlatego podgląd w przeglądarce, kompilacja i pełna synchronizacja backendu wymagają sprawdzenia poza tym sandboxem.

## Stanowiska Prezydenta

`GET /api/presidential-comments` udostępnia `data/presidential-comments.json`. Ścieżkę backendu można zmienić przez `PRESIDENT_COMMENTS_PATH`. Compose montuje ten plik tylko do odczytu; obraz frontendu zawiera jego kopię dla podglądu bez backendu. Zmiana danych w obrazie wymaga ponownego zbudowania frontendu.

Plik zawiera `verifiedAt`, `mode: "snapshot"`, adres oficjalnej listy oraz tablicę `comments`. Każdy wpis zawiera datę, skrót stanowiska, argumenty, źródła i opcjonalne `billId` w formacie `kadencja-numer`. Powiązanie uzupełnia się wyłącznie po potwierdzeniu numeru druku w źródle. Podobieństwo tytułów nie wystarcza. Komunikat dotyczący kilku ustaw nie nadaje wszystkim wymienionym projektom statusu weta.

Pierwszy wpis dotyczy druku `10-2693`. Sekcja stanowiska jest widoczna wyłącznie w szczegółach ustawy z niepustym streszczeniem. Bez dopasowanego stanowiska pokazuje informację o braku danych. Nie pojawia się na stronie głównej ani kartach. Opinie i zarzuty Prezydenta są oddzielone od analizy ustawy; dodanie komentarza nie zmienia statusu opracowania projektu. Harmonogram Sejmu i przycisk synchronizacji nie aktualizują tej migawki.

## Głosowania

Sekcja „Jak głosowali posłowie?” jest dostępna wyłącznie w szczegółach ustawy. Pokazuje wynik zbiorczy, rozkład klubów i głosy imienne z wyszukiwaniem i filtrami, jeżeli dane imienne są dostępne. Historia rozróżnia głosowania nad całością i poprawkami; wynik głosowania nie oznacza wejścia ustawy w życie.

`GET /api/bills/{id}/votings` korzysta z ręcznie zweryfikowanych powiązań POC w `data/bill-votings.json`. Początkowe powiązanie dla druku `10-2693` to głosowanie `10-66-103`. Brak powiązania nie jest dowodem, że nad projektem nigdy nie głosowano. Nie jest to pełna historia prac nad ustawą.

Ścieżkę danych ustawia `BILL_VOTINGS_PATH`; Compose montuje plik tylko do odczytu, a obraz frontendu zawiera kopię do podglądu bez backendu. Początkowa migawka ma potwierdzone wyniki zbiorcze, ale nie ma głosów imiennych — interfejs jawnie informuje o tym ograniczeniu. Backend pobiera dane z API Sejmu, a przy jego niedostępności może odczytać zweryfikowaną tabelę klubów z oficjalnej strony wyników. Rozkład klubowy może być dostępny niezależnie od listy imiennej. Nieznany głos nie jest przypisywany do niegłosujących; klub pochodzi z konkretnego historycznego głosowania. [Źródła i zakres weryfikacji](docs/SOURCES.md).
