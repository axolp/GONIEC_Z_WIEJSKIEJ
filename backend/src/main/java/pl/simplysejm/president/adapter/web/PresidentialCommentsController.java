package pl.simplysejm.president.adapter.web;

import org.springframework.web.bind.annotation.*;
import pl.simplysejm.president.api.PresidentialCommentsQuery;

@RestController
public class PresidentialCommentsController {
    private final PresidentialCommentsQuery query;
    public PresidentialCommentsController(PresidentialCommentsQuery query) { this.query = query; }
    @GetMapping("/api/presidential-comments")
    public PresidentialCommentsQuery.Feed comments() { return query.comments(); }
}
