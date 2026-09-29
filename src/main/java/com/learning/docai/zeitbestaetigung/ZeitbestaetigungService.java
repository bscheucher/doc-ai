package com.learning.docai.zeitbestaetigung;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.learning.docai.ai.AiResult;
import com.learning.docai.ai.DocumentAiClient;
import com.learning.docai.ai.Prompts;
import com.learning.docai.api.ApiEndpoint;
import com.learning.docai.api.DocAiMetrics;
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
 * Endpoint 3 (SPEC §3.4): intake, one model call, then the deterministic rules of §4.3.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ZeitbestaetigungService {

    private static final String INSTRUCTION = Prompts.load("zeitbestaetigung.txt");

    private final DocumentIntakeService intake;
    private final DocumentAiClient aiClient;
    private final ZeitbestaetigungValidator validator;
    private final DocAiMetrics metrics;

    public ExtraktionResponse<ZeitbestaetigungDaten> extrahiere(MultipartFile file,
            Teilnehmerhinweis hinweis, String requestId) {

        PageImages pages = intake.toPageImages(file);
        AiResult<ZeitbestaetigungDaten> result =
                aiClient.extract(INSTRUCTION, pages, ZeitbestaetigungDaten.class);
        metrics.modellaufruf(ApiEndpoint.ZEITBESTAETIGUNG, result.provider(), result.model(),
                result.inputTokens(), result.outputTokens(), result.durationMs());

        List<ValidationIssue> probleme = validator.pruefe(result.value(), hinweis);

        metrics.anfrage(ApiEndpoint.ZEITBESTAETIGUNG, !probleme.isEmpty());

        // Only codes, never the values they are about (CLAUDE.md hard rules).
        log.info("Zeitbestaetigung: seiten={} probleme={} requestId={}",
                pages.pageCount(), Problemprotokoll.codes(probleme), requestId);

        return ExtraktionResponse.of(requestId, Extraktionstyp.ZEITBESTAETIGUNG, result.value(),
                probleme, result.metadaten(pages.pageCount()));
    }
}
