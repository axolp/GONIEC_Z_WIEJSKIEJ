package pl.simplysejm;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.nio.file.Path;

@Validated
@ConfigurationProperties("sejm")
public record SejmProperties(
        @NotBlank String baseUrl, @NotNull Path dataDir, @NotNull Path seedPath,
        @Min(1) @Max(10080) long intervalMinutes, boolean startupSync,
        @Min(1) @Max(300) int connectTimeoutSeconds,
        @Min(1) @Max(600) int requestTimeoutSeconds,
        @Min(1024) @Max(104857600) int maxPdfBytes,
        @Min(1024) @Max(104857600) int maxJsonBytes,
        @Min(1) @Max(1000) int maxPages,
        @Min(1000) @Max(2000000) int maxTextChars,
        @NotBlank String corsOrigin) {}
