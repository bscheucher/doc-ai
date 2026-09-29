# Sample documents

Invented documents for exercising the endpoints by hand (Postman, curl). Nothing here comes
from a real person: the names are made up and the Versicherungsnummern are fake numbers that
happen to carry a correct check digit, so `SVNR_PRUEFZIFFER_UNGUELTIG` does not drown out the
rules you are actually looking at.

They are **generated, not edited**:

```bash
./gradlew generateFixtures
```

Every date is written relative to the day of generation. `docai.validation.max-days-in-past`
is 90 days, so a fixed date would quietly start reporting `DATUM_ZU_ALT` three months after
it was committed. Regenerate instead of editing; the content lives in
`src/test/java/com/learning/docai/fixtures/FixtureGenerator.java`.

| File | Endpoint | What it is for |
|---|---|---|
| `krankenstand.pdf` | 1, 2 | Complete and plausible - the run with no issues |
| `krankenstand-ohne-ende.pdf` | 2 | Only a first day, so `ENDE_FEHLT` (legitimate, SPEC §4.2) |
| `zeitbestaetigung.pdf` | 1, 3 | Arzttermin with a start and an end time |
| `kompetenzprofil.pdf` | 4 | Both competency tables, certificates, interests; one row without a score, so `BEZEICHNUNG_OHNE_SCORE` |
| `unbekannt.pdf` | 1 | An invoice - neither class, so `UNBEKANNT` and `manuellePruefung` |
| `zu-viele-seiten.pdf` | any | Six pages against `docai.intake.max-pages` of five; answered `422 unreadable-document` |
| `krankenstand.png` | any | The same document as PNG, for the image path |

## Running against them

Start the service without authentication (`local` needs a model profile alongside it,
because naming any active profile replaces `spring.profiles.default`):

```bash
./gradlew bootRun --args='--spring.profiles.active=local,anthropic'
```

```bash
cd src/test/resources/fixtures

# Endpoint 1 - classification
curl -s -F "file=@krankenstand.pdf" localhost:8080/api/v1/klassifikation

# Endpoint 2 - Krankenstand, with the Teilnehmer hints ibosNG would send
curl -s -F "file=@krankenstand.pdf" \
     -F "vorname=Max" -F "familienname=Mustermann" -F "svnr=1238 010190" \
     localhost:8080/api/v1/extraktion/krankenstand

# Endpoint 3 - Zeitbestaetigung
curl -s -F "file=@zeitbestaetigung.pdf" \
     localhost:8080/api/v1/extraktion/zeitbestaetigung

# Endpoint 4 - Kompetenzprofil
curl -s -F "file=@kompetenzprofil.pdf" \
     localhost:8080/api/v1/extraktion/kompetenzprofil
```

Pass a hint that disagrees with the document to see the comparison rules fire, e.g.
`-F "familienname=Mustermeier"` for `NAME_WEICHT_AB`.
