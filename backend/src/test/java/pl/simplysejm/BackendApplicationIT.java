package pl.simplysejm;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import pl.simplysejm.bills.adapter.scheduling.BillSynchronization;
import pl.simplysejm.bills.api.*;
import pl.simplysejm.bills.port.*;
import pl.simplysejm.infrastructure.http.BoundedHttpTransport;
import pl.simplysejm.president.api.PresidentialCommentsQuery;
import pl.simplysejm.president.port.PresidentialSnapshotSource;
import pl.simplysejm.votings.api.*;
import pl.simplysejm.votings.port.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static pl.simplysejm.VotingFixtures.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sejm.startup-sync=false", "sejm.scheduling-enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackendApplicationIT {
    @TempDir static Path directory;
    static HttpServer source;
    static byte[] pdf;
    static final CountDownLatch releaseVoting = new CountDownLatch(1);
    static final AtomicInteger requests = new AtomicInteger();
    static String baseUrl;
    @Autowired TestRestTemplate http;
    @Autowired ApplicationContext context;

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties) throws Exception {
        pdf = syntheticPdf();
        var bill = new Bill("10-2693", "2693", 10, "Rządowy projekt ustawy", LocalDate.of(2026, 11, 13),
                "https://www.sejm.gov.pl/sejm10.nsf/druk.xsp?nr=2693", "/api/bills/10-2693/pdf", "pending",
                new Bill.Summary("Brak lokalnej analizy tekstu projektu.", List.of(), List.of(), "Nie ustalono", "Seed", "pending"), null);
        MAPPER.writeValue(directory.resolve("seed.json").toFile(), new Bill.Bills(List.of(bill)));
        MAPPER.writeValue(directory.resolve("mapping.json").toFile(), new BillVotings.File(List.of(snapshot())));
        var feed = new PresidentialCommentsQuery.Feed("https://www.prezydent.pl/prawo/ustawy-zawetowane",
                LocalDate.of(2026, 11, 15), "snapshot", List.of());
        MAPPER.writeValue(directory.resolve("president.json").toFile(), feed);
        source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String print = """
                {"number":"2693","term":10,"title":"Rządowy projekt ustawy","deliveryDate":"2026-11-13","attachments":["2693.pdf"]}
                """;
        source.createContext("/prints", exchange -> {
            requests.incrementAndGet(); String path = exchange.getRequestURI().getPath();
            byte[] bytes = path.endsWith(".pdf") ? pdf : (path.equals("/prints") ? "[" + print + "]" : print).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", path.endsWith(".pdf") ? "application/pdf" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        source.createContext("/votings/66/103", exchange -> {
            requests.incrementAndGet();
            try {
                if (!releaseVoting.await(10, TimeUnit.SECONDS)) throw new IOException("test deadline");
                byte[] bytes = VOTING_JSON.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); exchange.close(); }
        });
        source.start(); baseUrl = "http://127.0.0.1:" + source.getAddress().getPort();
        properties.add("sejm.base-url", () -> baseUrl + "/prints");
        properties.add("bill-votings.base-url", () -> baseUrl + "/votings");
        properties.add("sejm.data-dir", () -> directory.resolve("cache").toString());
        properties.add("sejm.seed-path", () -> directory.resolve("seed.json").toString());
        properties.add("bill-votings.path", () -> directory.resolve("mapping.json").toString());
        properties.add("president.comments-path", () -> directory.resolve("president.json").toString());
        properties.add("sejm.request-timeout-seconds", () -> 15);
    }
    static byte[] syntheticPdf() throws IOException {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage(); document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Projekt ustawy wprowadza zasady publikowania informacji publicznych oraz terminy udostepniania dokumentow.");
                content.endText();
            }
            document.save(bytes); return bytes.toByteArray();
        }
    }
    @AfterAll static void cleanup() { releaseVoting.countDown(); if (source != null) source.stop(0); }

    @Test void wiresRealAdaptersSyncsAnalyzesAndServesLiveVotingResults() throws Exception {
        for (Class<?> port : List.of(BillQuery.class, BillSync.class, PrintSource.class, BillRepository.class,
                PdfTextExtractor.class, VotingQuery.class, VotingSource.class, VotingMappingSource.class,
                VotingRepository.class, PresidentialCommentsQuery.class, PresidentialSnapshotSource.class, BoundedHttpTransport.class))
            assertThat(context.getBeansOfType(port)).as(port.getName()).hasSize(1);
        assertThat(context.getBeansOfType(BillSynchronization.class)).isEmpty();
        assertThat(requests.get()).isZero();
        assertThat(http.getForObject("/api/bills/10-2693", JsonNode.class).path("status").asText()).isEqualTo("pending");
        var accepted = http.postForEntity("/api/sync", null, JsonNode.class);
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        assertThat(accepted.getBody().path("started").asBoolean()).isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var status = http.getForObject("/api/status", JsonNode.class);
            assertThat(status.path("syncing").asBoolean()).isFalse();
            assertThat(status.path("lastSuccessAt").isTextual()).isTrue();
            assertThat(status.path("error").isNull()).isTrue();
        });
        var analyzed = http.getForObject("/api/bills/10-2693", JsonNode.class);
        assertThat(analyzed.path("status").asText()).isEqualTo("analyzed");
        assertThat(analyzed.path("summary").path("method").asText()).startsWith("local-extractive-v1");
        var downloaded = http.getForEntity("/api/bills/10-2693/pdf", byte[].class);
        assertThat(downloaded.getBody()).isEqualTo(pdf);
        assertThat(downloaded.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(downloaded.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(downloaded.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("inline", "10-2693.pdf");
        assertThat(Files.exists(directory.resolve("cache/cache.json"))).isTrue();
        // JDK HttpClient permits Origin; HttpURLConnection may silently remove it.
        int runningPort = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        try (var corsClient = java.net.http.HttpClient.newHttpClient()) {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + runningPort + "/api/bills"))
                    .timeout(Duration.ofSeconds(5)).header("Origin", "http://localhost:5173").GET().build();
            var cors = corsClient.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            assertThat(cors.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
        }
        assertProblem("/api/bills/unknown", 404, "Nie znaleziono projektu ustawy");
        assertProblem("/api/bills/bad/votings", 400, "Nieprawidłowy identyfikator ustawy");
        assertThat(http.getForEntity("/api/presidential-comments", JsonNode.class).getStatusCode().value()).isEqualTo(200);
        Files.delete(directory.resolve("president.json"));
        assertProblem("/api/presidential-comments", 503, "Stanowiska Prezydenta są chwilowo niedostępne");
        try {
            var first = http.getForObject("/api/bills/10-2693/votings", JsonNode.class);
            assertThat(first.path("mode").asText()).isEqualTo("snapshot");
            assertThat(first.path("votings").get(0).path("votes")).isEmpty();
        } finally { releaseVoting.countDown(); }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var live = http.getForObject("/api/bills/10-2693/votings", JsonNode.class);
            assertThat(live.path("mode").asText()).isEqualTo("live");
            assertThat(live.path("votings").get(0).path("votes").get(0).path("MP").asInt()).isEqualTo(1);
            assertThat(live.path("votings").get(0).path("notParticipating").isNull()).isTrue();
            assertThat(live.path("votings").get(0).has("clubResults")).isFalse();
        });
    }
    void assertProblem(String path, int status, String detail) {
        var response = http.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("detail").asText()).isEqualTo(detail);
    }
}
