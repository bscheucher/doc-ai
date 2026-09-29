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
 * document, and the failure the instruction guards against is the model putting the range into
 * one field, or copying the issue date into `datumVon`.
 */
@RealModelTest
class ZeitbestaetigungLlmTest {

    @Autowired
    private ZeitbestaetigungService service;

    @Test
    void tellsTheStartAndEndTimeApart() {
        ExtraktionResponse<ZeitbestaetigungDaten> antwort = extrahiere();
        ZeitbestaetigungDaten daten = antwort.daten();

        assertThat(daten.zeitVon()).isEqualTo(LocalTime.of(9, 0));
        assertThat(daten.zeitBis()).isEqualTo(LocalTime.of(11, 30));
    }

    @Test
    void readsASingleDayAppointmentWithoutAnEndDate() {
        ZeitbestaetigungDaten daten = extrahiere().daten();

        assertThat(daten.datumVon()).isNotNull();
        // One day, so there is no "bis" to read - and the issue date is not it either.
        assertThat(daten.datumBis()).isNull();
        assertThat(daten.ausstellungsdatum()).isEqualTo(daten.datumVon());
    }

    @Test
    void readsTheNameAndTheKindOfAppointment() {
        ExtraktionResponse<ZeitbestaetigungDaten> antwort = extrahiere();
        ZeitbestaetigungDaten daten = antwort.daten();

        assertThat(daten.vorname()).isEqualTo("Anna");
        assertThat(daten.familienname()).isEqualTo("Beispiel");
        assertThat(daten.grundDerAbwesenheit()).containsIgnoringCase("arzttermin");
        assertThat(daten.aussteller()).isNotBlank();

        assertThat(antwort.probleme()).isEmpty();
        assertThat(antwort.manuellePruefung()).isFalse();
    }

    private ExtraktionResponse<ZeitbestaetigungDaten> extrahiere() {
        return service.extrahiere(Fixtures.pdf("zeitbestaetigung.pdf"), Teilnehmerhinweis.ohne(),
                UUID.randomUUID().toString());
    }
}
