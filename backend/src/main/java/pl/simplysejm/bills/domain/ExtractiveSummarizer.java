package pl.simplysejm.bills.domain;

import java.util.*;
import java.util.regex.Pattern;
import pl.simplysejm.bills.api.Bill;

public class ExtractiveSummarizer {
    public static final String METHOD = "local-extractive-v1: deterministyczny wybór cytatów z PDF, bez AI";
    private static final String UNKNOWN_DATE = "Nie ustalono automatycznie; sprawdź przepisy końcowe projektu.";
    private static final Pattern CHANGE = Pattern.compile("(?iu)(otrzymuje brzmienie|dodaje się|uchyla się|wprowadza się|zmienia się)");
    private static final Pattern EFFECTIVE = Pattern.compile("(?iu)(ustawa wchodzi w życie|wchodzi w życie po upływie|wchodzi w życie z dniem)");
    public Bill.Summary summarize(String raw, boolean truncated) {
        List<String> passages = passages(raw);
        if (passages.isEmpty()) return pending("PDF nie zawiera dostatecznego tekstu. Możliwy skan; OCR nie jest wykonywany.");
        List<String> changes = passages.stream().filter(s -> CHANGE.matcher(s).find()).distinct().limit(5).toList();
        String lead = passages.stream().filter(s -> s.length() >= 60).findFirst().orElse(passages.getFirst());
        String effective = passages.stream().filter(s -> EFFECTIVE.matcher(s).find()).findFirst().orElse(UNKNOWN_DATE);
        return new Bill.Summary(lead, changes, List.of(), effective,
                "Fragmenty źródłowe, nie interpretacja prawna. Nie ustalono grup objętych zmianą; pusta lista nie oznacza braku skutków. "
                + "PDF może zawierać uzasadnienie i załączniki; wybrane cytaty wymagają sprawdzenia w kontekście."
                + (truncated ? " Analiza obejmuje tylko ograniczoną część dokumentu." : ""), METHOD);
    }
    public Bill.Summary pending(String reason) {
        return new Bill.Summary("Brak lokalnej analizy tekstu projektu.", List.of(), List.of(), UNKNOWN_DATE, reason, METHOD);
    }
    public boolean hasText(String text) {
        return text != null && text.codePoints().filter(Character::isLetter).limit(80).count() >= 80;
    }
    private List<String> passages(String raw) {
        if (!hasText(raw)) return List.of();
        String normalized = raw.replace("\r", "").replaceAll("(?<=\\p{L})-\\n(?=\\p{Ll})", "")
                .replaceAll("[ \t]+", " ");
        // Paragraphs or complete sentence boundaries; never paraphrase or infer missing facts.
        return Arrays.stream(normalized.split("\\n\\s*\\n|(?<=[.!?;])\\s+(?=[A-ZĄĆĘŁŃÓŚŹŻ])"))
                .map(s -> s.replaceAll("\\s+", " ").strip()).filter(s -> s.length() >= 40)
                .map(s -> s.length() > 800 ? s.substring(0, 800) + " […]" : s).limit(2000).toList();
    }
}
