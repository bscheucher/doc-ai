package com.learning.docai.zeitbestaetigung;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.learning.docai.ai.RealModelTest;
import com.learning.docai.api.ExtraktionResponse;
import com.learning.docai.fixtures.Fixtures;
import com.learning.docai.validation.Teilnehmerhinweis;

/**
 * Endpoint 3 against a real model (SPEC §3.4, §11).
 *
 * <p>The two times are the point of this one. "von 09:00 bis 11:30" is a single line on the
 * document, and the failure the instruction guards against is the model putting the range into one
 * field. The confirmation was issued the day after the appointment, so copying the issue date into
 * `datumVon` - the other thing the instruction forbids - is visible too.
 */
@RealModelTest
class ZeitbestaetigungLlmTest {

    @Autowired
    private ZeitbestaetigungService service;

    private ExtraktionResponse<ZeitbestaetigungDaten> antwort;

    @Test
    void tellsTheStartAndEndTimeApart() {
        ZeitbestaetigungDaten daten = antwort().daten();

        assertThat(daten.zeitVon()).isEqualTo(LocalTime.of(9, 0));
        assertThat(daten.zeitBis()).isEqualTo(LocalTime.of(11, 30));
    }

    @Test
    void doesNotTakeTheIssueDateAsTheAppointmentDate() {
        ZeitbestaetigungDaten daten = antwort().daten();

        assertThat(daten.datumVon()).isNotNull();
        // One day, so there is no "bis" to read.
        assertThat(daten.datumBis()).isNull();
        assertThat(daten.ausstellungsdatum())
                .isEqualTo(daten.datumVon().plusDays(1))
                .isNotEqualTo(daten.datumVon());
    }

    @Test
    void readsTheNameAndTheKindOfAppointment() {
        ZeitbestaetigungDaten daten = antwort().daten();

        assertThat(daten.vorname()).isEqualTo("Anna");
        assertThat(daten.familienname()).isEqualTo("Beispiel");
        assertThat(daten.grundDerAbwesenheit()).containsIgnoringCase("arzttermin");
        assertThat(daten.aussteller()).isNotBlank();

        assertThat(antwort().probleme()).isEmpty();
        assertThat(antwort().manuellePruefung()).isFalse();
    }

    /**
     * `grundDerAbwesenheit` is the one free-text field of this endpoint, and its instruction
     * forbids a diagnosis or the name of an illness. Nothing else in the response could carry
     * medical text, so this is where the hard rule of CLAUDE.md is checked for endpoint 3.
     */
    @Test
    void keepsMedicalDetailAndNamesOutOfTheFreeTextField() {
        String grund = antwort().daten().grundDerAbwesenheit();

        assertThat(grund)
                .doesNotContainIgnoringCase("diagnose")
                .doesNotContainIgnoringCase("krankheit")
                // The patient's given name. Her surname would be no use here: "Beispiel" is also
                // in the name of the Gesundheitszentrum and of its street, so it could not tell a
                // leaked name from a naming of the issuer.
                .doesNotContainIgnoringCase("Anna")
                // The kind of appointment, not a retelling of the document.
                .hasSizeLessThan(60);
    }

    private ExtraktionResponse<ZeitbestaetigungDaten> antwort() {
        if (antwort == null) {
            antwort = service.extrahiere(Fixtures.pdf("zeitbestaetigung.pdf"),
                    Teilnehmerhinweis.ohne(), UUID.randomUUID().toString());
        }
        return antwort;
    }
}
