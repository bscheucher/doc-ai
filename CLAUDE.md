# CLAUDE.md – doc-ai

Internal REST service that classifies and extracts data from scanned documents using a
vision LLM. It replaces the natif.ai workflows currently called from the ibosNG backend.

Read these before writing code:
- `src/docs/doc-ai-specs/docs/SPEC.md` – what to build (endpoints, contracts, validation rules). Source of truth.
- `src/docs/doc-ai-specs/docs/IMPLEMENTATION_PLAN.md` – the order to build it in, phase by phase.
- `src/docs/doc-ai-specs/docs/reference/natif/*.json` – current natif responses, for field mapping only.

## Working agreement

- Work one phase of `src/docs/doc-ai-specs/docs/IMPLEMENTATION_PLAN.md` at a time. At the end of a phase, run the
  build, summarise what changed, and stop for review before starting the next phase.
- If the spec is ambiguous or contradicts itself, ask. Do not guess on API contracts.
- Do not add dependencies beyond those listed in SPEC.md §9 without asking.
- Update SPEC.md only when asked; note deviations in your phase summary instead.

## Stack

Java 21, Spring Boot 3.5.x, Spring AI 1.1.x (not 2.x), Gradle Kotlin DSL, PDFBox 3.

## Commands

```bash
./gradlew build                 # compile + all tests; must pass at the end of every phase
./gradlew test
./gradlew bootRun               # default profile: anthropic (needs ANTHROPIC_API_KEY)
./gradlew bootRun --args='--spring.profiles.active=ollama'
./gradlew test -PincludeLlmTests   # optional, calls a real model, never in CI
```

## Code conventions

- Package root `com.learning.docai`, layered by feature:
  `intake`, `ai`, `klassifikation`, `krankenstand`, `zeitbestaetigung`, `kompetenz`,
  `validation`, `api` (shared DTOs, error handling), `config`.
- Domain names stay German (Krankenstand, Zeitbestaetigung, Kompetenzprofil, Teilnehmer);
  technical names in English.
- DTOs and extraction models are Java records. Constructor injection only. No field injection.
- Loggers via Lombok `@Slf4j` on the class (company standard); use the generated `log` field.
  Lombok is for logging only - do not use `@Data`/`@Builder`/`@Value`; records cover those.
- Configuration via `@ConfigurationProperties` records, prefix `docai`.
- Controllers stay thin: HTTP mapping only; logic lives in services.
- Errors as RFC 7807 `ProblemDetail` via one `@RestControllerAdvice`.
- Use `java.time` with an injected `Clock` (zone Europe/Vienna); never `LocalDate.now()` without it.

## Hard rules

- **No real LLM calls in regular tests.** Mock the AI client interface. Real-model tests are
  tagged `llm` and excluded by default.
- **Never log document content, extracted values, names, SVNR or participant hints.**
  Log only requestId, endpoint, document type, page count, issue codes, token counts, durations.
- **Never persist uploads.** Process in memory; no temp files, no caching of documents or results.
- Prompt texts must not contain `{` or `}` (Spring AI renders them as templates).
- Do not extract diagnoses or medical details, even if visible on the document.
