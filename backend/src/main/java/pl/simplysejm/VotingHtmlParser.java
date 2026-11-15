package pl.simplysejm;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Conservative parser of the official results table, never a source of individual votes. */
@Component
public class VotingHtmlParser {
    private static final Pattern HEADING = Pattern.compile("(?iu)^Głosowanie nr (\\d+) na (\\d+)\\. posiedzeniu Sejmu$");
    private static final Pattern WHEN = Pattern.compile("(?iu)dnia (\\d{2}-\\d{2}-\\d{4}) r\\. o godz\\. (\\d{2}:\\d{2}:\\d{2})");
    private static final Pattern PRINTS = Pattern.compile("(?iu)\\bdruk(?:i|u|ów|iem|ach)?(?:\\s+nr)?\\s+(\\d+(?:-[a-z0-9]+)?(?:\\s*(?:,|i|oraz)\\s*\\d+(?:-[a-z0-9]+)?)*)");
    private static final Set<String> KEYS = Set.of("club", "members", "totalVoted", "yes", "no", "abstain", "notParticipating");

    static String sourceUrl(int term, int sitting, int number) {
        if (term != 10 || sitting < 1 || number < 1) throw new IllegalArgumentException("Nieprawidłowa tożsamość głosowania HTML");
        return "https://www.sejm.gov.pl/Sejm" + term + ".nsf/Glosowanie.xsp?posiedzenie=" + sitting + "&glosowanie=" + number;
    }
    public BillVotings.Voting parse(byte[] html, BillVotings.Voting mapping) throws IOException {
        VotingValidation.validate(mapping);
        String url = sourceUrl(mapping.term(), mapping.sitting(), mapping.votingNumber());
        String raw = new String(html, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        VotingValidation.check(raw.contains("</html>") && raw.contains("</table>"), "Niekompletny HTML wyników");
        Document doc = Jsoup.parse(new ByteArrayInputStream(html), null, url);
        doc.select("script,style,noscript").remove();
        String body = normalize(doc.body().text());
        String lower = body.toLowerCase(Locale.ROOT);
        VotingValidation.check(!lower.contains("captcha") && !lower.contains("verify you are human")
                && !lower.contains("access denied") && !lower.contains("checking your browser")
                && doc.select("input[name*=captcha],iframe[src*=captcha]").isEmpty(), "HTML zawiera blokadę lub captcha");
        Element heading = null;
        for (Element h : doc.select("h1")) {
            Matcher m = HEADING.matcher(normalize(h.text()));
            if (m.matches()) {
                VotingValidation.check(heading == null && Integer.parseInt(m.group(1)) == mapping.votingNumber()
                        && Integer.parseInt(m.group(2)) == mapping.sitting(), "HTML przedstawia inne głosowanie");
                heading = h;
            }
        }
        VotingValidation.check(heading != null && confirmsTerm(doc, mapping), "HTML nie potwierdza kadencji lub tożsamości głosowania");
        int start = body.indexOf(normalize(heading.text())) + normalize(heading.text()).length();
        int end = body.indexOf("Wyniki indywidualne", start);
        if (end < 0) end = body.indexOf("Wyniki głosowania wg klubów", start);
        VotingValidation.check(end > start && end - start < 12000, "Brak przedmiotu głosowania w HTML");
        String subject = body.substring(start, end);
        Set<String> expected = expectedPrints(mapping);
        Set<String> actual = prints(subject);
        VotingValidation.check(expected.size() == 1 && !Collections.disjoint(expected, actual), "HTML nie potwierdza jednoznacznego druku z ręcznego powiązania");
        String normalizedSubject = subject.toLowerCase(Locale.ROOT);
        if ("final".equals(mapping.category())) VotingValidation.check(
                normalizedSubject.matches("(?s).*całoś(?:ć|cią|ci)\\s+(?:projektu\\s+)?ustawy.*"), "HTML nie potwierdza głosowania nad całością");
        if ("amendment".equals(mapping.category())) VotingValidation.check(normalizedSubject.contains("popraw"), "HTML nie potwierdza poprawki");
        Matcher when = WHEN.matcher(subject);
        VotingValidation.check(when.find(), "Brak daty głosowania HTML");
        String date;
        try { date = LocalDate.parse(when.group(1), DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)) + "T" + when.group(2); LocalDateTime.parse(date); }
        catch (RuntimeException e) { throw new IOException("Nieprawidłowa data HTML", e); }
        VotingValidation.check(date.substring(0, 10).equals(mapping.date().substring(0, 10)), "Data HTML nie odpowiada mapowaniu");
        String results = body.substring(end);
        int total = global(results, "(?<!nie )głosowało");
        int yes = global(results, "za"), no = global(results, "przeciw"), abstain = global(results, "wstrzymało się"), absent = global(results, "nie głosowało");
        checkKnown(mapping.totalVoted(), total); checkKnown(mapping.yes(), yes); checkKnown(mapping.no(), no);
        checkKnown(mapping.abstain(), abstain); checkKnown(mapping.notParticipating(), absent);
        List<BillVotings.ClubResult> clubs = table(doc);
        var result = new BillVotings.Voting(mapping.id(), mapping.term(), mapping.sitting(), mapping.votingNumber(), date,
                mapping.title(), mapping.description(), mapping.category(), url, mapping.pdfUrl(), yes, no, abstain, absent, total,
                List.of(), clubs, url, Instant.now().toString());
        VotingValidation.validate(result);
        return result;
    }
    private static List<BillVotings.ClubResult> table(Document doc) throws IOException {
        VotingValidation.check(normalize(doc.body().text()).contains("Wyniki głosowania wg klubów"), "Brak tabeli klubów");
        List<BillVotings.ClubResult> found = null;
        for (Element table : doc.select("table")) {
            Map<String, Integer> columns = null;
            List<BillVotings.ClubResult> rows = new ArrayList<>();
            BillVotings.ClubResult footer = null;
            for (Element row : table.select("tr")) {
                if (row.closest("table") != table) continue;
                List<Element> cells = row.children().stream().filter(c -> Set.of("th", "td").contains(c.tagName())).toList();
                if (cells.isEmpty()) continue;
                if (columns == null) {
                    Map<String, Integer> candidate = new HashMap<>();
                    for (int i = 0; i < cells.size(); i++) {
                        String key = header(cells.get(i).text());
                        if (key != null) VotingValidation.check(candidate.putIfAbsent(key, i) == null, "Powtórzony nagłówek tabeli");
                    }
                    if (candidate.keySet().equals(KEYS)) columns = candidate;
                    continue;
                }
                VotingValidation.check(cells.size() == columns.size() && cells.stream().noneMatch(c -> c.hasAttr("colspan") || c.hasAttr("rowspan")), "Niekompletny wiersz tabeli klubów");
                String club = normalize(cells.get(columns.get("club")).text());
                VotingValidation.check(!club.isBlank(), "Brak nazwy klubu");
                var parsed = new BillVotings.ClubResult(club, number(cells, columns, "members"), number(cells, columns, "totalVoted"),
                        number(cells, columns, "yes"), number(cells, columns, "no"), number(cells, columns, "abstain"), number(cells, columns, "notParticipating"));
                if (Set.of("razem", "suma", "ogółem").contains(club.toLowerCase(Locale.ROOT))) {
                    VotingValidation.check(footer == null, "Powtórzona suma tabeli"); footer = parsed;
                } else rows.add(parsed);
            }
            if (columns != null) {
                VotingValidation.check(found == null && !rows.isEmpty(), "Niejednoznaczna lub pusta tabela klubów");
                if (footer != null) VotingValidation.check(
                        rows.stream().mapToInt(BillVotings.ClubResult::members).sum() == footer.members()
                        && rows.stream().mapToInt(BillVotings.ClubResult::totalVoted).sum() == footer.totalVoted()
                        && rows.stream().mapToInt(BillVotings.ClubResult::yes).sum() == footer.yes()
                        && rows.stream().mapToInt(BillVotings.ClubResult::no).sum() == footer.no()
                        && rows.stream().mapToInt(BillVotings.ClubResult::abstain).sum() == footer.abstain()
                        && rows.stream().mapToInt(BillVotings.ClubResult::notParticipating).sum() == footer.notParticipating(), "Sprzeczna suma końcowa tabeli");
                found = List.copyOf(rows);
            }
        }
        VotingValidation.check(found != null, "Brak kompletnych nagłówków tabeli klubów");
        return found;
    }
    private static String header(String text) {
        return switch (normalize(text).toLowerCase(Locale.ROOT).replace(" ", "")) {
            case "klub/koło", "klub", "klub/kołoposelskie" -> "club";
            case "liczbaczł.", "liczbaczłonków" -> "members";
            case "głosowało" -> "totalVoted";
            case "za" -> "yes";
            case "przeciw" -> "no";
            case "wstrzymałosię" -> "abstain";
            case "niegłosowało" -> "notParticipating";
            default -> null;
        };
    }
    private static int number(List<Element> cells, Map<String, Integer> columns, String key) throws IOException {
        String value = normalize(cells.get(columns.get(key)).text());
        if (value.equals("-")) return 0;
        VotingValidation.check(value.matches("[0-9]{1,3}"), "Nieprawidłowa komórka liczbowa tabeli");
        int n = Integer.parseInt(value); VotingValidation.check(n <= 460, "Przekroczony licznik tabeli"); return n;
    }
    private static int global(String text, String label) throws IOException {
        Matcher m = Pattern.compile("(?iu)\\b" + label + "\\s*[-:]\\s*([0-9]{1,3})(?![0-9])").matcher(text);
        VotingValidation.check(m.find(), "Brak sumy HTML: " + label);
        return Integer.parseInt(m.group(1));
    }
    private static void checkKnown(Integer expected, int actual) throws IOException {
        VotingValidation.check(expected == null || expected == actual, "HTML ma wyniki sprzeczne z potwierdzonym mapowaniem");
    }
    private static Set<String> expectedPrints(BillVotings.Voting mapping) {
        Set<String> result = new HashSet<>();
        URI uri = URI.create(mapping.sourceUrl());
        if (uri.getPath().toLowerCase(Locale.ROOT).matches(".*/(?:druk|przebiegproc)\\.xsp") && uri.getQuery() != null) {
            Matcher n = Pattern.compile("(?:^|&)nr=([0-9]+)(?:&|$)").matcher(uri.getQuery());
            if (n.find()) result.add(n.group(1));
        }
        if (result.isEmpty()) result.addAll(prints(mapping.title()));
        return result;
    }
    private static Set<String> prints(String text) {
        Set<String> result = new HashSet<>(); Matcher match = PRINTS.matcher(text);
        while (match.find()) {
            Matcher n = Pattern.compile("[0-9]+(?:-[A-Za-z0-9]+)?").matcher(match.group(1));
            while (n.find()) result.add(n.group());
        }
        return result;
    }
    private static boolean confirmsTerm(Document doc, BillVotings.Voting mapping) {
        String text = normalize(doc.body().text()).toLowerCase(Locale.ROOT);
        boolean visibleTerm = Pattern.compile("(?:\\bx\\s+kadencj[ai]\\b|\\bkadencja\\s+(?:x|10)\\b|\\bsejm\\s+x\\s+kadencji\\b)").matcher(text).find();
        boolean canonical = false;
        // A self-identifying canonical URL is acceptable; unrelated navigation links are not.
        for (Element link : doc.select("link[rel=canonical]")) {
            try {
                URI uri = URI.create(link.attr("abs:href"));
                if (!"www.sejm.gov.pl".equals(uri.getHost()) || !"https".equals(uri.getScheme())) return false;
                if (!uri.getPath().equalsIgnoreCase("/Sejm10.nsf/Glosowanie.xsp")) return false;
                Set<String> query = new HashSet<>(Arrays.asList(Objects.toString(uri.getQuery(), "").split("&")));
                if (!query.equals(Set.of("posiedzenie=" + mapping.sitting(), "glosowanie=" + mapping.votingNumber()))) return false;
                canonical = true;
            } catch (RuntimeException ignored) { return false; }
        }
        return canonical || visibleTerm;
    }
    private static String normalize(String s) { return s.replace('\u00a0', ' ').replaceAll("\\s+", " ").strip(); }
}
