package com.learning.docai.krankenstand;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.learning.docai.api.ExtraktionResponse;
import com.learning.docai.api.RequestIdFilter;
import com.learning.docai.validation.Teilnehmerhinweis;

import lombok.RequiredArgsConstructor;

/**
 * Endpoint 2 (SPEC §3.3). The Teilnehmer hints are multipart parts, not query parameters, so
 * they never reach a URL or an access log.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/extraktion/krankenstand")
public class KrankenstandController {

    private final KrankenstandService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
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
