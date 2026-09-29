package com.learning.docai.krankenstand;

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
 * Endpoint 2 (SPEC §3.3). The Teilnehmer hints are multipart parts, not query parameters, so
 * they never reach a URL or an access log.
 */
@Tag(name = "2 Krankenstand",
        description = "Daten aus einer Krankmeldung bzw. Arbeitsunfaehigkeitsmeldung (SPEC 3.3)")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/extraktion/krankenstand")
public class KrankenstandController {

    private final KrankenstandService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Krankenstandsbestaetigung auswerten",
            description = """
                    Die Teilnehmer-Angaben (vorname, familienname, svnr) sind optional und \
                    dienen nur dem Abgleich; sie sind Multipart-Felder, damit sie nicht in \
                    Zugriffsprotokollen oder URLs landen. Die Diagnose wird bewusst nicht \
                    extrahiert (Datenminimierung).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Extraktion, mit `probleme` und `manuellePruefung`",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Ohne Befund",
                                            value = OpenApiExamples.KRANKENSTAND_200),
                                    @ExampleObject(name = "Mit Befund (ENDE_FEHLT)",
                                            value = OpenApiExamples.KRANKENSTAND_200_MIT_PROBLEM)
                            })),
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
    public ExtraktionResponse<KrankenstandDaten> extrahiere(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "vorname", required = false) String vorname,
            @RequestPart(value = "familienname", required = false) String familienname,
            @RequestPart(value = "svnr", required = false) String svnr,
            @RequestAttribute(RequestIdFilter.ATTRIBUTE) String requestId) {

        return service.extrahiere(file, new Teilnehmerhinweis(vorname, familienname, svnr),
                requestId);
    }
}
