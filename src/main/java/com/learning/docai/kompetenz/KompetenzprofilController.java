package com.learning.docai.kompetenz;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.learning.docai.api.ExtraktionResponse;
import com.learning.docai.api.RequestIdFilter;

import lombok.RequiredArgsConstructor;

/**
 * Endpoint 4 (SPEC §3.5). Only the file: ibosNG passes no participant hints for a
 * Kompetenzprofil, so there is nothing to compare a name or an SVNR against.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/extraktion/kompetenzprofil")
public class KompetenzprofilController {

    private final KompetenzprofilService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExtraktionResponse<KompetenzprofilDaten> extrahiere(
            @RequestPart("file") MultipartFile file,
            @RequestAttribute(RequestIdFilter.ATTRIBUTE) String requestId) {

        return service.extrahiere(file, requestId);
    }
}
