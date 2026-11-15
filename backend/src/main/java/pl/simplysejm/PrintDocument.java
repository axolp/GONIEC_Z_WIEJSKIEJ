package pl.simplysejm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PrintDocument(String number, int term, String title, LocalDate deliveryDate,
                            String changeDate, List<String> attachments) {}
