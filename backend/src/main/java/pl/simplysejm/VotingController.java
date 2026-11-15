package pl.simplysejm;

import org.springframework.web.bind.annotation.*;

@RestController
public class VotingController {
    private final VotingService service;
    public VotingController(VotingService service) { this.service = service; }
    @GetMapping("/api/bills/{id}/votings")
    public BillVotings.Response votings(@PathVariable String id) { return service.get(id); }
}
