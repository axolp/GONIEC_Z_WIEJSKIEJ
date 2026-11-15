package pl.simplysejm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.simplysejm.bills.adapter.file.CacheStore;
import pl.simplysejm.bills.api.Bill;
import pl.simplysejm.bills.application.BillService;
import pl.simplysejm.bills.port.BillRepository;
import pl.simplysejm.bootstrap.SejmProperties;
import pl.simplysejm.infrastructure.files.Sha256;

import static org.assertj.core.api.Assertions.assertThat;

class CacheStoreIT {
    @TempDir Path temp;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    SejmProperties config() {
        return new SejmProperties("https://api.sejm.gov.pl/sejm/term10/prints", temp.resolve("cache"),
                temp.resolve("seed-bills.json"), 30, false, 10, 60, 25000000, 20000000, 300, 300000,
                "http://localhost:5173");
    }
    Bill seedBill() {
        return new Bill("10-3355", "3355", 10, "Rządowy projekt ustawy o zmianie ustawy", LocalDate.of(2026, 11, 13),
                "https://www.sejm.gov.pl/sejm10.nsf/druk.xsp?nr=3355", "https://api.sejm.gov.pl/sejm/term10/prints/3355/3355.pdf",
                "analyzed", new Bill.Summary("Ręczne streszczenie", List.of("Zmiana"), List.of(), "Nie ustalono", "Projekt", "manual"), null);
    }
    @Test void loadsSeedPinsHashAndReanalyzesNextChange() throws Exception {
        mapper.writeValue(config().seedPath().toFile(), new Bill.Bills(List.of(seedBill())));
        var store = new CacheStore(mapper, config());
        var initial = store.load().entries().getFirst();
        String firstHash = Sha256.digest("first".getBytes());
        assertThat(BillService.shouldPreserve(initial, firstHash)).isTrue();
        String file = store.savePdf(firstHash, "first".getBytes());
        var pinned = new BillRepository.Entry(initial.bill(), firstHash, file);
        store.save(new BillRepository.Snapshot(1, List.of(pinned), Instant.now(), Instant.now(), null));
        var reloaded = new CacheStore(mapper, config()).load().entries().getFirst();
        assertThat(BillService.shouldPreserve(reloaded, firstHash)).isTrue();
        assertThat(BillService.shouldPreserve(reloaded, Sha256.digest("changed".getBytes()))).isFalse();
        assertThat(reloaded.bill().summary()).isEqualTo(initial.bill().summary());
        assertThat(store.pdf(reloaded)).isPresent();
        assertThat(Files.readAllBytes(config().seedPath())).isEqualTo(mapper.writeValueAsBytes(new Bill.Bills(List.of(seedBill()))));
    }
    @Test void corruptCacheFallsBackWithoutOverwritingSeed() throws Exception {
        mapper.writeValue(config().seedPath().toFile(), new Bill.Bills(List.of(seedBill())));
        var store = new CacheStore(mapper, config());
        Files.writeString(config().dataDir().resolve("cache.json"), "{broken");
        var result = store.load();
        assertThat(result.entries()).hasSize(1);
        assertThat(result.error()).contains("cache");
    }
}
