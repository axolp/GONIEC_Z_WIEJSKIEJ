package pl.simplysejm.bills.port;

import java.io.IOException;
import java.util.List;

public interface PrintSource {
    List<PrintDocument> prints() throws IOException;
    PrintDocument detail(String number) throws IOException;
    String attachment(PrintDocument print) throws IOException;
    byte[] pdf(PrintDocument print, String attachment) throws IOException;
}
