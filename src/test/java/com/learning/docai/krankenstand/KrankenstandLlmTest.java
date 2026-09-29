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
 * once they are regenerated. The fixture keeps the three dates distinct - absence from, absence to
 * five days later, document issued the day after it began - so each relation can actually fail.
 * The same goes for the two addresses on the document.
 */
@RealModelTest
class KrankenstandLlmTest {

    @Autowired
    private KrankenstandService service;

    private ExtraktionResponse<KrankenstandDaten> vollstaendig;
    private ExtraktionResponse<KrankenstandDaten> ohneEnde;

    @Test
    void readsTheNameAndInsuranceNumberOfThePatient() {
        KrankenstandDaten daten = vollstaendig().daten();

        // The Ordination names a doctor too; the patient is who is asked for.
        assertThat(daten.vorname()).isEqualTo("Max");
        assertThat(daten.familienname()).isEqualTo("Mustermann");
        // SPEC §3.3 asks for ten digits without the space the document prints.
        assertThat(daten.versicherungsnummer()).isEqualTo("1238010190");
    }

    /**
     * The document prints two addresses: the Ordination's and the one the patient stays at.
     * `krankenstand.txt` asks for the second, and they are in different districts so that picking
     * the first is visible here.
     */
    @Test
    void takesTheAddressOfTheAbsenceAndNotOfThePractice() {
        KrankenstandDaten daten = vollstaendig().daten();

        assertThat(daten.krankenstandsadresse())
                .contains("Blumengasse")
                .contains("1150")
                .doesNotContain("Hauptstraße", "1010");
    }

    /**
     * The absence runs five days, and the note was issued the day after it began. Both relations
     * would hold for a model that confused the fields only if the fixture printed one date twice,
     * which is exactly why it does not.
     */
    @Test
    void tellsTheThreeDatesApart() {
        KrankenstandDaten daten = vollstaendig().daten();

        assertThat(daten.arbeitsunfaehigVon()).isNotNull();
        assertThat(daten.letzterTagArbeitsunfaehigkeit())
                .isEqualTo(daten.arbeitsunfaehigVon().plusDays(5));
        assertThat(daten.ausstellungsdatum())
                .isEqualTo(daten.arbeitsunfaehigVon().plusDays(1))
                .isNotEqualTo(daten.arbeitsunfaehigVon());
    }

    /** Every field is present and plausible, so the deterministic rules find nothing. */
    @Test
    void reportsNoFindingsForACompleteNote() {
        assertThat(vollstaendig().probleme()).isEmpty();
        assertThat(vollstaendig().manuellePruefung()).isFalse();
    }

    /**
     * SPEC §4.2: a note that states only a first day is legitimate. The model must leave the end
     * null rather than inventing one, and the rules then report it as a warning.
     */
    @Test
    void leavesAMissingEndDateNullInsteadOfInventingOne() {
        assertThat(ohneEnde().daten().arbeitsunfaehigVon()).isNotNull();
        assertThat(ohneEnde().daten().letzterTagArbeitsunfaehigkeit()).isNull();
        assertThat(Problemprotokoll.codes(ohneEnde().probleme())).contains(IssueCode.ENDE_FEHLT);
        assertThat(ohneEnde().manuellePruefung()).isTrue();
    }

    private ExtraktionResponse<KrankenstandDaten> vollstaendig() {
        if (vollstaendig == null) {
            vollstaendig = extrahiere("krankenstand.pdf");
        }
        return vollstaendig;
    }

    private ExtraktionResponse<KrankenstandDaten> ohneEnde() {
        if (ohneEnde == null) {
            ohneEnde = extrahiere("krankenstand-ohne-ende.pdf");
        }
        return ohneEnde;
    }

    private ExtraktionResponse<KrankenstandDaten> extrahiere(String fixture) {
        return service.extrahiere(Fixtures.pdf(fixture), Teilnehmerhinweis.ohne(),
                UUID.randomUUID().toString());
    }
}
