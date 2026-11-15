package pl.simplysejm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SejmClientTest {
    @Test void fetchesRealApiShapeAndRejectsOversizedPdf() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prints", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String json = """
                    {"number":"3355","term":10,"title":"Rządowy projekt ustawy","deliveryDate":"2026-11-13","attachments":["3355.pdf"],"ignored":true}
                    """;
            byte[] bytes = path.endsWith(".pdf") ? new byte[2048] :
                    (path.equals("/prints") ? "[" + json + "]" : json).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        var cfg = new SejmProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/prints",
                Path.of("unused"), Path.of("unused"), 30, false, 2, 2, 1024, 4096, 300, 300000, "http://localhost:5173");
        var client = new SejmClient(cfg, new ObjectMapper().findAndRegisterModules());
        try {
            assertThat(client.prints()).hasSize(1);
            var detail = client.detail("3355");
            assertThat(detail.deliveryDate()).isEqualTo(LocalDate.of(2026, 11, 13));
            assertThat(client.attachment(detail)).isEqualTo("3355.pdf");
            assertThatThrownBy(() -> client.pdf(detail, "3355.pdf")).isInstanceOf(IOException.class).hasMessageContaining("limit");
            assertThatThrownBy(() -> client.attachment(new PrintDocument("3355", 10, "", null, null, List.of("../evil.pdf"))))
                    .isInstanceOf(IOException.class);
        } finally { client.close(); server.stop(0); }
    }
}
