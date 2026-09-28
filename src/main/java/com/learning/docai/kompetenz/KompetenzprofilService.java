package com.learning.docai.kompetenz;

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
import com.learning.docai.validation.ValidationIssue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoint 4 (SPEC §3.5): intake, one model call, then the deterministic rules of §4.4. The
 * lists are normalised before the rules run, so the indices in an issue such as
 * `fachlich[2]` address the entry in the response.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KompetenzprofilService {

    private static final String INSTRUCTION = Prompts.load("kompetenzprofil.txt");

    private final DocumentIntakeService intake;
    private final DocumentAiClient aiClient;
    private final KompetenzprofilValidator validator;

    public ExtraktionResponse<KompetenzprofilDaten> extrahiere(MultipartFile file,
            String requestId) {

        PageImages pages = intake.toPageImages(file);
        AiResult<KompetenzprofilDaten> result =
                aiClient.extract(INSTRUCTION, pages, KompetenzprofilDaten.class);

        KompetenzprofilDaten daten = result.value().normalisiert();
        List<ValidationIssue> probleme = validator.pruefe(daten);

        // Only codes, never the values they are about (CLAUDE.md hard rules).
        log.info("Kompetenzprofil: seiten={} probleme={} requestId={}",
                pages.pageCount(), Problemprotokoll.codes(probleme), requestId);

        return ExtraktionResponse.of(requestId, Extraktionstyp.KOMPETENZPROFIL, daten, probleme,
                result.metadaten(pages.pageCount()));
    }
}
