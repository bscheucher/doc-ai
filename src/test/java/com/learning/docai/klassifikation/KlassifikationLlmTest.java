package com.learning.docai.klassifikation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;

import com.learning.docai.ai.RealModelTest;
import com.learning.docai.fixtures.Fixtures;

/**
 * Endpoint 1 against a real model (SPEC §3.2, §11). The fixtures cover the whole answer space of
 * the endpoint: the two known classes, the document that belongs to neither, and the image path.
 *
 * <p>`manuellePruefung` is not asserted per case: {@link KlassifikationService} derives it from
 * `typ` alone, so next to the assertion on `typ` it would be a tautology bought with a model call.
 * The derivation is covered by the mocked controller test, where it costs nothing.
 */
@RealModelTest
class KlassifikationLlmTest {

    @Autowired
    private KlassifikationService service;

    private final Map<String, KlassifikationResponse> antworten = new LinkedHashMap<>();

    @Test
    void recognisesASickNote() {
        assertThat(klassifiziere("krankenstand.pdf").typ())
                .isEqualTo(Dokumenttyp.KRANKENSTANDSBESTAETIGUNG);
    }

    @Test
    void recognisesAnAppointmentConfirmation() {
        assertThat(klassifiziere("zeitbestaetigung.pdf").typ())
                .isEqualTo(Dokumenttyp.ZEITBESTAETIGUNG);
    }

    /** An invoice is neither class. Guessing here would be the worse failure, not the safer one. */
    @Test
    void doesNotGuessAtAnInvoice() {
        KlassifikationResponse antwort = klassifiziere("unbekannt.pdf");

        assertThat(antwort.typ()).isEqualTo(Dokumenttyp.UNBEKANNT);
        // The one case where the flag is not a restatement of `typ` for a reader: it is the whole
        // point of UNBEKANNT that a person takes over.
        assertThat(antwort.manuellePruefung()).isTrue();
    }

    /** The image path: the same document as PNG has to classify the same way. */
    @Test
    void readsAPngAsWellAsAPdf() {
        assertThat(klassifiziere("krankenstand.png").typ())
                .isEqualTo(Dokumenttyp.KRANKENSTANDSBESTAETIGUNG);
    }

    /**
     * `begruendung` is the only free text endpoint 1 returns, and its instruction forbids a
     * diagnosis, the name of an illness and personal names. The sick note carries a patient and a
     * doctor, so this is where the hard rule of CLAUDE.md is checked for endpoint 1.
     *
     * <p>The wording itself is never asserted: it may differ on every call, and that is allowed.
     */
    @Test
    void keepsMedicalDetailAndNamesOutOfTheReason() {
        String begruendung = klassifiziere("krankenstand.pdf").begruendung();

        assertThat(begruendung).isNotBlank()
                .doesNotContainIgnoringCase("diagnose")
                .doesNotContainIgnoringCase("krankheit")
                // Patient and doctor. "Max" is left out on purpose: it is a substring of ordinary
                // German words such as "maximal" and could not tell a name from a coincidence.
                .doesNotContainIgnoringCase("Mustermann")
                .doesNotContainIgnoringCase("Musterfrau")
                .doesNotContainIgnoringCase("Erika");
    }

    private KlassifikationResponse klassifiziere(String fixture) {
        return antworten.computeIfAbsent(fixture, name ->
                service.klassifiziere(upload(name), UUID.randomUUID().toString()));
    }

    private static MultipartFile upload(String name) {
        return name.endsWith(".png") ? Fixtures.png(name) : Fixtures.pdf(name);
    }
}
