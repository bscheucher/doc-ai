package com.learning.docai.klassifikation;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.learning.docai.ai.AiResult;
import com.learning.docai.ai.DocumentAiClient;
import com.learning.docai.ai.Prompts;
import com.learning.docai.intake.DocumentIntakeService;
import com.learning.docai.intake.PageImages;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Endpoint 1 (SPEC §3.2): intake, one model call, no validation rules.
 * `manuellePruefung` is true exactly when the type could not be determined.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KlassifikationService {

    private static final String INSTRUCTION = Prompts.load("klassifikation.txt");

    private final DocumentIntakeService intake;
    private final DocumentAiClient aiClient;

    public KlassifikationResponse klassifiziere(MultipartFile file, String requestId) {
        PageImages pages = intake.toPageImages(file);
        AiResult<KlassifikationErgebnis> result =
                aiClient.extract(INSTRUCTION, pages, KlassifikationErgebnis.class);

        // A model that answers with an unknown or absent type is treated as UNBEKANNT rather
        // than as a failure: the document is then reviewed by a person, which is the safe end.
        Dokumenttyp typ = result.value().typ() == null ? Dokumenttyp.UNBEKANNT : result.value().typ();
        boolean manuellePruefung = typ == Dokumenttyp.UNBEKANNT;

        log.info("Klassifikation: typ={} manuellePruefung={} seiten={} requestId={}",
                typ, manuellePruefung, pages.pageCount(), requestId);

        return new KlassifikationResponse(requestId, typ, result.value().begruendung(),
                manuellePruefung, result.metadaten(pages.pageCount()));
    }
}
