package com.learning.docai.krankenstand;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.learning.docai.ai.AiResult;
import com.learning.docai.ai.DocumentAiClient;
import com.learning.docai.ai.Prompts;
import com.learning.docai.api.ExtraktionResponse;
import com.learning.docai.api.Extraktionstyp;
import com.learning.docai.intake.DocumentIntakeService;
import com.learning.docai.intake.PageImages;
import com.learning.docai.validation.Problemprotokoll;
import com.learning.docai.validation.Teilnehmerhinweis;
import com.learning.docai.validation.ValidationIssue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoint 2 (SPEC §3.3): intake, one model call, then the deterministic rules of §4.2.
 * Missing fields are a finding, never an error - the caller gets the document either way.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KrankenstandService {

    private static final String INSTRUCTION = Prompts.load("krankenstand.txt");

    private final DocumentIntakeService intake;
    private final DocumentAiClient aiClient;
    private final KrankenstandValidator validator;

    public ExtraktionResponse<KrankenstandDaten> extrahiere(MultipartFile file,
            Teilnehmerhinweis hinweis, String requestId) {

        PageImages pages = intake.toPageImages(file);
        AiResult<KrankenstandDaten> result =
                aiClient.extract(INSTRUCTION, pages, KrankenstandDaten.class);

        List<ValidationIssue> probleme = validator.pruefe(result.value(), hinweis);

        // Only codes, never the values they are about (CLAUDE.md hard rules).
        log.info("Krankenstand: seiten={} probleme={} requestId={}",
                pages.pageCount(), Problemprotokoll.codes(probleme), requestId);

        return ExtraktionResponse.of(requestId, Extraktionstyp.KRANKENSTAND, result.value(),
                probleme, result.metadaten(pages.pageCount()));
    }
}
