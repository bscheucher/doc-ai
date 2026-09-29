package com.learning.docai.krankenstand;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.learning.docai.ai.RealModelTest;
import com.learning.docai.api.ExtraktionResponse;
import com.learning.docai.fixtures.Fixtures;
import com.learning.docai.validation.IssueCode;
import com.learning.docai.validation.Problemprotokoll;
import com.learning.docai.validation.Teilnehmerhinweis;

/**
 * Endpoint 2 against a real model (SPEC §3.3, §11).
 *
 * <p>The dates are asserted by their relation to each other, not by their value: the fixtures are
 * generated relative to the day of generation, so a committed absolute date would start failing
 * once they are regenerated. The relations are what the extraction can actually get wrong -
 * swapping the issue date for the first day of absence, or reading the end as the beginning.
 */
@RealModelTest
class KrankenstandLlmTest {

    @Autowired
    private KrankenstandService service;

    @Test
    void readsEveryFieldOfACompleteNote() {
        ExtraktionResponse<KrankenstandDaten> antwort = extrahiere("krankenstand.pdf");
        KrankenstandDaten daten = antwort.daten();

        assertThat(daten.vorname()).isEqualTo("Max");
        assertThat(daten.familienname()).isEqualTo("Mustermann");
        // SPEC §3.3 asks for ten digits without the space the document prints.
        assertThat(daten.versicherungsnummer()).isEqualTo("1238010190");
        assertThat(daten.krankenstandsadresse()).contains("1010 Wien");

        // "Arbeitsunfähig von" is ten days back, "voraussichtlich bis" five: the span is five
        // days, and the note was issued on the first of them.
        assertThat(daten.arbeitsunfaehigVon()).isNotNull();
        assertThat(daten.letzterTagArbeitsunfaehigkeit())
                .isEqualTo(daten.arbeitsunfaehigVon().plusDays(5));
        assertThat(daten.ausstellungsdatum()).isEqualTo(daten.arbeitsunfaehigVon());

        // Every field is present and plausible, so the deterministic rules find nothing.
        assertThat(antwort.probleme()).isEmpty();
        assertThat(antwort.manuellePruefung()).isFalse();
    }

    /**
     * SPEC §4.2: a note that states only a first day is legitimate. The model must leave the end
     * null rather than inventing one, and the rules then report it as a warning.
     */
    @Test
    void leavesAMissingEndDateNullInsteadOfInventingOne() {
        ExtraktionResponse<KrankenstandDaten> antwort = extrahiere("krankenstand-ohne-ende.pdf");

        assertThat(antwort.daten().arbeitsunfaehigVon()).isNotNull();
        assertThat(antwort.daten().letzterTagArbeitsunfaehigkeit()).isNull();
        assertThat(Problemprotokoll.codes(antwort.probleme())).contains(IssueCode.ENDE_FEHLT);
        assertThat(antwort.manuellePruefung()).isTrue();
    }

    /** SPEC §3.3: the diagnosis is on the document and must not come back (data minimisation). */
    @Test
    void extractsNoDiagnosis() {
        ExtraktionResponse<KrankenstandDaten> antwort = extrahiere("krankenstand.pdf");

        assertThat(antwort.daten().krankenstandsadresse())
                .doesNotContainIgnoringCase("diagnose")
                .doesNotContainIgnoringCase("allgemeinmedizin");
    }

    private ExtraktionResponse<KrankenstandDaten> extrahiere(String fixture) {
        return service.extrahiere(Fixtures.pdf(fixture), Teilnehmerhinweis.ohne(),
                UUID.randomUUID().toString());
    }
}
