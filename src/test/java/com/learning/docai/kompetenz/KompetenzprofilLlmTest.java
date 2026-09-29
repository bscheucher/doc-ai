package com.learning.docai.kompetenz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.learning.docai.ai.RealModelTest;
import com.learning.docai.api.ExtraktionResponse;
import com.learning.docai.fixtures.Fixtures;
import com.learning.docai.validation.IssueCode;
import com.learning.docai.validation.Problemprotokoll;

/**
 * Endpoint 4 against a real model (SPEC §3.5, §11).
 *
 * <p>Two tables on one page, each row a label and a number: the failure to catch is a score landing
 * next to the wrong competency, or the two tables being merged into one. The rows are therefore
 * asserted by their pairing, not merely by being present.
 *
 * <p>One call for the whole class, so every assertion below describes the same response - which is
 * what "the tables were not merged" and "each score stayed with its row" have to mean to be worth
 * anything.
 */
@RealModelTest
class KompetenzprofilLlmTest {

    @Autowired
    private KompetenzprofilService service;

    private ExtraktionResponse<KompetenzprofilDaten> antwort;

    @Test
    void readsThePersonOnTheProfile() {
        KompetenzprofilDaten daten = antwort().daten();

        assertThat(daten.vorname()).isEqualTo("Johanna");
        assertThat(daten.nachname()).isEqualTo("Beispielhuber");
        assertThat(daten.geburtsdatum()).isEqualTo(LocalDate.of(1985, 7, 22));
        assertThat(daten.versicherungsnummer()).isEqualTo("7895220785");
    }

    @Test
    void keepsEachScoreWithItsOwnCompetency() {
        KompetenzprofilDaten daten = antwort().daten();

        assertThat(daten.fachlich())
                .anySatisfy(zeile -> {
                    assertThat(zeile.bezeichnung()).containsIgnoringCase("buchhaltung");
                    assertThat(zeile.score()).isEqualTo(80);
                })
                .anySatisfy(zeile -> {
                    assertThat(zeile.bezeichnung()).containsIgnoringCase("excel");
                    assertThat(zeile.score()).isEqualTo(65);
                });

        assertThat(daten.ueberfachlich())
                .anySatisfy(zeile -> {
                    assertThat(zeile.bezeichnung()).containsIgnoringCase("teamf");
                    assertThat(zeile.score()).isEqualTo(90);
                });
    }

    /** The two tables are separate on the document and must stay separate in the answer. */
    @Test
    void doesNotMergeTheTwoTables() {
        KompetenzprofilDaten daten = antwort().daten();

        assertThat(daten.fachlich()).hasSize(4);
        assertThat(daten.fachlich())
                .noneSatisfy(zeile -> assertThat(zeile.bezeichnung()).containsIgnoringCase("teamf"));
    }

    /**
     * SPEC §4.4: "Konfliktfaehigkeit" has no score on the document. Half a row is reported, not
     * dropped and not completed with a number the model made up.
     */
    @Test
    void reportsTheRowThatHasNoScore() {
        assertThat(antwort().daten().ueberfachlich())
                .anySatisfy(zeile -> {
                    assertThat(zeile.bezeichnung()).containsIgnoringCase("konflikt");
                    assertThat(zeile.score()).isNull();
                });
        assertThat(Problemprotokoll.codes(antwort().probleme()))
                .contains(IssueCode.BEZEICHNUNG_OHNE_SCORE);
        assertThat(antwort().manuellePruefung()).isTrue();
    }

    @Test
    void readsTheCertificatesAndInterests() {
        KompetenzprofilDaten daten = antwort().daten();

        assertThat(daten.zertifikate()).isNotEmpty()
                .anySatisfy(eintrag -> assertThat(eintrag).containsIgnoringCase("ecdl"));
        assertThat(daten.interessengebiete()).isNotEmpty();
    }

    private ExtraktionResponse<KompetenzprofilDaten> antwort() {
        if (antwort == null) {
            antwort = service.extrahiere(Fixtures.pdf("kompetenzprofil.pdf"),
                    UUID.randomUUID().toString());
        }
        return antwort;
    }
}
