package com.learning.docai.zeitbestaetigung;

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
import com.learning.docai.validation.Teilnehmerhinweis;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Endpoint 3 (SPEC §3.4). No `svnr` part: an excuse note does not carry one (SPEC §3).
 */
@Tag(name = "3 Zeitbestaetigung",
        description = "Daten aus einer Terminbestaetigung / Entschuldigung (SPEC 3.4)")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/extraktion/zeitbestaetigung")
public class ZeitbestaetigungController {

    private final ZeitbestaetigungService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Zeitbestaetigung auswerten",
            description = """
                    Uhrzeit von und Uhrzeit bis sind zwei getrennte Werte: steht nur eine \
                    davon auf dem Dokument, ist die andere null und es wird ein Hinweis \
                    gemeldet.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Extraktion, mit `probleme` und `manuellePruefung`",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Arzttermin mit Von- und Bis-Zeit",
                                    value = OpenApiExamples.ZEITBESTAETIGUNG_200))),
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
    public ExtraktionResponse<ZeitbestaetigungDaten> extrahiere(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "vorname", required = false) String vorname,
            @RequestPart(value = "familienname", required = false) String familienname,
            @RequestAttribute(RequestIdFilter.ATTRIBUTE) String requestId) {

        return service.extrahiere(file, new Teilnehmerhinweis(vorname, familienname, null),
                requestId);
    }
}
