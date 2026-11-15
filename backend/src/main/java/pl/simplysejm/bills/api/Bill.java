package pl.simplysejm.bills.api;

import java.time.LocalDate;
import java.util.List;

public record Bill(String id, String number, int term, String title, LocalDate date,
                   String sourceUrl, String pdfUrl, String status, Summary summary, String textPreview) {
    public record Summary(String lead, List<String> keyChanges, List<String> affectedGroups,
                          String effectiveDate, String caveats, String method) {}
    public record Bills(List<Bill> bills) {}
}
