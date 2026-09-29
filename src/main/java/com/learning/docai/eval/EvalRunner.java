package com.learning.docai.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.docai.api.DocAiException;
import com.learning.docai.api.ExtraktionResponse;
import com.learning.docai.api.Metadaten;
import com.learning.docai.config.EvalProperties;
import com.learning.docai.klassifikation.KlassifikationResponse;
import com.learning.docai.klassifikation.KlassifikationService;
import com.learning.docai.kompetenz.KompetenzprofilService;
import com.learning.docai.krankenstand.KrankenstandService;
import com.learning.docai.validation.IssueCode;
import com.learning.docai.validation.Problemprotokoll;
import com.learning.docai.validation.Teilnehmerhinweis;
import com.learning.docai.zeitbestaetigung.ZeitbestaetigungService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The evaluation tool of SPEC §12: runs every document under eval/input through the endpoint its
 * directory names, writes one JSON per document and one summary.csv, and compares against
 * eval/expected where a reference exists.
 *
 * <p>It drives the same services the controllers do, so what it measures is what the API answers -
 * intake, model call, validation and envelope included. Only the HTTP layer is missing, and with
 * it authentication, which is why the `eval` profile opens no port at all.
 *
 * <p>It calls a real model. That makes it the one place in the codebase where the hosted provider
 * sees documents, so the data-protection constraint of SPEC §7 applies to whatever is put into
 * eval/input. Nothing from a document reaches the log: file names, codes and counts only.
 */
@Slf4j
@RequiredArgsConstructor
@Profile("eval")
@Component
public class EvalRunner implements ApplicationRunner {

    private static final String SUMMARY = "summary.csv";

    private final EvalProperties properties;
    private final ObjectMapper mapper;
    private final KlassifikationService klassifikation;
    private final KrankenstandService krankenstand;
    private final ZeitbestaetigungService zeitbestaetigung;
    private final KompetenzprofilService kompetenzprofil;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Files.createDirectories(properties.output());

        List<EvalZeile> zeilen = new ArrayList<>();
        for (Endpunkt endpunkt : Endpunkt.values()) {
            zeilen.addAll(verarbeite(endpunkt));
        }

        if (zeilen.isEmpty()) {
            log.warn("No documents found under {} - nothing to evaluate", properties.input());
        }
        schreibeSummary(zeilen);
        log.info("Evaluation finished: {} documents, results in {}", zeilen.size(),
                properties.output());
    }

    private List<EvalZeile> verarbeite(Endpunkt endpunkt) throws IOException {
        Path verzeichnis = properties.input().resolve(endpunkt.verzeichnis);
        if (!Files.isDirectory(verzeichnis)) {
            log.info("No input directory {} - endpoint {} skipped", verzeichnis,
                    endpunkt.verzeichnis);
            return List.of();
        }
        Files.createDirectories(properties.output().resolve(endpunkt.verzeichnis));

        try (Stream<Path> dateien = Files.list(verzeichnis)) {
            return dateien.filter(Files::isRegularFile).sorted()
                    .map(datei -> verarbeite(endpunkt, datei))
                    .toList();
        }
    }

    /**
     * One document. A failure is a result too - which document the service rejects, and with
     * which slug of SPEC §6, is exactly what an evaluation run is for - so it lands in the row
     * and in a JSON file instead of stopping the run.
     */
    private EvalZeile verarbeite(Endpunkt endpunkt, Path datei) {
        String name = datei.getFileName().toString();
        String requestId = UUID.randomUUID().toString();
        try {
            MultipartFile upload = DateiMultipartFile.von(datei);
            Auswertung auswertung = endpunkt.auswerten(this, upload, requestId);

            schreibeJson(endpunkt, name, auswertung.antwort());
            log.info("Evaluated {}/{}: typ={} manuellePruefung={} probleme={}",
                    endpunkt.verzeichnis, name, auswertung.typ(), auswertung.manuellePruefung(),
                    auswertung.codes());

            return new EvalZeile(name, endpunkt.verzeichnis, auswertung.typ(),
                    auswertung.manuellePruefung(), auswertung.codes().stream().map(Enum::name).toList(),
                    auswertung.metadaten().inputTokens(), auswertung.metadaten().outputTokens(),
                    auswertung.metadaten().dauerMs(),
                    feldTreffer(endpunkt, name, auswertung.daten()));
        } catch (DocAiException e) {
            String slug = e.errorType().slug();
            schreibeJson(endpunkt, name, Map.of("fehler", slug, "requestId", requestId));
            log.warn("Evaluation of {}/{} failed: {}", endpunkt.verzeichnis, name, slug);
            return new EvalZeile(name, endpunkt.verzeichnis, slug, null, List.of(), null, null,
                    null, Map.of());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + datei, e);
        }
    }

    /**
     * SPEC §12 puts the reference at eval/expected/&lt;same-name&gt;.json. A per-endpoint
     * subdirectory is accepted as well, because input file names only have to be unique inside
     * their endpoint directory.
     */
    private Map<String, Boolean> feldTreffer(Endpunkt endpunkt, String name, Object daten) {
        if (daten == null) {
            // Endpoint 1 classifies rather than extracts: there are no fields to compare.
            return Map.of();
        }
        return erwartungsdatei(endpunkt, name)
                .map(this::lese)
                .map(erwartet -> FeldVergleich.vergleiche(mapper, daten, erwartet))
                .orElseGet(Map::of);
    }

    private Optional<Path> erwartungsdatei(Endpunkt endpunkt, String name) {
        String json = ohneEndung(name) + ".json";
        return Stream.of(properties.expected().resolve(json),
                        properties.expected().resolve(endpunkt.verzeichnis).resolve(json))
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private JsonNode lese(Path datei) {
        try {
            return mapper.readTree(datei.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the reference " + datei, e);
        }
    }

    private void schreibeJson(Endpunkt endpunkt, String name, Object inhalt) {
        Path ziel = properties.output().resolve(endpunkt.verzeichnis)
                .resolve(ohneEndung(name) + ".json");
        try {
            Files.createDirectories(ziel.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(ziel.toFile(), inhalt);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + ziel, e);
        }
    }

    /**
     * The fixed columns of SPEC §12 plus one `match.&lt;field&gt;` column per compared field. The
     * match columns are the union over all rows, so documents of different endpoints - which have
     * different fields - still share one table, with an empty cell where a field does not apply.
     */
    private void schreibeSummary(List<EvalZeile> zeilen) throws IOException {
        List<String> felder = zeilen.stream()
                .flatMap(zeile -> zeile.feldTreffer().keySet().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream().sorted().toList();

        List<String> kopf = new ArrayList<>(List.of("datei", "endpunkt", "typ",
                "manuellePruefung", "codes", "inputTokens", "outputTokens", "dauerMs"));
        felder.forEach(feld -> kopf.add("match." + feld));

        StringBuilder csv = new StringBuilder(zeile(kopf));
        for (EvalZeile zeile : zeilen) {
            List<String> werte = new ArrayList<>(List.of(
                    zeile.datei(),
                    zeile.endpunkt(),
                    text(zeile.typ()),
                    text(zeile.manuellePruefung()),
                    String.join(" ", zeile.codes()),
                    text(zeile.inputTokens()),
                    text(zeile.outputTokens()),
                    text(zeile.dauerMs())));
            felder.forEach(feld -> werte.add(text(zeile.feldTreffer().get(feld))));
            csv.append(zeile(werte));
        }
        Files.writeString(properties.output().resolve(SUMMARY), csv.toString(),
                StandardCharsets.UTF_8);
    }

    private static String zeile(List<String> werte) {
        return werte.stream().map(EvalRunner::csv).reduce((a, b) -> a + "," + b).orElse("") + "\n";
    }

    /** Minimal RFC 4180 quoting: a value only needs it when it could break the row. */
    private static String csv(String wert) {
        return wert.contains(",") || wert.contains("\"") || wert.contains("\n")
                ? "\"" + wert.replace("\"", "\"\"") + "\""
                : wert;
    }

    private static String text(Object wert) {
        return wert == null ? "" : String.valueOf(wert);
    }

    private static String ohneEndung(String name) {
        int punkt = name.lastIndexOf('.');
        return punkt > 0 ? name.substring(0, punkt) : name;
    }

    /** What one evaluated document produced, in the shape summary.csv and the JSON both need. */
    private record Auswertung(
            Object antwort,
            String typ,
            boolean manuellePruefung,
            List<IssueCode> codes,
            Metadaten metadaten,
            Object daten) {

        static <T> Auswertung von(ExtraktionResponse<T> antwort) {
            return new Auswertung(antwort, antwort.dokumenttyp().name(), antwort.manuellePruefung(),
                    Problemprotokoll.codes(antwort.probleme()), antwort.metadaten(),
                    antwort.daten());
        }
    }

    /** The four input directories of SPEC §12 and the service each one feeds. */
    private enum Endpunkt {

        KLASSIFIKATION("klassifikation") {
            @Override
            Auswertung auswerten(EvalRunner runner, MultipartFile datei, String requestId) {
                KlassifikationResponse antwort =
                        runner.klassifikation.klassifiziere(datei, requestId);
                return new Auswertung(antwort, antwort.typ().name(), antwort.manuellePruefung(),
                        List.of(), antwort.metadaten(), null);
            }
        },
        KRANKENSTAND("krankenstand") {
            @Override
            Auswertung auswerten(EvalRunner runner, MultipartFile datei, String requestId) {
                return Auswertung.von(runner.krankenstand.extrahiere(datei,
                        Teilnehmerhinweis.ohne(), requestId));
            }
        },
        ZEITBESTAETIGUNG("zeitbestaetigung") {
            @Override
            Auswertung auswerten(EvalRunner runner, MultipartFile datei, String requestId) {
                return Auswertung.von(runner.zeitbestaetigung.extrahiere(datei,
                        Teilnehmerhinweis.ohne(), requestId));
            }
        },
        KOMPETENZPROFIL("kompetenzprofil") {
            @Override
            Auswertung auswerten(EvalRunner runner, MultipartFile datei, String requestId) {
                return Auswertung.von(runner.kompetenzprofil.extrahiere(datei, requestId));
            }
        };

        private final String verzeichnis;

        Endpunkt(String verzeichnis) {
            this.verzeichnis = verzeichnis;
        }

        abstract Auswertung auswerten(EvalRunner runner, MultipartFile datei, String requestId);
    }
}
