package pl.simplysejm.votings.adapter.web;

import org.springframework.web.bind.annotation.*;
import pl.simplysejm.votings.api.BillVotings;
import pl.simplysejm.votings.api.VotingQuery;

@RestController
public class VotingController {
    private final VotingQuery service;
    public VotingController(VotingQuery service) { this.service = service; }
    @GetMapping("/api/bills/{id}/votings")
    public BillVotings.Response votings(@PathVariable String id) { return service.get(id); }
}
