package pl.simplysejm.president.api;

import java.time.LocalDate;
import java.util.List;

public interface PresidentialCommentsQuery {
    public record Comment(String id, String billId, LocalDate date, String title, String summary,
                          List<String> arguments, String sourceUrl, String documentUrl, String method) {}
    public record Feed(String sourceUrl, LocalDate verifiedAt, String mode, List<Comment> comments) {}
    Feed comments();
}
