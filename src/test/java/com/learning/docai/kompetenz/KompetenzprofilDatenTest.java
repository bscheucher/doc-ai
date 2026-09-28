package com.learning.docai.kompetenz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The list promise of SPEC §3.5: empty rather than null, and without rows that carry nothing.
 */
class KompetenzprofilDatenTest {

    @Test
    void turnsMissingListsIntoEmptyOnes() {
        KompetenzprofilDaten daten = new KompetenzprofilDaten("Amira", "Ahmed",
                LocalDate.of(1985, 7, 13), null, null, null, null, null).normalisiert();

        assertThat(daten.fachlich()).isEmpty();
        assertThat(daten.ueberfachlich()).isEmpty();
        assertThat(daten.zertifikate()).isEmpty();
        assertThat(daten.interessengebiete()).isEmpty();
    }

    @Test
    void dropsRowsThatCarryNothing() {
        // natif returned exactly this for `ueberfachlich` on the sample document.
        List<Kompetenz> leer = List.of(new Kompetenz(null, null), new Kompetenz("  ", null));

        KompetenzprofilDaten daten = profil(List.of(new Kompetenz("Buero Verwaltung", 70)), leer)
                .normalisiert();

        assertThat(daten.ueberfachlich()).isEmpty();
        assertThat(daten.fachlich()).hasSize(1);
    }

    @Test
    void keepsARowThatHasOnlyOneOfTheTwo() {
        // Half a row is a finding for §4.4, not something to throw away.
        KompetenzprofilDaten daten = profil(
                Arrays.asList(new Kompetenz(null, 95), new Kompetenz("Berufserfahrung", null)),
                List.of()).normalisiert();

        assertThat(daten.fachlich()).hasSize(2);
    }

    @Test
    void dropsBlankEntriesFromTheTextLists() {
        KompetenzprofilDaten daten = new KompetenzprofilDaten("Amira", "Ahmed", null, null,
                List.of(), List.of(), Arrays.asList("Staplerschein", null, "  "),
                List.of("Logistik")).normalisiert();

        assertThat(daten.zertifikate()).containsExactly("Staplerschein");
        assertThat(daten.interessengebiete()).containsExactly("Logistik");
    }

    private static KompetenzprofilDaten profil(List<Kompetenz> fachlich,
            List<Kompetenz> ueberfachlich) {
        return new KompetenzprofilDaten("Amira", "Ahmed", LocalDate.of(1985, 7, 13), null,
                fachlich, ueberfachlich, List.of(), List.of());
    }
}
