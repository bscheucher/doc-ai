# CLAUDE.md – doc-ai

Internal REST service that classifies and extracts data from scanned documents using a
vision LLM, replacing the natif.ai workflows a calling backend uses today.

Private learning project: one operator, no real consumer, nothing in production. The SPEC is
written as a realistic brief because that is the exercise - see the note at the top of it. The
contracts are meant literally; only the consumer is imagined.

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
./gradlew test -PincludeLlmTests   # optional, calls a real model, never in CI
./gradlew generateFixtures      # rewrite the sample documents in src/test/resources/fixtures

# Running it. Any profile other than 'local' is a secured resource server (SPEC §7) and needs
# DOCAI_JWT_ISSUER_URI and DOCAI_JWT_AUDIENCE, or it fails at startup naming the missing one.
# 'local' turns authentication off, and naming any profile replaces spring.profiles.default,
# so the model profile has to be named alongside it:
./gradlew bootRun --args='--spring.profiles.active=local,azure-openai'   # default; needs AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_API_KEY
./gradlew bootRun --args='--spring.profiles.active=local,anthropic'   # needs ANTHROPIC_API_KEY
./gradlew bootRun --args='--spring.profiles.active=local,ollama'
```

## Code conventions

- Package root `com.learning.docai`, layered by feature:
  `intake`, `ai`, `klassifikation`, `krankenstand`, `zeitbestaetigung`, `kompetenz`,
  `validation`, `api` (shared DTOs, error handling), `config`.
- Domain names stay German (Krankenstand, Zeitbestaetigung, Kompetenzprofil, Teilnehmer);
  technical names in English.
- DTOs and extraction models are Java records. Constructor injection only. No field injection.
- Loggers via Lombok `@Slf4j` on the class (company standard); use the generated `log` field.
- Constructor injection via Lombok `@RequiredArgsConstructor` over an explicit constructor;
  dependencies are `private final` fields.
- Lombok is limited to those two annotations - no `@Data`/`@Value`/`@Builder`/`@Getter`;
  records cover those.
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
