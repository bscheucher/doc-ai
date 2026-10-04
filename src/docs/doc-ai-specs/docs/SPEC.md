# doc-ai – Specification

Status: draft v0.1 · Owner: Bernhard · Consumers: none in production — see the note below

> **This is a private learning project.** One operator, no other consumer, nothing in production.
> This document is written as a realistic brief — an internal service replacing natif.ai workflows
> for an ibosNG backend, with the domain vocabulary kept in German — because specifying and
> building against a realistic brief is the exercise. That integration does not exist and is not
> planned.
>
> So wherever this document says "the caller", "the calling backend" or "the consumer", read it as
> the hypothetical caller the contract is designed for, not a system that will connect. The
> contracts, validation rules and field mappings are meant literally and are implemented as
> written; only the consumer is imagined. In practice the caller is `curl` or Hoppscotch from the
> author's laptop against the deployed service — `deploy/README.md` has the commands.
>
> Two consequences worth naming where they bite:
>
> - **§13 Q1** asks whether Entra authentication is wanted from the start or whether network
>   isolation is enough for v1. Answered in the deployment as: authentication, from the start, and
>   no isolation. The deployed service has **public** ingress, because isolation would only lock
>   out the one person who needs in. §7 is implemented exactly as written and is the only barrier —
>   every `/api/**` call needs a token carrying `DocAi.Process` — with the client-credentials flow
>   it describes coming from a test app registration rather than another system.
> - **§13 Q4** asks which remaining natif AMS fields the consumer actually uses. There is no
>   consumer to ask, so it cannot be closed from inside the project; treat the field set as the
>   author's choice rather than a requirement.

## 1. Purpose and scope

doc-ai replaces the natif.ai workflows "Abwesenheiten Classifier", "Abwesenheiten",
"Zeitbestätigungen" and "AMS" with an internal service built on a vision LLM.

It exposes four stateless, synchronous endpoints:

| # | Endpoint | Replaces natif workflow | Purpose |
|---|----------|-------------------------|---------|
| 1 | `POST /api/v1/klassifikation` | Abwesenheiten Classifier | Is an absence document a Krankenstandsbestätigung or a Zeitbestätigung? |
| 2 | `POST /api/v1/extraktion/krankenstand` | Abwesenheiten | Extract data from a doctor's note / Arbeitsunfähigkeitsmeldung |
| 3 | `POST /api/v1/extraktion/zeitbestaetigung` | Zeitbestätigungen | Extract data from an excuse note / appointment confirmation |
| 4 | `POST /api/v1/extraktion/kompetenzprofil` | AMS | Extract competencies and scores from a Kompetenzprofil |

**Orchestration stays with the caller.** As today with natif, the calling backend calls (1), then
calls (2) or (3) depending on the result. doc-ai does not chain calls itself.

Out of scope: storing documents or results, bounding boxes / OCR output, per-field confidence
scores, UI, asynchronous processing.

## 2. Processing pipeline (all endpoints)

1. **Intake** – validate the upload (§5), convert to page images:
   - PDF: render each page with PDFBox at `docai.intake.render-dpi` (default 150), RGB.
   - PNG/JPEG: decode as-is; apply EXIF orientation for JPEG.
   - Scale so the longest edge is ≤ `docai.intake.max-image-edge` (default 1600 px), encode as PNG.
   - Process at most `docai.intake.max-pages` (default 5) pages; more pages → 422 (§6).
2. **Model call** – one call per request via Spring AI `ChatClient`: shared system prompt +
   endpoint-specific instruction + all page images; response mapped onto the endpoint's
   Java record using Spring AI structured output (`.call().responseEntity(Type.class)`).
   Field descriptions come from `@JsonPropertyDescription` on the record components.
   - Temperature 0.
   - If mapping the model output to the record fails, retry once. Second failure → 502.
3. **Validation** – deterministic rules (§4) produce a list of issues.
4. **Response** – result + issues + `manuellePruefung` + metadata (§3.1).

The model provider is chosen by Spring profile (§8). Business code depends only on an
internal `DocumentAiClient` interface, never on provider classes.

## 3. API

All endpoints: `Content-Type: multipart/form-data`, response `application/json`.

Multipart parts:

| Part | Endpoints | Required | Notes |
|------|-----------|----------|-------|
| `file` | all | yes | PDF, PNG or JPEG, max 20 MB |
| `vorname` | 2, 3 | no | Participant hint from the caller, used for validation only |
| `familienname` | 2, 3 | no | " |
| `svnr` | 2 | no | " ; whitespace allowed |

Participant hints are multipart fields, **not** query parameters, so they never appear in
access logs or URLs.

Every response carries header `X-Request-Id` (echo the incoming header if present and a valid
UUID, otherwise generate one). The same id is in the body and in all log lines.

### 3.1 Common response envelope (endpoints 2–4)

```json
{
  "requestId": "3f1c…",
  "dokumenttyp": "KRANKENSTAND",
  "daten": { … endpoint-specific record, fields may be null … },
  "probleme": [ { "feld": "versicherungsnummer", "code": "SVNR_PRUEFZIFFER_UNGUELTIG",
                  "schweregrad": "FEHLER", "meldung": "Prüfziffer der Versicherungsnummer ungültig" } ],
  "manuellePruefung": true,
  "metadaten": { "provider": "anthropic", "modell": "claude-sonnet-5", "seiten": 1,
                 "inputTokens": 1834, "outputTokens": 156, "dauerMs": 3120 }
}
```

- `manuellePruefung` = `true` if `probleme` contains at least one entry (FEHLER or WARNUNG).
- `daten` is always present (never null) on 200; individual fields are null when not found.
- `meldung` is German, human-readable; callers must rely on `code`, not `meldung`.

### 3.2 Endpoint 1 – Klassifikation

`POST /api/v1/klassifikation` – part `file`.

Response 200:

```json
{
  "requestId": "…",
  "typ": "KRANKENSTANDSBESTAETIGUNG",
  "begruendung": "ÖGK-Formular Arbeitsunfähigkeitsmeldung mit Zeitraum der Arbeitsunfähigkeit.",
  "manuellePruefung": false,
  "metadaten": { … }
}
```

`typ` ∈ `KRANKENSTANDSBESTAETIGUNG`, `ZEITBESTAETIGUNG`, `UNBEKANNT`.
`manuellePruefung` = `typ == UNBEKANNT`.

Definitions to put into the instruction:
- KRANKENSTANDSBESTAETIGUNG: Krankmeldung, Arbeitsunfähigkeitsmeldung or
  Krankenstandsbestätigung (e.g. ÖGK, doctor); confirms inability to work for one or more days.
- ZEITBESTAETIGUNG: confirms presence at an appointment (doctor, outpatient clinic, authority,
  court, AMS) on a date, usually with a time from–to. Does not confirm inability to work.
- UNBEKANNT: anything else, or not clearly assignable.

`begruendung`: one sentence, must not contain medical details.

### 3.3 Endpoint 2 – Krankenstand (doctor's note)

`POST /api/v1/extraktion/krankenstand` – parts `file`, optional `vorname`, `familienname`, `svnr`.
`dokumenttyp` = `KRANKENSTAND`. `daten`:

| Field | Type | Description for the model | natif field |
|-------|------|---------------------------|-------------|
| `vorname` | String | First name of the person unable to work | `vorname` |
| `familienname` | String | Family name | `familienname` |
| `versicherungsnummer` | String | 10-digit Austrian SVNR, digits only, no spaces | `versicherungsnummer` (was a number) |
| `krankenstandsadresse` | String | Address during sick leave, single line | `krankenstandsadresse` |
| `arbeitsunfaehigVon` | LocalDate | First day of inability to work | `arbeitsunfaehig_von` |
| `letzterTagArbeitsunfaehigkeit` | LocalDate | Last day of inability to work, exactly as on the document; null if not stated | `letzter_tag_der_arbeitsunfaehigkeit` |
| `ausstellungsdatum` | LocalDate | Issue date of the document | `ausstellungsdatum` |

natif's `grund_der_arbeitsunfaehigkeit` is intentionally **not** extracted (data minimisation).

### 3.4 Endpoint 3 – Zeitbestätigung (excuse note)

`POST /api/v1/extraktion/zeitbestaetigung` – parts `file`, optional `vorname`, `familienname`.
`dokumenttyp` = `ZEITBESTAETIGUNG`. `daten`:

| Field | Type | Description for the model | natif field |
|-------|------|---------------------------|-------------|
| `vorname` | String | First name of the person whose presence is confirmed | `vorname` |
| `familienname` | String | Family name | `familienname` |
| `datumVon` | LocalDate | Date of the appointment, or first day | `datum_von` |
| `datumBis` | LocalDate | Last day, only if the confirmation spans several days; else null | `datum_bis` |
| `zeitVon` | LocalTime (HH:mm) | Start time | `zeit_von` |
| `zeitBis` | LocalTime (HH:mm) | End time | `zeit_bis` |
| `ausstellungsdatum` | LocalDate | Issue date | `ausstellungsdatum` |
| `grundDerAbwesenheit` | String | Kind of appointment as written (e.g. Arzttermin, Behördentermin); null if not stated | `grund_der_abwesenheit` |
| `aussteller` | String | Issuing practice/authority | – (new) |

Instruction must state: time from and time to are two separate values; if only one time is
on the document, the other is null. Do not copy `ausstellungsdatum` into `datumVon`.

### 3.5 Endpoint 4 – Kompetenzprofil

`POST /api/v1/extraktion/kompetenzprofil` – part `file`.
`dokumenttyp` = `KOMPETENZPROFIL`. `daten`:

| Field | Type | Description for the model | natif field |
|-------|------|---------------------------|-------------|
| `vorname` | String | First name | `vorname` |
| `nachname` | String | Last name | `nachname` |
| `geburtsdatum` | LocalDate | Date of birth | `geburtsdatum` |
| `versicherungsnummer` | String | 10-digit SVNR, digits only; null if absent | `sv_nr` |
| `fachlich` | List<Kompetenz> | Professional competencies, one entry per table row, in document order | `fachlich` |
| `ueberfachlich` | List<Kompetenz> | Transversal competencies, same rules | `ueberfachlich` |
| `zertifikate` | List<String> | Certificates, if listed | `zertifikate` |
| `interessengebiete` | List<String> | Areas of interest, if listed | `interessengebiete` |

`Kompetenz` = `{ "bezeichnung": String, "score": Integer }` – score 0–100 as on the document.
Instruction: each score belongs to the competency in the same row; omit empty rows.
Lists are empty (`[]`), never null, in the response.

natif fields not (yet) mapped: `kostentraeger`, `pin_code`, `voraussetzung`, `suchbegriffe`,
`ams_6_steller`, `teil_der_berufsobergruppe` → open question Q4.

## 4. Validation rules

Issue = `{ feld, code, schweregrad (FEHLER|WARNUNG), meldung }`. "Heute" = `LocalDate.now(clock)`,
zone Europe/Vienna. Thresholds are configurable under `docai.validation.*`.

### 4.1 Shared rules

| Code | Severity | Rule |
|------|----------|------|
| `PFLICHTFELD_FEHLT` | FEHLER | Required field (per endpoint below) is null or blank |
| `SVNR_FEHLT` | WARNUNG | SVNR expected (endpoint 2) but not found |
| `SVNR_PRUEFZIFFER_UNGUELTIG` | FEHLER | SVNR present but fails §4.5 |
| `SVNR_WEICHT_AB` | FEHLER | Hint `svnr` given and differs from extracted SVNR (after removing whitespace) |
| `NAME_WEICHT_AB` | WARNUNG | Hint `vorname`/`familienname` given and does not match (§4.6); one issue per field |
| `DATUM_ZUKUNFT` | WARNUNG | Date more than `max-days-in-future` (default 14) after today |
| `DATUM_ALT` | WARNUNG | Date more than `max-days-in-past` (default 90) before today |

### 4.2 Krankenstand

Required: `vorname`, `familienname`, `arbeitsunfaehigVon`. SVNR expected.
Date checks on `arbeitsunfaehigVon`.

| Code | Severity | Rule |
|------|----------|------|
| `ENDE_FEHLT` | WARNUNG | `letzterTagArbeitsunfaehigkeit` null |
| `ENDE_VOR_BEGINN` | FEHLER | `letzterTagArbeitsunfaehigkeit` < `arbeitsunfaehigVon` |
| `DAUER_UNGEWOEHNLICH` | WARNUNG | Duration > `max-krankenstand-days` (default 60) |

### 4.3 Zeitbestätigung

Required: `vorname`, `familienname`, `datumVon`. Date checks on `datumVon`.

| Code | Severity | Rule |
|------|----------|------|
| `ENDE_VOR_BEGINN` | FEHLER | `datumBis` < `datumVon` |
| `UHRZEIT_FEHLT` | WARNUNG | `zeitVon` or `zeitBis` null |
| `UHRZEIT_REIHENFOLGE` | FEHLER | same day and `zeitBis` ≤ `zeitVon` |

### 4.4 Kompetenzprofil

Required: `vorname`, `nachname`. SVNR optional (checksum only if present). Per list entry
(`feld` = e.g. `fachlich[2]`):

| Code | Severity | Rule |
|------|----------|------|
| `KEINE_KOMPETENZEN` | WARNUNG | `fachlich` empty |
| `SCORE_OHNE_BEZEICHNUNG` | FEHLER | score set, bezeichnung null/blank |
| `BEZEICHNUNG_OHNE_SCORE` | WARNUNG | bezeichnung set, score null |
| `SCORE_AUSSERHALB` | FEHLER | score < 0 or > 100 |

### 4.5 SVNR checksum

Format `LLLC DDMMYY`: 10 digits after removing whitespace, first digit ≠ 0.
Weights `3 7 9 0 5 8 4 2 1 6`; `s = Σ digit[i] × weight[i] mod 11`.
Valid iff `s != 10` and `s == digit[3]`.
Test data: `1237010180`, `4568 150392` valid; `1121300795` invalid (from natif sample,
returned there with confidence 0.998).

### 4.6 Name matching

Normalise both sides: Unicode NFD, strip combining marks, `ß`→`ss`, `ı`→`i`, lowercase,
remove everything except `a–z`. Match if equal or one contains the other (double names).
Test data: `Müller-Lüdenscheid` ~ `Muller Ludenscheid` ✓, `Yilmaz` ~ `Yılmaz` ✓,
`Fischer` ~ `Fisher-Meier` ✗.

## 5. Input limits

| Check | Limit / rule |
|-------|--------------|
| File size | 20 MB (`spring.servlet.multipart.max-file-size`) |
| Types | `application/pdf`, `image/png`, `image/jpeg`; detect by magic bytes, not only by Content-Type |
| Pages | ≤ `docai.intake.max-pages` (default 5) |
| PDF | not encrypted / password-protected, parsable |
| Images | decodable by ImageIO (HEIC/WebP are not supported) |

## 6. Errors

`application/problem+json`, `type` = `https://doc-ai/errors/<slug>`, always includes `requestId`.

| Status | Slug | When |
|--------|------|------|
| 400 | `missing-file` | no `file` part / empty file |
| 413 | `file-too-large` | size limit exceeded |
| 415 | `unsupported-type` | not PDF/PNG/JPEG, or undecodable image |
| 422 | `unreadable-document` | corrupt or encrypted PDF, too many pages |
| 502 | `model-error` | provider error, or output not mappable after retry |
| 503 | `model-unavailable` | provider unreachable / rate limited (include `Retry-After` if known) |
| 504 | `model-timeout` | model call exceeds `docai.ai.timeout` (default 60 s) |
| 401/403 | – | security (§7) |

Extraction with missing fields is **not** an error: 200 with null fields and issues.

## 7. Security and data protection

- OAuth2 resource server, JWT from Azure Entra ID (client-credentials flow from the calling backend).
  Config: `spring.security.oauth2.resourceserver.jwt.issuer-uri`,
  `spring.security.oauth2.resourceserver.jwt.audiences` (expected audience(s)), required app
  role `DocAi.Process` in `docai.security.required-role`. All `/api/**` require it. Actuator
  `health` open, rest secured.
- Profile `local` may disable auth; it must never be active together with `prod`.
- Documents and extracted data are processed in memory only; nothing persisted.
- Logging restrictions: see CLAUDE.md "Hard rules".
- The hosted model provider must not be used with real documents until approved by data
  protection (Krankenstandsbestätigungen are health data, GDPR Art. 9). Add a startup WARN log
  when a hosted model profile - `azure-openai` or `anthropic` - is active together with `prod`.
  An EU Data Zone deployment of Azure OpenAI keeps processing in the EU, which strengthens the
  case for that approval but does not replace it.

## 8. Configuration

```yaml
docai:
  intake:   { render-dpi: 150, max-image-edge: 1600, max-pages: 5 }
  ai:       { timeout: 60s, retries-on-mapping-error: 1 }
  validation: { max-days-in-past: 90, max-days-in-future: 14, max-krankenstand-days: 60 }
spring.threads.virtual.enabled: true
```

Provider profiles (exactly one active), using `spring.ai.model.chat` to select the provider.
Only chat is used, so `spring.ai.model.embedding`, `spring.ai.model.image` and
`spring.ai.model.audio.transcription` are all `none` - the Azure OpenAI starter otherwise claims
image and audio transcription and refuses to start without an endpoint, in every profile:

| Profile | Provider | Default model | Notes |
|---------|----------|---------------|-------|
| `azure-openai` (default) | Azure OpenAI | `gpt-4.1` | endpoint via `AZURE_OPENAI_ENDPOINT`, key via `AZURE_OPENAI_API_KEY`; the model is addressed by deployment name, `AZURE_OPENAI_DEPLOYMENT`, default `gpt-4.1`, ideally an EU Data Zone deployment; max-tokens 4096; temperature 0 |
| `anthropic` | Anthropic API | `claude-sonnet-5` | key via `ANTHROPIC_API_KEY`; max-tokens 4096 |
| `ollama` | local Ollama | `gemma3:4b` | base URL via `OLLAMA_BASE_URL`; `num-ctx: 16384`; wiring only, see below |

Model names are configuration, not code. The `provider` value in `metadaten` and in the §10
metrics is derived from the chat model class, so the `azure-openai` profile reports
`azureopenai`.

The profiles are not interchangeable. `anthropic` is the profile the endpoints are specified
against: on the fixtures in `src/test/resources/fixtures/` it extracts every field correctly and
classifies every document correctly (§12, measured 2026-09-30). `azure-openai` is the default
because it is the provider meant to run on Azure, not because of a result: it has not yet been
through §12, and until it clears that close to the `anthropic` result on the same documents,
`anthropic` stays the reference. The `ollama` profile exists so
that the service can be run and developed without a hosted model - intake, rendering, validation,
the error paths of §6 and the response envelope are all exercised through it - and not because a
local model is currently an alternative for extraction.

That is a measurement rather than a caution. `gemma3:4b`, the current local default, returns
schema-shaped filler instead of what the page says: one constant document class for every input,
a placeholder insurance number, the same placeholder in every date field. One of 29 compared
fields matched. Its validation issues are symptoms of that filler, so on this profile a 200 with
issue codes means the model failed, not that the document was unusual - which makes a manual
request against it a poor way to judge a change.

A local model may be treated as a substitute for the hosted one once it clears §12 close to the
hosted result on the same documents, and not before. Two constraints for whoever looks:

- It has to stay resident beside the `num-ctx: 16384` above, which on a 6 GB laptop GPU leaves
  roughly 5 GB. `gemma3:4b` meets that (4.6 GB, fully on the GPU) and is the default for that
  reason alone. `qwen2.5vl:7b` does not load there at all, and the requirement sits in the weights
  and the vision projector rather than the KV cache, so lowering `num-ctx` does not recover it;
  its 3B sibling stays on the CPU whatever the context.
- No model that fits 6 GB has yet been shown to do this task. The budget and the accuracy bar have
  not so far been satisfiable together on that hardware, which is worth knowing before Q2 of §13
  is decided in favour of local-only.

## 9. Dependencies

spring-boot-starter-web, -validation, -actuator, -security, -oauth2-resource-server;
spring-ai-bom 1.1.x with spring-ai-starter-model-azure-openai, spring-ai-starter-model-anthropic
and spring-ai-starter-model-ollama;
org.apache.pdfbox:pdfbox 3.0.x; springdoc-openapi-starter-webmvc-ui (version compatible with
Boot 3.5); micrometer (via actuator); org.projectlombok:lombok (version from the Boot BOM,
`compileOnly` + `annotationProcessor`) - `@Slf4j` and `@RequiredArgsConstructor` only, see §10.
Test: spring-boot-starter-test, spring-security-test.

## 10. Observability

- Actuator: `health` (liveness/readiness), `info`, `metrics`, `prometheus` if registry present.
- Metrics: `docai.requests` counter tagged `endpoint`, `outcome` (ok / review / error);
  `docai.model.call` timer tagged `endpoint`, `provider`, `model`;
  `docai.model.tokens` counter tagged `direction` (input/output), `model`.
- OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html` (disabled in `prod`).
- Loggers are declared with Lombok's `@Slf4j` on the class, giving a `log` field, to match the
  company standard. SLF4J over Boot's default Logback backend. This is a declaration style
  only and does not relax the rule that document content, extracted values, names, SVNR and
  participant hints are never logged.
- Lombok's use is limited to `@Slf4j` and `@RequiredArgsConstructor` (constructor injection of
  `final` dependencies, per the company standard). Value-type annotations - `@Data`, `@Value`,
  `@Builder`, `@Getter`/`@Setter` - are not used: DTOs, extraction models and
  `@ConfigurationProperties` stay Java records (§9, code conventions).

## 11. Testing requirements

- Unit tests for every validation rule in §4, including the test data in §4.5 and §4.6.
- Intake tests with PDFs and images generated in the test (PDFBox), covering: multi-page,
  too many pages, encrypted PDF, wrong type, oversized image scaling, EXIF rotation.
- Controller tests (MockMvc) for all four endpoints with a mocked `DocumentAiClient`,
  covering 200, `manuellePruefung` true/false, and every error in §6 that is reachable.
- Security tests: 401 without token, 403 without role, 200 with role (`spring-security-test`).
- A test asserting no extracted values or hints appear in log output for a sample request.
- Optional real-model tests tagged `llm`, excluded by default, using synthetic fixtures in
  `src/test/resources/fixtures/`.

## 12. Evaluation tool (non-endpoint)

Profile `eval` (no web server): processes `eval/input/{klassifikation,krankenstand,
zeitbestaetigung,kompetenzprofil}/*`, writes one JSON per file plus `summary.csv`
(file, endpoint, typ, manuellePruefung, issue codes, tokens, dauerMs) to `eval/output/`.
If `eval/expected/<same-name>.json` exists, compare field by field and add per-field
match columns. `eval/` is git-ignored.

## 13. Open questions

- Q1: Is authentication via Entra ID wanted from the start, or is network isolation enough for
  v1? Answered by the deployment: authentication from the start, and no isolation — the deployed
  service has public ingress, because isolation would lock out its only caller.
- Q2: Hosted model vs. local-only – pending data protection.
- Q3: Should endpoint 1 also recognise `KOMPETENZPROFIL` (for misrouted uploads)?
- Q4: Which remaining natif AMS fields (§3.5) does the consumer actually use? Open by
  construction: there is no consumer to ask (see the note at the top), so the field set is a
  choice rather than a requirement.
- Q5: Path naming – German (`/extraktion/krankenstand`) as drafted, or English?
