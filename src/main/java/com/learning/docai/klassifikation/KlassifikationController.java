package com.learning.docai.klassifikation;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.learning.docai.api.RequestIdFilter;

import lombok.RequiredArgsConstructor;

/**
 * Endpoint 1 (SPEC §3.2). HTTP mapping only; everything else is in the service.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/klassifikation")
public class KlassifikationController {

    private final KlassifikationService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public KlassifikationResponse klassifiziere(
            @RequestPart("file") MultipartFile file,
            @RequestAttribute(RequestIdFilter.ATTRIBUTE) String requestId) {

        return service.klassifiziere(file, requestId);
    }
}
