package com.example.aiplatform.ai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Writes each evaluation run to a timestamped JSON file.
 *
 * <p>Without this, a run existed only in the HTTP response that produced it.
 * The report's whole purpose is comparison - run it, change a prompt or a
 * model, run it again, diff the two - and that only works if the earlier run
 * still exists to diff against. Relying on whoever ran it to have saved the
 * response by hand made the harness's central use case depend on a manual step
 * nobody remembers.
 *
 * <p>Files are named by UTC timestamp so runs sort chronologically and a later
 * run never overwrites an earlier one - the property that makes the directory a
 * history rather than a snapshot.
 *
 * <p>A write failure is logged and swallowed. Persistence is a convenience
 * around the report, not the report itself, and losing the ability to file a
 * copy is not a reason to fail a run that has already spent real money on LLM
 * calls.
 */
@Component
public class EvaluationReportStore {

    private static final Logger log = LoggerFactory.getLogger(EvaluationReportStore.class);
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);

    private final ObjectMapper objectMapper;
    private final Path directory;

    public EvaluationReportStore(ObjectMapper objectMapper,
                                  @Value("${app.eval.report-directory:target/eval-reports}") String directory) {
        this.objectMapper = objectMapper;
        this.directory = Path.of(directory);
    }

    public void save(AiEvaluationReport report) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(
                    "eval-" + FILE_TIMESTAMP.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)) + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
            log.info("Evaluation report written to {}", file.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not persist the evaluation report - the run itself was unaffected", e);
        }
    }
}
