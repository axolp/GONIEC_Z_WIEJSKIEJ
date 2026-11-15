package pl.simplysejm.bills.adapter.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import pl.simplysejm.bills.port.PrintDocument;
import pl.simplysejm.bills.port.PrintSource;
import pl.simplysejm.bootstrap.SejmProperties;
import pl.simplysejm.infrastructure.http.BoundedHttpTransport;

@Component
public class SejmClient implements PrintSource {
    private final SejmProperties config;
    private final ObjectMapper mapper;
    private final BoundedHttpTransport transport;
    public SejmClient(SejmProperties config, ObjectMapper mapper, BoundedHttpTransport transport) {
        this.config = config; this.mapper = mapper;
        this.transport = transport;
    }
    public List<PrintDocument> prints() throws IOException {
        return mapper.readValue(transport.fetch(config.baseUrl(), config.maxJsonBytes(), "application/json", Duration.ofSeconds(config.requestTimeoutSeconds())), new TypeReference<>() {});
    }
    public PrintDocument detail(String number) throws IOException {
        requireNumber(number);
        return mapper.readValue(transport.fetch(config.baseUrl() + "/" + number, config.maxJsonBytes(), "application/json", Duration.ofSeconds(config.requestTimeoutSeconds())), PrintDocument.class);
    }
    public String attachment(PrintDocument print) throws IOException {
        if (print.attachments() == null) throw new IOException("Druk nie ma załączników PDF");
        return print.attachments().stream().filter(s -> s != null && s.matches("[A-Za-z0-9_.-]+(?i:\\.pdf)") && !s.contains(".."))
                .min(Comparator.comparing((String s) -> !s.equalsIgnoreCase(print.number() + ".pdf")).thenComparing(s -> s))
                .orElseThrow(() -> new IOException("Druk nie ma bezpiecznej nazwy załącznika PDF"));
    }
    public String pdfUrl(PrintDocument print, String attachment) {
        requireNumber(print.number());
        return config.baseUrl() + "/" + print.number() + "/" + attachment;
    }
    public byte[] pdf(PrintDocument print, String attachment) throws IOException {
        byte[] bytes = transport.fetch(pdfUrl(print, attachment), config.maxPdfBytes(), "application/pdf", Duration.ofSeconds(config.requestTimeoutSeconds()));
        if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
            throw new IOException("Załącznik nie jest plikiem PDF");
        return bytes;
    }
    private static void requireNumber(String number) {
        if (number == null || !number.matches("[0-9]+")) throw new IllegalArgumentException("Nieprawidłowy numer druku");
    }
}
