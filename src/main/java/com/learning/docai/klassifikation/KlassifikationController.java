package com.learning.docai.klassifikation;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
 * Endpoint 1 (SPEC §3.2). HTTP mapping only; everything else is in the service.
 */
@Tag(name = "1 Klassifikation",
        description = """
                Ist ein Abwesenheitsdokument eine Krankenstandsbestaetigung oder eine \
                Zeitbestaetigung? (SPEC 3.2)""")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/klassifikation")
public class KlassifikationController {

    private final KlassifikationService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Dokument klassifizieren",
            description = """
                    Liefert KRANKENSTANDSBESTAETIGUNG, ZEITBESTAETIGUNG oder UNBEKANNT. \
                    Bei UNBEKANNT wird `manuellePruefung` gesetzt; das Dokument wird nicht \
                    geraten. Die Orchestrierung bleibt beim Aufrufer: er ruft danach \
                    Endpunkt 2 oder 3 auf.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Klassifikation",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Krankenstandsbestaetigung",
                                    value = OpenApiExamples.KLASSIFIKATION_200))),
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
    public KlassifikationResponse klassifiziere(
            @RequestPart("file") MultipartFile file,
            @RequestAttribute(RequestIdFilter.ATTRIBUTE) String requestId) {

        return service.klassifiziere(file, requestId);
    }
}
