package pl.simplysejm.president.domain;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import pl.simplysejm.president.api.PresidentialCommentsQuery.*;

public final class PresidentialValidation {
    private PresidentialValidation() {}
    public static void validate(Feed feed) throws IOException {
        if (feed == null || feed.verifiedAt() == null || !"snapshot".equals(feed.mode())
                || feed.comments() == null || !officialUrl(feed.sourceUrl()))
            throw new IOException("Nieprawidłowa migawka stanowisk Prezydenta");
        Set<String> ids = new HashSet<>();
        for (Comment c : feed.comments()) {
            if (c == null || blank(c.id()) || !ids.add(c.id()) || c.date() == null || blank(c.title())
                    || blank(c.summary()) || c.arguments() == null || c.arguments().stream().anyMatch(PresidentialValidation::blank)
                    || blank(c.method()) || !officialUrl(c.sourceUrl()) || !officialUrl(c.documentUrl())
                    || (c.billId() != null && !c.billId().matches("[1-9][0-9]*-[0-9]+")))
                throw new IOException("Nieprawidłowy komentarz Prezydenta");
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean officialUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            return "https".equals(uri.getScheme()) && "www.prezydent.pl".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1;
        } catch (IllegalArgumentException e) { return false; }
    }
}
