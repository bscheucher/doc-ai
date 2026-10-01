package com.learning.docai.kompetenz;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.learning.docai.api.ExtraktionResponse;
import com.learning.docai.api.OpenApiExamples;
import com.learning.docai.api.RequestIdFilter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Endpoint 4 (SPEC §3.5). Only the file: the caller passes no participant hints for a
 * Kompetenzprofil, so there is nothing to compare a name or an SVNR against.
 */
@Tag(name = "4 Kompetenzprofil",
        description = "Kompetenzen und Scores aus einem AMS-Kompetenzprofil (SPEC 3.5)")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/extraktion/kompetenzprofil")
public class KompetenzprofilController {

    private final KompetenzprofilService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Kompetenzprofil auswerten",
            description = """
                    Beide Kompetenztabellen, Zertifikate und Interessengebiete. Listen sind \
                    immer vorhanden, notfalls leer; Zeilen ohne Bezeichnung und ohne Score \
                    werden verworfen, halb gelesene Zeilen gemeldet.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Extraktion, mit `probleme` und `manuellePruefung`",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Vollstaendiges Profil",
                                    value = OpenApiExamples.KOMPETENZPROFIL_200))),
            @ApiResponse(responseCode = "400", description = "Keine Datei uebermittelt",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "413", description = "Datei groesser als 20 MB",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "415", description = "Kein PDF, PNG oder JPEG",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422",
                    description = "Dokument nicht lesbar, verschluesselt oder zu viele Seiten",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = OpenApiExamples.PROBLEM))),
            @ApiResponse(responseCode = "502", description = "Modell lieferte kein Ergebnis",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Modelldienst nicht erreichbar",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504", description = "Modell hat zu lange gebraucht",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ExtraktionResponse<KompetenzprofilDaten> extrahiere(
            @RequestPart("file") MultipartFile file,
            @RequestAttribute(RequestIdFilter.ATTRIBUTE) String requestId) {

        return service.extrahiere(file, requestId);
    }
}
