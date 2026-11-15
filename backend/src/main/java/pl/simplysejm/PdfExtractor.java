package pl.simplysejm;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import java.io.*;

@Component
public class PdfExtractor {
    private final SejmProperties config;
    public PdfExtractor(SejmProperties config) { this.config = config; }
    public record Extraction(String text, boolean truncated) {}
    public Extraction extract(byte[] bytes) throws IOException {
        try (var doc = Loader.loadPDF(bytes)) {
            if (!doc.getCurrentAccessPermission().canExtractContent()) throw new IOException("PDF nie zezwala na ekstrakcję tekstu");
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setEndPage(Math.min(doc.getNumberOfPages(), config.maxPages()));
            var writer = new LimitedWriter(config.maxTextChars());
            try { stripper.writeText(doc, writer); } catch (TextLimit reached) { writer.truncated = true; }
            return new Extraction(writer.text.toString(), writer.truncated || doc.getNumberOfPages() > config.maxPages());
        }
    }
    private static class TextLimit extends IOException {}
    private static class LimitedWriter extends Writer {
        final StringBuilder text = new StringBuilder();
        final int limit;
        boolean truncated;
        LimitedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int off, int len) throws IOException {
            int remaining = limit - text.length();
            text.append(chars, off, Math.min(len, remaining));
            if (len > remaining) throw new TextLimit();
        }
        @Override public void flush() {}
        @Override public void close() {}
    }
}
