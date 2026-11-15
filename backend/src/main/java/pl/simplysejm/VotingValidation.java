package pl.simplysejm;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

final class VotingValidation {
    private VotingValidation() {}
    static final Set<String> VOTES = Set.of("YES", "NO", "ABSTAIN", "NO_VOTE", "ABSENT", "VOTE_VALID", "VOTE_INVALID", "PRESENT");
    static void validate(BillVotings.Response bill) throws IOException {
        check(bill != null && validBillId(bill.billId()), "Nieprawidłowy billId");
        check(date(bill.verifiedAt()) && officialUrl(bill.sourceUrl()), "Brak potwierdzonego źródła lub daty weryfikacji");
        check(Set.of("snapshot", "live").contains(Objects.toString(bill.mode(), "")), "Nieprawidłowy mode");
        check(bill.votings() != null && bill.votings().size() <= 100, "Nieprawidłowa lista głosowań");
        Set<String> ids = new HashSet<>();
        for (var v : bill.votings()) {
            validate(v);
            check(ids.add(v.id()), "Powtórzony identyfikator głosowania");
        }
        check(bill.defaultVotingId() == null || ids.contains(bill.defaultVotingId()), "defaultVotingId poza powiązaniami");
    }
    static boolean validBillId(String id) { return id != null && id.matches("10-[1-9][0-9]*"); }
    static void validate(BillVotings.Voting v) throws IOException {
        check(v != null && v.term() == 10 && v.sitting() > 0 && v.votingNumber() > 0, "Nieprawidłowe identyfikatory głosowania");
        check(Objects.equals(v.id(), "10-" + v.sitting() + "-" + v.votingNumber()), "ID głosowania nie odpowiada metadanym");
        check(date(v.date()) && v.title() != null && !v.title().isBlank() && v.description() != null, "Nieprawidłowy opis lub data głosowania");
        check(Set.of("final", "amendment", "other").contains(Objects.toString(v.category(), "")), "Nieprawidłowa kategoria");
        check(officialUrl(v.sourceUrl()) && officialUrl(v.pdfUrl()), "Nieprawidłowe źródło głosowania");
        for (Integer count : Arrays.asList(v.yes(), v.no(), v.abstain(), v.notParticipating(), v.totalVoted()))
            check(count == null || (count >= 0 && count <= 460), "Nieprawidłowa liczba głosów");
        if (v.yes() != null && v.no() != null && v.abstain() != null && v.totalVoted() != null)
            check(v.yes() + v.no() + v.abstain() == v.totalVoted(), "Sprzeczna suma głosów");
        check(v.votes() != null && v.votes().size() <= 460, "Nieprawidłowa lista głosów imiennych");
        Set<Integer> members = new HashSet<>();
        for (var vote : v.votes()) {
            check(vote != null && vote.mp() > 0 && members.add(vote.mp()), "Powtórzony lub nieprawidłowy MP");
            check(vote.firstName() != null && !vote.firstName().isBlank() && vote.lastName() != null && !vote.lastName().isBlank()
                    && VOTES.contains(Objects.toString(vote.vote(), "")), "Nieprawidłowy głos imienny");
        }
        if (!v.votes().isEmpty()) {
            tally(v, "YES", v.yes()); tally(v, "NO", v.no()); tally(v, "ABSTAIN", v.abstain());
            if (v.totalVoted() != null && v.notParticipating() != null)
                check(v.totalVoted() + v.notParticipating() == v.votes().size(), "Niepełna lub sprzeczna lista imienna");
            // NO_VOTE is preserved verbatim. Its meaning is not assumed to equal ABSENT.
            if (v.notParticipating() != null && v.votes().stream().allMatch(vote ->
                    Set.of("YES", "NO", "ABSTAIN", "ABSENT").contains(vote.vote())))
                tally(v, "ABSENT", v.notParticipating());
        }
        // Do not infer missing totals or notParticipating from 460 or an empty roll call.
        validateClubs(v);
    }
    static boolean hasLiveData(BillVotings.Voting voting) {
        return !voting.votes().isEmpty() || (voting.clubResults() != null && !voting.clubResults().isEmpty());
    }
    private static void validateClubs(BillVotings.Voting v) throws IOException {
        if (v.clubResults() == null || v.clubResults().isEmpty()) {
            check(v.clubResultsSourceUrl() == null && v.clubResultsVerifiedAt() == null, "Źródło klubów bez wyników");
            return;
        }
        check(v.clubResults().size() <= 100 && Objects.equals(v.clubResultsSourceUrl(),
                VotingHtmlParser.sourceUrl(v.term(), v.sitting(), v.votingNumber()))
                && date(v.clubResultsVerifiedAt()), "Niepotwierdzone źródło lub tożsamość wyników klubowych");
        Set<String> clubs = new HashSet<>();
        int members = 0, voted = 0, yes = 0, no = 0, abstain = 0, absent = 0;
        for (var c : v.clubResults()) {
            check(c != null && c.club() != null && !c.club().isBlank()
                    && clubs.add(c.club().strip().toLowerCase(Locale.ROOT)), "Powtórzony lub pusty klub");
            check(c.members() != null && c.totalVoted() != null && c.yes() != null && c.no() != null
                    && c.abstain() != null && c.notParticipating() != null, "Niekompletne liczniki klubu");
            for (int count : new int[] { c.members(), c.totalVoted(), c.yes(), c.no(), c.abstain(), c.notParticipating() })
                check(count >= 0 && count <= 460, "Nieprawidłowy licznik klubu");
            check(c.members() > 0 && c.yes() + c.no() + c.abstain() == c.totalVoted()
                    && c.totalVoted() + c.notParticipating() == c.members(), "Sprzeczne sumy klubu");
            members += c.members(); voted += c.totalVoted(); yes += c.yes(); no += c.no(); abstain += c.abstain(); absent += c.notParticipating();
        }
        check(members <= 460 && v.totalVoted() != null && v.yes() != null && v.no() != null && v.abstain() != null
                && v.notParticipating() != null && voted == v.totalVoted() && yes == v.yes() && no == v.no()
                && abstain == v.abstain() && absent == v.notParticipating(), "Tabela klubów nie zgadza się z wynikiem głosowania");
    }
    private static void tally(BillVotings.Voting v, String value, Integer expected) throws IOException {
        if (expected != null) check(v.votes().stream().filter(vote -> value.equals(vote.vote())).count() == expected,
                "Sprzeczny wynik imienny " + value);
    }
    static void check(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
    private static boolean date(String value) {
        if (value == null) return false;
        try { if (value.length() == 10) LocalDate.parse(value); else LocalDateTime.parse(value); return true; }
        catch (RuntimeException e) {
            try { java.time.OffsetDateTime.parse(value); return true; } catch (RuntimeException ignored) { return false; }
        }
    }
    private static boolean officialUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            return "https".equals(uri.getScheme()) && Set.of("api.sejm.gov.pl", "www.sejm.gov.pl", "sejm.gov.pl").contains(Objects.toString(uri.getHost(), ""))
                    && uri.getUserInfo() == null && uri.getPort() == -1;
        } catch (RuntimeException e) { return false; }
    }
}
