package pl.simplysejm.bills.port;

import java.io.IOException;

public interface PdfTextExtractor {
    record Extraction(String text, boolean truncated) {}
    Extraction extract(byte[] bytes) throws IOException;
}
