package com.learning.docai.klassifikation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.learning.docai.ai.RealModelTest;
import com.learning.docai.fixtures.Fixtures;

/**
 * Endpoint 1 against a real model (SPEC §3.2, §11). The three fixtures cover the whole answer
 * space of the endpoint: the two known classes and the document that belongs to neither.
 *
 * <p>Only `typ` is asserted. `begruendung` is free text and may be worded differently on every
 * call - all that is required of it is that it exists and keeps the diagnosis out.
 */
@RealModelTest
class KlassifikationLlmTest {

    @Autowired
    private KlassifikationService service;

    @Test
    void recognisesASickNote() {
        KlassifikationResponse antwort = klassifiziere("krankenstand.pdf");

        assertThat(antwort.typ()).isEqualTo(Dokumenttyp.KRANKENSTANDSBESTAETIGUNG);
        assertThat(antwort.manuellePruefung()).isFalse();
        assertThat(antwort.begruendung()).isNotBlank();
    }

    @Test
    void recognisesAnAppointmentConfirmation() {
        KlassifikationResponse antwort = klassifiziere("zeitbestaetigung.pdf");

        assertThat(antwort.typ()).isEqualTo(Dokumenttyp.ZEITBESTAETIGUNG);
        assertThat(antwort.manuellePruefung()).isFalse();
    }

    /** An invoice is neither class. Guessing here would be the worse failure, not the safer one. */
    @Test
    void doesNotGuessAtAnInvoice() {
        KlassifikationResponse antwort = klassifiziere("unbekannt.pdf");

        assertThat(antwort.typ()).isEqualTo(Dokumenttyp.UNBEKANNT);
        assertThat(antwort.manuellePruefung()).isTrue();
    }

    /** The image path: the same document as PNG has to classify the same way. */
    @Test
    void readsAPngAsWellAsAPdf() {
        KlassifikationResponse antwort = service.klassifiziere(
                Fixtures.png("krankenstand.png"), UUID.randomUUID().toString());

        assertThat(antwort.typ()).isEqualTo(Dokumenttyp.KRANKENSTANDSBESTAETIGUNG);
    }

    private KlassifikationResponse klassifiziere(String fixture) {
        return service.klassifiziere(Fixtures.pdf(fixture), UUID.randomUUID().toString());
    }
}
