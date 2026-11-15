# Źródła pierwszej analizy

## Głosowania — druk 2693

`data/bill-votings.json` wiąże projekt `10-2693` z głosowaniem nad całością z 8.10.2026: kadencja 10, posiedzenie 66, głosowanie 103. [Przebieg prac w Sejmie](https://www.sejm.gov.pl/Sejm10.nsf/PrzebiegProc.xsp?nr=2693) potwierdza identyfikator i wynik 242 za, 61 przeciw, 136 wstrzymujących się (łącznie 439). Nie jest to głosowanie nad późniejszymi poprawkami Senatu ani nad odrzuceniem weta.

[API głosowania](https://api.sejm.gov.pl/sejm/term10/votings/66/103) i jego PDF zwracały timeout podczas przygotowania migawki. [Lista głosowań posiedzenia 66](https://api.sejm.gov.pl/sejm/term10/votings/66) była dostępna i potwierdziła dodatkowo 21 niegłosujących oraz czas 20:54:10. Początkowy zapis nie zawiera głosów imiennych: `votes: []`. Puste dane imienne nie oznaczają, że nikt nie głosował. Funkcjonalność imienna korzysta z rzeczywistego API po udanym pobraniu; nie uzupełniamy migawki wymyślonymi nazwiskami lub głosami. Zestawienia klubowe należy liczyć z historycznego pola `club` konkretnego głosowania.

[Dokumentacja API](https://api.sejm.gov.pl/votings.html) opisuje wyniki zbiorcze oraz listę `votes` w szczegółach głosowania. Powiązania POC są ręcznie sprawdzane; migawka nie jest kompletną historią głosowań nad projektem.

## Druk 2693 — dodatkowa ustawa POC

Dodano rekord `10-2693` i streszczenie głównych rozwiązań po pracach parlamentarnych. Weryfikacja: 15.11.2026.

- [Metadane druku w API Sejmu](https://api.sejm.gov.pl/sejm/term10/prints/2693) potwierdzają tytuł, kadencję i datę doręczenia 17.06.2026.
- [Przebieg procesu](https://www.sejm.gov.pl/sejm10.nsf/PrzebiegProc.xsp?nr=2693) rozróżnia projekt od późniejszego tekstu ustawy po poprawkach.
- [Informacja Senatu, punkt 7](https://www.senat.gov.pl/prace/posiedzenia/tematy,668,1.html) opisuje cel regulacji: procedurę blokowania dostępu do nielegalnych treści.
- Formalny wniosek Prezydenta podlinkowany poniżej potwierdza weto z 13.11.2026 do ustawy z 22.10.2026, powiązanie z drukiem 2693, zakres nakazów i odrębny tryb sądowy. Opis mechanizmów i zarzuty Prezydenta przedstawiono oddzielnie.

Pełny PDF druku 2693 nie został pobrany (timeout narzędzia WWW, brak DNS w shellu). Streszczenie opiera się na wymienionych źródłach urzędowych; nie jest pełną analizą artykułów. Link PDF w rekordzie prowadzi do pierwotnego projektu, a streszczenie jawnie opisuje późniejszą ustawę. Nie ustalano hipotetycznej daty wejścia w życie zawetowanej ustawy.

Stan weryfikacji: 15 listopada 2026 r. Dane startowe są migawką, nie wynikiem działającej synchronizacji.

- [Lista druków X kadencji](https://api.sejm.gov.pl/sejm/term10/prints) — najnowszy odnaleziony projekt ustawy: 3355, data doręczenia 13.11.2026. Późniejsze pisma do starszych druków nie zmieniają daty złożenia projektu.
- [Karta druku 3355](https://www.sejm.gov.pl/sejm10.nsf/druk.xsp?nr=3355).
- [Źródłowy PDF](https://api.sejm.gov.pl/sejm/term10/prints/3355/3355.pdf) — odczytano treść projektu i uzasadnienia; streszczenie odnosi się do art. 1, 8 i 12. Numery stron PDF 2–19 obejmują projekt; cały druk ma 159 stron wraz z materiałami towarzyszącymi.
- [Dokumentacja pobierania druków i załączników](https://api.sejm.gov.pl/prints.html).

Pierwsze streszczenie przygotował asystent w tej sesji. Aplikacja nie wywołuje dostawców AI. Kolejne pobrane dokumenty analizuje lokalny algorytm ekstraktywny, którego wynik jest osobno oznaczony. To wybór fragmentów dokumentu i reguły tekstowe, a nie model językowy ani pełna interpretacja prawna.

## Stanowisko Prezydenta — migawka z 15.11.2026

- [Oficjalna lista ustaw zawetowanych](https://www.prezydent.pl/prawo/ustawy-zawetowane).
- [Komunikat i wypowiedź z 13.11.2026](https://www.prezydent.pl/prawo/ustawy-zawetowane/prezydent-podpisal-szesc-ustaw-jedna-zawetowal,129977) — zawiera zarówno ustawy podpisane, jak i odmowę podpisania. Samo wystąpienie numeru druku w artykule nie oznacza weta.
- [Formalny wniosek Prezydenta, PDF dostępny](https://www.prezydent.pl/storage/file/core_files/2026/11/13/a821a0a649b55f3d82daf1f3957b6b65/Wniosek%20Prezydenta%20RP%20ws.%20nowelizacji%20ustawy%20o%20%C5%9Bwiadczeniu%20us%C5%82ug%20drog%C4%85%20elektroniczn%C4%85%20%5BPDF%20dost%C4%99pny%5D.pdf) — pierwsza strona zawiera odmowę podpisania; strona druga identyfikuje druk 2693. Odczytano argumentację dotyczącą blokowania treści, istniejących instrumentów oraz proporcjonalności.

Skrót w `data/presidential-comments.json` jest opracowaniem stanowiska Prezydenta. Nie przedstawia jego zarzutów jako rozstrzygnięcia sądu ani stanowiska Goniec z Wiejskiej. Weryfikacja dotyczy konkretnego wpisu, a nie kompletności historii wet. Migawka nie jest aktualizowana przez scheduler Sejmu.
