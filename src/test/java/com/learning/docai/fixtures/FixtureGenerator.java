package com.learning.docai.fixtures;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Writes the sample documents in {@code src/test/resources/fixtures} for testing the
 * endpoints by hand (Postman, curl). Run it through Gradle:
 *
 * <pre>./gradlew generateFixtures</pre>
 *
 * <p>The documents are generated rather than committed as hand-made binaries because their
 * dates have to stay inside the validation window: {@code docai.validation.max-days-in-past}
 * is 90 days, so a fixed date would silently start reporting DATUM_ZU_ALT a quarter after it
 * was committed, and whoever hit that would go looking for a bug in the validator. Every date
 * below is written relative to the day of generation, so regenerating is the fix.
 *
 * <p>All personal data is invented. The Versicherungsnummern are fake but carry a correct
 * check digit ({@code SvnrValidator}), because an invalid one would mask the SVNR rules
 * behind a permanent SVNR_PRUEFZIFFER_UNGUELTIG on every call.
 *
 * <p>This is a generator with a {@code main}, not a test - it lives in the test source set
 * only because that is where its output and its only callers belong.
 */
public final class FixtureGenerator {

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * The zone of the injected {@code Clock} (ClockConfig). The dates below are written
     * against the same "today" the validator measures them against, so generating late in
     * the evening on a machine in another zone cannot put a document a day off its window.
     */
    private static final ZoneId ZONE = ZoneId.of("Europe/Vienna");

    private static final PDFont NORMAL = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont FETT = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    /** Rendering DPI of the PNG fixture; matches the service default docai.intake.render-dpi. */
    private static final int PNG_DPI = 150;

    private FixtureGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path ziel = Path.of(args.length > 0 ? args[0] : "src/test/resources/fixtures");
        Files.createDirectories(ziel);

        LocalDate heute = LocalDate.now(ZONE);

        krankenstand(ziel.resolve("krankenstand.pdf"), heute);
        krankenstandOhneEnde(ziel.resolve("krankenstand-ohne-ende.pdf"), heute);
        zeitbestaetigung(ziel.resolve("zeitbestaetigung.pdf"), heute);
        kompetenzprofil(ziel.resolve("kompetenzprofil.pdf"), heute);
        unbekannt(ziel.resolve("unbekannt.pdf"), heute);
        zuVieleSeiten(ziel.resolve("zu-viele-seiten.pdf"));
        alsPng(ziel.resolve("krankenstand.pdf"), ziel.resolve("krankenstand.png"));

        System.out.println("Fixtures written to " + ziel.toAbsolutePath());
    }

    /**
     * The straightforward case: every field present and plausible, so a clean run reports no
     * issues at all. SVNR 1238 010190 - check digit 8 over the other nine digits.
     */
    private static void krankenstand(Path datei, LocalDate heute) throws IOException {
        schreibe(datei, List.of(
                titel("KRANKENSTANDSBESTÄTIGUNG"),
                text("Dr. med. Erika Musterfrau, Ärztin für Allgemeinmedizin"),
                text("Hauptstraße 1, 1010 Wien"),
                leer(),
                text("Name:                       Max Mustermann"),
                text("Geburtsdatum:               01.01.1990"),
                text("Versicherungsnummer:        1238 010190"),
                text("Aufenthalt im Krankenstand: Hauptstraße 1, 1010 Wien"),
                leer(),
                text("Arbeitsunfähig von:         " + heute.minusDays(10).format(DATUM)),
                text("Voraussichtlich bis:        " + heute.minusDays(5).format(DATUM)),
                leer(),
                text("Wien, am " + heute.minusDays(10).format(DATUM)),
                text("Unterschrift / Stempel")));
    }

    /**
     * Many notes state only the first day, which SPEC §4.2 treats as legitimate rather than
     * as an error: this one exists so ENDE_FEHLT can be seen without editing a document.
     */
    private static void krankenstandOhneEnde(Path datei, LocalDate heute) throws IOException {
        schreibe(datei, List.of(
                titel("KRANKENSTANDSBESTÄTIGUNG"),
                text("Dr. med. Erika Musterfrau, Ärztin für Allgemeinmedizin"),
                text("Hauptstraße 1, 1010 Wien"),
                leer(),
                text("Name:                       Max Mustermann"),
                text("Versicherungsnummer:        1238 010190"),
                leer(),
                text("Arbeitsunfähig ab:          " + heute.minusDays(3).format(DATUM)),
                text("Ein Ende der Arbeitsunfähigkeit ist derzeit nicht absehbar."),
                leer(),
                text("Wien, am " + heute.minusDays(3).format(DATUM))));
    }

    /** SVNR 4568 150392 - check digit 8. */
    private static void zeitbestaetigung(Path datei, LocalDate heute) throws IOException {
        schreibe(datei, List.of(
                titel("TERMINBESTÄTIGUNG"),
                text("Gesundheitszentrum Beispielstadt"),
                text("Ambulanz, Beispielgasse 7, 8010 Graz"),
                leer(),
                text("Hiermit wird bestätigt, dass"),
                leer(),
                text("Frau Anna Beispiel"),
                text("Versicherungsnummer: 4568 150392"),
                leer(),
                text("am " + heute.minusDays(7).format(DATUM) + " von 09:00 bis 11:30 Uhr"),
                text("einen Arzttermin in unserer Ambulanz wahrgenommen hat."),
                leer(),
                text("Graz, am " + heute.minusDays(7).format(DATUM)),
                text("Unterschrift / Stempel")));
    }

    /**
     * Two competency tables, certificates and interests, so the lists of SPEC §3.5 are all
     * non-empty. "Konfliktfaehigkeit" carries no score, which is the half-read row of §4.4:
     * the response must report BEZEICHNUNG_OHNE_SCORE for ueberfachlich[3] and keep the row.
     *
     * <p>The rows that carry nothing at all - natif returned three of those for
     * `ueberfachlich`, and KompetenzprofilDaten.normalisiert() drops them - cannot be put in a
     * fixture: a row with neither label nor score leaves no mark on a rendered page, so there
     * is nothing for the model to read. That path is covered by KompetenzprofilDatenTest.
     *
     * <p>SVNR 7895 220785 - check digit 5.
     */
    private static void kompetenzprofil(Path datei, LocalDate heute) throws IOException {
        schreibe(datei, List.of(
                titel("KOMPETENZPROFIL"),
                text("Arbeitsmarktservice - Auswertung vom " + heute.minusDays(20).format(DATUM)),
                leer(),
                text("Vorname:              Johanna"),
                text("Nachname:             Beispielhuber"),
                text("Geburtsdatum:         22.07.1985"),
                text("Versicherungsnummer:  7895 220785"),
                leer(),
                fett("Fachliche Kompetenzen"),
                text("Buchhaltung                                      80"),
                text("Microsoft Excel                                  65"),
                text("Lohnverrechnung                                  45"),
                text("Englisch in Wort und Schrift                     70"),
                leer(),
                fett("Überfachliche Kompetenzen"),
                text("Teamfähigkeit                                    90"),
                text("Selbstständiges Arbeiten                         75"),
                text("Kommunikationsfähigkeit                          85"),
                text("Konfliktfähigkeit"),
                leer(),
                fett("Zertifikate"),
                text("ECDL Advanced"),
                text("Buchhalterprüfung WIFI"),
                leer(),
                fett("Interessengebiete"),
                text("Rechnungswesen"),
                text("Personalverrechnung")));
    }

    /**
     * Neither of the two known classes, so endpoint 1 must answer UNBEKANNT and set
     * manuellePruefung - the case that proves it does not guess.
     */
    private static void unbekannt(Path datei, LocalDate heute) throws IOException {
        schreibe(datei, List.of(
                titel("RECHNUNG"),
                text("Beispiel Bürobedarf GmbH"),
                text("Beispielstraße 42, 4020 Linz"),
                leer(),
                text("Rechnungsnummer:  2026-00815"),
                text("Rechnungsdatum:   " + heute.minusDays(14).format(DATUM)),
                leer(),
                text("2 x Druckerpapier A4, 500 Blatt              11,80"),
                text("1 x Toner schwarz                            89,90"),
                text("                                            ------"),
                text("Summe netto                                 101,70"),
                text("USt 20 %                                     20,34"),
                text("Gesamtbetrag                                122,04"),
                leer(),
                text("Zahlbar innerhalb von 14 Tagen ohne Abzug.")));
    }

    /**
     * Six pages against a docai.intake.max-pages of five: the rejection path, which is worth
     * a fixture because it is the one error a caller can trigger by accident.
     */
    private static void zuVieleSeiten(Path datei) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int seite = 1; seite <= 6; seite++) {
                zeichne(doc, List.of(
                        titel("SEITE " + seite + " VON 6"),
                        text("Dieses Dokument überschreitet die erlaubte Seitenzahl.")));
            }
            doc.save(datei.toFile());
        }
    }

    /** The intake accepts PDF, PNG and JPEG; this covers the image path without a scanner. */
    private static void alsPng(Path quelle, Path ziel) throws IOException {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(quelle.toFile())) {
            BufferedImage bild = new PDFRenderer(doc).renderImageWithDPI(0, PNG_DPI, ImageType.RGB);
            ImageIO.write(bild, "png", ziel.toFile());
        }
    }

    // ---- page building -------------------------------------------------------------------

    private record Zeile(String inhalt, PDFont font, float groesse) {
    }

    private static Zeile titel(String inhalt) {
        return new Zeile(inhalt, FETT, 16);
    }

    private static Zeile fett(String inhalt) {
        return new Zeile(inhalt, FETT, 12);
    }

    private static Zeile text(String inhalt) {
        return new Zeile(inhalt, NORMAL, 11);
    }

    private static Zeile leer() {
        return new Zeile("", NORMAL, 11);
    }

    private static void schreibe(Path datei, List<Zeile> zeilen) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            zeichne(doc, zeilen);
            doc.save(datei.toFile());
        }
    }

    private static void zeichne(PDDocument doc, List<Zeile> zeilen) throws IOException {
        PDPage seite = new PDPage(PDRectangle.A4);
        doc.addPage(seite);

        try (PDPageContentStream cs = new PDPageContentStream(doc, seite)) {
            float y = 760;
            for (Zeile zeile : zeilen) {
                if (!zeile.inhalt().isBlank()) {
                    cs.beginText();
                    cs.setFont(zeile.font(), zeile.groesse());
                    cs.newLineAtOffset(60, y);
                    cs.showText(zeile.inhalt());
                    cs.endText();
                }
                y -= zeile.groesse() + 11;
            }
        }
    }
}
