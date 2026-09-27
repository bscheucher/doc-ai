# doc-ai – Implementation plan

Build in this order. Each phase ends with `./gradlew build` green, a short summary of what
changed (and any deviation from SPEC.md), then **stop for review**.

## Phase 0 – Scaffold

- Generate project (Spring Initializr or by hand): Gradle Kotlin DSL, Java 21 toolchain,
  Spring Boot 3.5.x, dependencies from SPEC §9 (security deps can wait until phase 5).
- Gradle wrapper committed. `.gitignore` incl. `eval/`.
- `application.yml` with `docai.*` defaults and the `anthropic` / `ollama` profiles (SPEC §8).
- `Clock` bean (Europe/Vienna), virtual threads on, actuator health.
- Gradle: exclude JUnit tag `llm` unless `-PincludeLlmTests` is set.

Done when: app starts with `ollama` profile without a running Ollama (no eager connection),
`/actuator/health` returns UP, build green.

## Phase 1 – Intake and error handling

- `intake` package: upload validation (SPEC §5, magic-byte detection) and conversion to page
  images (SPEC §2 step 1), returning an internal `PageImages` value (list of PNG bytes + page count).
- `api` package: `@RestControllerAdvice` producing ProblemDetails (SPEC §6) for intake errors,
  request-id filter (`X-Request-Id`, MDC key `requestId`).
- Tests per SPEC §11 "Intake".

Done when: all intake error cases map to the right status/slug in a test.

## Phase 2 – AI client and endpoint 1 (Klassifikation)

- `ai` package: interface `DocumentAiClient` with
  `<T> AiResult<T> extract(String instruction, PageImages pages, Class<T> type)`;
  `AiResult` holds value + metadata (provider, model, tokens, duration).
  Spring AI implementation: shared system prompt, images as `Media`, structured output,
  timeout, one retry on mapping failure, maps provider exceptions to 502/503/504.
- Prompts as plain-text resources under `src/main/resources/prompts/` (no curly braces).
- `klassifikation` package: record, service, controller for `POST /api/v1/klassifikation`.
- MockMvc tests with a mocked `DocumentAiClient`.

Done when: endpoint returns SPEC §3.2 shape; UNBEKANNT sets `manuellePruefung`.

## Phase 3 – Validation framework and endpoints 2 + 3

- `validation` package: `ValidationIssue`, codes enum, `SvnrValidator`, name matcher,
  shared rules (SPEC §4.1), thresholds via `@ConfigurationProperties`.
- `krankenstand` and `zeitbestaetigung` packages: record with `@JsonPropertyDescription`
  texts from SPEC §3.3/§3.4, validator, service, controller; participant hints as multipart fields.
- Shared response envelope (SPEC §3.1).
- Unit tests for every rule + MockMvc tests.

Done when: all codes in SPEC §4.1–4.3 are covered by at least one test.

## Phase 4 – Endpoint 4 (Kompetenzprofil)

- `kompetenz` package per SPEC §3.5 and §4.4; null lists normalised to `[]`.
- Tests incl. the natif failure case: score present without bezeichnung.

## Phase 5 – Security

- Resource server config per SPEC §7, `local` profile without auth, guard against
  `local` + `prod`, startup warning for `anthropic` + `prod`.
- Security tests per SPEC §11.

## Phase 6 – Observability, docs, evaluation

- Metrics per SPEC §10, springdoc OpenAPI with examples for all four endpoints.
- Log-content test (SPEC §11).
- `eval` profile runner per SPEC §12.
- README: run, configure, evaluate.

## Phase 7 (optional) – Real-model smoke tests

- `llm`-tagged tests against synthetic fixtures for each endpoint, asserting key fields
  (e.g. dates, times split correctly) rather than exact strings.
