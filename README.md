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
| `ollama` | `gemma3:4b` | Ollama at `OLLAMA_BASE_URL`, default `http://localhost:11434` |
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

## Deploy

Azure Container Apps, described by Bicep in `deploy/`. Nothing here is referenced by the
application; it is deployment only.

| File | What it creates |
|------|-----------------|
| `deploy/infra.bicep` | Container registry, Container Apps environment (**internal** ingress), user-assigned identity with `AcrPull`, Log Analytics workspace |
| `deploy/app.bicep` | The container app: image, probes, scaling, environment, the API key as a secret |
| `deploy/infra.parameters.json` | Names, region, network addresses |
| `deploy/deploy.sh` | Applies both, with the image build in between |
| `deploy/README.md` | Step-by-step guide: what the CLI does, what gets created, what is still missing |

New to the Azure CLI, or picking this up cold: read **`deploy/README.md`** rather than this
section. It covers the resource model, the difference between `validate`, `what-if` and
`create`, and the three prerequisites that are not in this repository.

```bash
az login
ANTHROPIC_API_KEY=sk-...                                             \
DOCAI_JWT_ISSUER_URI=https://login.microsoftonline.com/<guid>/v2.0   \
DOCAI_JWT_AUDIENCE=<api-client-id>                                   \
  ./deploy/deploy.sh rg-docai-test
```

The script needs `az`, `jq` and a reachable Docker daemon (the image is built locally by
buildpacks, as `./gradlew bootBuildImage`). It is idempotent — re-running it applies the
infrastructure unchanged and adds a revision.

Two templates rather than one because the app cannot be created before its image exists and the
image cannot be pushed before the registry does: `deploy.sh` applies `infra.bicep`, pushes, then
applies `app.bicep`.

### What the deployed app runs with

| | |
|---|---|
| Profiles | `prod,anthropic` — Swagger UI off (SPEC §10), hosted model (SPEC §8) |
| CPU / memory | 1.0 / 2.0Gi — PDFBox rasterises up to 5 pages at 150 dpi in memory; the 0.5/1Gi default is too tight |
| Replicas | 1–5, scaled on 4 concurrent requests, `minReplicas: 1` so no caller waits on a JVM cold start |
| Probes | `/actuator/health/{liveness,readiness}`, public by `SecurityConfig` because the platform has no token |
| Image tag | `<version>-<git-sha>`, never `:latest` |

Ingress is **public**. This is a private learning project with one operator and no other caller,
and the deployment exists so the endpoints can be called from `curl` or a browser API client such
as Hoppscotch — a private address could not be. There is no VNet and no subnet; the Container Apps
environment runs on Azure-managed networking. An earlier revision of these templates was
VNet-injected and internal-only, which is the right shape when another system calls the service
from inside the same network, and the wrong one here; git history has it.

Authentication is therefore the only barrier, and it is unchanged (SPEC §7): every `/api/**` call
needs a client-credentials token carrying the `DocAi.Process` app role, and a request without one
never reaches a controller. `/actuator/health` is open because the platform's probes have no
token. `/v3/api-docs` stays behind a token and the Swagger UI is off under `prod`.

`deploy/README.md` has the full walkthrough, including how to mint a token and call each
endpoint.

### Data protection

These templates deploy the `anthropic` profile, which sends page images to the Anthropic API.
**SPEC §7 permits that for synthetic documents only** until data protection approves it —
Krankenstandsbestätigungen are GDPR Art. 9 health data, and §13 Q2 is open. The app logs a WARN
at startup saying so (`HostedModelWarning`). Before real participant documents reach this
deployment, either that approval exists or the provider has to change.

The API key is a container app secret, taken from the environment by `deploy.sh` and written
through a `0600` temporary file so it never appears in a command line. For an environment that
sees real data, give the already-deployed identity (`id-docai-<env>`) `get` on a Key Vault secret
and replace the `secrets` entry in `app.bicep` with a `keyVaultUrl` reference, so the key is not
readable from the app's own configuration.

## Layout

`com.learning.docai`, by feature: `intake` (upload validation, page images), `ai` (the only
package that knows Spring AI), `klassifikation`, `krankenstand`, `zeitbestaetigung`, `kompetenz`,
`validation`, `eval`, `api` (shared DTOs, error handling, metrics), `config`.

Domain names stay German, technical names English. Development conventions and the phase plan:
`CLAUDE.md` and `src/docs/doc-ai-specs/docs/IMPLEMENTATION_PLAN.md`.
