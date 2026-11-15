package pl.simplysejm;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BillController {
    private final BillService service;
    public BillController(BillService service) { this.service = service; }
    @GetMapping("/bills") public Bill.Bills bills() { return new Bill.Bills(service.bills()); }
    @GetMapping("/bills/{id}") public Bill bill(@PathVariable String id) {
        return service.bill(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono projektu ustawy"));
    }
    @GetMapping("/status") public BillService.Status status() { return service.status(); }
    @PostMapping("/sync") public ResponseEntity<Map<String, Object>> sync() {
        boolean started = service.requestSync();
        return ResponseEntity.accepted().body(Map.of("syncing", true, "started", started));
    }
    @GetMapping(value = "/bills/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<FileSystemResource> pdf(@PathVariable String id) {
        bill(id);
        var path = service.pdf(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "PDF nie jest jeszcze dostępny w cache; uruchom synchronizację"));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(id + ".pdf").build().toString())
                .header("X-Content-Type-Options", "nosniff").body(new FileSystemResource(path));
    }
}
