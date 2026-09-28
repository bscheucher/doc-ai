package com.learning.docai.zeitbestaetigung;

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
 * Endpoint 3 (SPEC §3.4). No `svnr` part: an excuse note does not carry one (SPEC §3).
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/extraktion/zeitbestaetigung")
public class ZeitbestaetigungController {

    private final ZeitbestaetigungService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExtraktionResponse<ZeitbestaetigungDaten> extrahiere(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "vorname", required = false) String vorname,
            @RequestPart(value = "familienname", required = false) String familienname,
            @RequestAttribute(RequestIdFilter.ATTRIBUTE) String requestId) {

        return service.extrahiere(file, new Teilnehmerhinweis(vorname, familienname, null),
                requestId);
    }
}
