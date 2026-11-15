package pl.simplysejm.bills.domain;

import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;
import pl.simplysejm.bills.port.PrintDocument;

public class PrintSelector {
    // Anchored: a report saying "o projekcie ustawy" is never a bill.
    private static final Pattern BILL = Pattern.compile(
            "^(?:rządowy|poselski|senacki|obywatelski|prezydencki|komisyjny|przedstawiony przez prezydenta(?: rzeczypospolitej polskiej| rp)?)\\s+projekt\\s+ustawy\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    public boolean isBill(PrintDocument p) {
        return p != null && p.term() == 10 && p.number() != null && p.number().matches("[0-9]+")
                && p.deliveryDate() != null && p.title() != null
                && BILL.matcher(p.title().strip().replaceAll("\\s+", " ")).find();
    }
    public Optional<PrintDocument> latest(List<PrintDocument> prints) {
        return prints.stream().filter(this::isBill).max(Comparator
                .comparing(PrintDocument::deliveryDate)
                .thenComparing(p -> new BigInteger(p.number())));
    }
}
