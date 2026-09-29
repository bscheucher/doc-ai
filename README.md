# doc-ai

Internal REST service that classifies and extracts data from scanned documents using a vision
LLM. It replaces the natif.ai workflows the ibosNG backend calls today.

Four stateless, synchronous endpoints. Orchestration stays with the caller: ibosNG classifies
first, then calls the matching extraction endpoint.

| # | Endpoint | Purpose |
|---|----------|---------|
| 1 | `POST /api/v1/klassifikation` | Krankenstandsbestätigung, Zeitbestätigung or UNBEKANNT |
| 2 | `POST /api/v1/extraktion/krankenstand` | Data from a doctor's note / Arbeitsunfähigkeitsmeldung |
| 3 | `POST /api/v1/extraktion/zeitbestaetigung` | Data from an appointment confirmation |
| 4 | `POST /api/v1/extraktion/kompetenzprofil` | Competencies and scores from an AMS Kompetenzprofil |

Contracts, validation rules and error catalogue: `src/docs/doc-ai-specs/docs/SPEC.md`. It is the
source of truth; this file only says how to run the thing.

**Nothing is persisted.** Documents and extracted values are processed in memory, never written
to disk and never cached. Krankenstandsbestätigungen are health data (GDPR Art. 9): the hosted
model provider must not see real documents before data protection has approved it, and the
service logs a warning at startup when the `anthropic` profile is active together with `prod`.

## Run

```bash
./gradlew build                 # compile + all tests
./gradlew bootRun --args='--spring.profiles.active=local,anthropic'   # needs ANTHROPIC_API_KEY
./gradlew bootRun --args='--spring.profiles.active=local,ollama'      # needs a local Ollama
```

The `local` profile switches authentication off and is the way to run the service by hand. Naming
any profile replaces `spring.profiles.default`, so the model profile has to be named alongside it.

Sample documents and ready-made `curl` calls: `src/test/resources/fixtures/README.md`.

```bash
./gradlew test                       # all tests, no model is called
./gradlew test -PincludeLlmTests     # also the `llm`-tagged tests, which call a real model
./gradlew generateFixtures           # regenerate the sample documents
./gradlew bootBuildImage             # OCI image via buildpacks, needs a Docker daemon
```

## Configure

One model provider per profile (SPEC §8); business code never names a provider.

| Profile | Model | Needs |
|---------|-------|-------|
| `anthropic` (default) | `claude-sonnet-5` | `ANTHROPIC_API_KEY` |
| `ollama` | `qwen2.5vl:7b` | Ollama at `OLLAMA_BASE_URL`, default `http://localhost:11434` |
| `local` | – | nothing; disables authentication, never together with `prod` |
| `prod` | – | disables the Swagger UI |
| `eval` | – | no web server; see below |

Every other profile is a secured resource server (SPEC §7) and needs these two, or it refuses to
start naming the missing one:

| Variable | Property | Notes |
|----------|----------|-------|
| `DOCAI_JWT_ISSUER_URI` | `spring.security.oauth2.resourceserver.jwt.issuer-uri` | Entra tenant, e.g. `https://login.microsoftonline.com/<tenant-guid>/v2.0`. For v2.0 the issuer is the tenant **GUID**, not the domain |
| `DOCAI_JWT_AUDIENCE` | `spring.security.oauth2.resourceserver.jwt.audiences` | The `aud` this API accepts: the client id of its app registration for v2.0 tokens |

Callers need the Entra app role `DocAi.Process` (`docai.security.required-role`) on every
`/api/**` call. `/actuator/health` is open; everything else needs a token.

Defaults worth knowing (`application.yml`, all under `docai.*`):

| Property | Default | Meaning |
|----------|---------|---------|
| `docai.intake.render-dpi` | 150 | PDF render resolution |
| `docai.intake.max-image-edge` | 1600 | longest edge of a page image, in px |
| `docai.intake.max-pages` | 5 | more pages → 422 `unreadable-document` |
| `docai.ai.timeout` | 60s | budget for the whole request, retry included → 504 |
| `docai.ai.retries-on-mapping-error` | 1 | retries when the model's answer will not map |
| `docai.validation.max-days-in-past` | 90 | older dates are reported as a finding |
| `docai.validation.max-days-in-future` | 14 | later dates are reported as a finding |
| `docai.validation.max-krankenstand-days` | 60 | longer absences are reported as a finding |

Uploads are capped at 20 MB (`spring.servlet.multipart.max-file-size`) → 413.

## Observe

`/actuator/health` (liveness and readiness probes), `/actuator/info`, `/actuator/metrics`, and
`/actuator/prometheus` once a Prometheus registry is on the classpath. Metrics per SPEC §10:

| Meter | Type | Tags |
|-------|------|------|
| `docai.requests` | counter | `endpoint`, `outcome` = `ok` \| `review` \| `error` |
| `docai.model.call` | timer | `endpoint`, `provider`, `model` |
| `docai.model.tokens` | counter | `direction` = `input` \| `output`, `model` |

`outcome=review` means the response carried `manuellePruefung: true` — a person has to look at the
document. It is the number to watch: it is not an error, but a rising share of it is.

OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html` (off in `prod`). Both need a token
outside the `local` profile.

**Logs never contain document content**, extracted values, names, SVNR or participant hints —
only requestId, endpoint, document type, page count, issue codes, token counts and durations.
`SensitiveLoggingTest` holds that line; Spring AI's `BeanOutputConverter`, which logs the model's
raw answer on a mapping failure, is silenced in `application.yml` for the same reason.

## Evaluate

The `eval` profile (SPEC §12) runs documents through the same services the endpoints use and
writes the results next to them, so a prompt or model change can be judged on a whole folder
instead of one document at a time. It opens no port. `eval/` is git-ignored.

```
eval/input/klassifikation/*      # one directory per endpoint
eval/input/krankenstand/*
eval/input/zeitbestaetigung/*
eval/input/kompetenzprofil/*
eval/expected/<name>.json        # optional reference, per document
eval/output/                     # written by the run
```

```bash
mkdir -p eval/input/krankenstand
cp src/test/resources/fixtures/krankenstand.pdf eval/input/krankenstand/
./gradlew bootRun --args='--spring.profiles.active=eval,anthropic'
```

Output is `eval/output/<endpoint>/<name>.json` — the exact response the API would have sent — plus
`eval/output/summary.csv` with one row per document:

```
datei,endpunkt,typ,manuellePruefung,codes,inputTokens,outputTokens,dauerMs
krankenstand.pdf,krankenstand,KRANKENSTAND,false,,1834,156,3120
```

A document the service rejects gets the error slug of SPEC §6 in the `typ` column and does not
stop the run.

Put a reference file at `eval/expected/<name>.json` to get per-field comparison columns
(`match.<field>`). It may be a whole response envelope or just the `daten` object, and only the
fields it names are compared — a reference that fixes two dates asks about those two dates:

```json
{ "arbeitsunfaehigVon": "2026-09-21", "letzterTagArbeitsunfaehigkeit": "2026-09-25" }
```

**This calls a real model with whatever is in `eval/input`,** which makes it the one place where
the data-protection constraint above applies directly.

## Layout

`com.learning.docai`, by feature: `intake` (upload validation, page images), `ai` (the only
package that knows Spring AI), `klassifikation`, `krankenstand`, `zeitbestaetigung`, `kompetenz`,
`validation`, `eval`, `api` (shared DTOs, error handling, metrics), `config`.

Domain names stay German, technical names English. Development conventions and the phase plan:
`CLAUDE.md` and `src/docs/doc-ai-specs/docs/IMPLEMENTATION_PLAN.md`.
