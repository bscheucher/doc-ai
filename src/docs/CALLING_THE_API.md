# Calling the doc-ai API

How to send a request to the deployed service, with `curl` and with
[hoppscotch.io](https://hoppscotch.io). Every command here has been run against the live
deployment, and the responses quoted are what it returned.

> doc-ai is a private learning project: one operator, no other consumer. "The caller" in the SPEC
> means you, from a terminal or an API client. See the note at the top of
> `doc-ai-specs/docs/SPEC.md`.

**Base URL**

```
https://doc-ai.livelypond-7c5784c0.germanywestcentral.azurecontainerapps.io
```

It changes if the environment is ever rebuilt, so take it from Azure rather than from here when in
doubt:

```bash
URL="https://$(az containerapp show -g rg-docai-test -n doc-ai \
      --query properties.configuration.ingress.fqdn -o tsv)"
```

**What is open and what is not**

| Path | Token needed |
|---|---|
| `GET /actuator/health` | no — the platform's probes carry none |
| `POST /api/**` | yes: a token carrying the `DocAi.Process` app role (SPEC §7) |
| `GET /v3/api-docs` | yes |
| Swagger UI | not served at all under the `prod` profile (SPEC §10) |

---

## Part 1 — curl

### Step 1: environment and token

Once per shell. `client-test.env` holds the test client's id and secret, mode `600`, outside the
repository.

```bash
cd ~/projects/ai/doc-ai
. ~/.config/docai/client-test.env
: "${DOCAI_TEST_TENANT:?source ~/.config/docai/client-test.env first}"

URL=https://doc-ai.livelypond-7c5784c0.germanywestcentral.azurecontainerapps.io

RESP=$(curl -sS -X POST \
  "https://login.microsoftonline.com/$DOCAI_TEST_TENANT/oauth2/v2.0/token" \
  -d grant_type=client_credentials \
  -d client_id="$DOCAI_TEST_CLIENT_ID" \
  -d client_secret="$DOCAI_TEST_CLIENT_SECRET" \
  -d scope="api://$DOCAI_TEST_API_ID/.default")

TOKEN=$(jq -r '.access_token // empty' <<<"$RESP")
[ -n "$TOKEN" ] && echo "token ok, ${#TOKEN} chars" \
  || jq -r '.error_description // .error // "empty response"' <<<"$RESP"
```

Expect `token ok, 1290 chars`.

**Why it is written defensively.** The obvious one-liner —
`TOKEN=$(curl -s ... | jq -r .access_token)` — fails silently in a way that wastes real time. If
`client-test.env` was not sourced, the tenant is empty, the URL becomes
`login.microsoftonline.com//oauth2/v2.0/token`, and that returns an **empty body**: `jq` emits
nothing, `TOKEN` is empty, and nothing is printed. The `:?` guard stops it at the cause, and the
error branch surfaces whatever Entra actually said.

Reading the length tells you which failure you have:

| `${#TOKEN}` | Meaning |
|---|---|
| ~1290 | fine |
| 0 | the request failed or returned nothing — the error branch above prints why |
| 4 | the literal string `null`: valid JSON came back with no `access_token` |

A token lasts about an hour. **Calls that worked and now return `401` usually just need step 1
again** — that is the common cause, not a broken deployment.

### Step 2: call an endpoint

The fixture paths below are relative, so `cd` first:

```bash
cd src/test/resources/fixtures
```

| Endpoint | Parts | Fixtures to try |
|---|---|---|
| `POST /api/v1/klassifikation` | `file` | `krankenstand.pdf`, `zeitbestaetigung.pdf`, `unbekannt.pdf` |
| `POST /api/v1/extraktion/krankenstand` | `file`, and optional `vorname`, `familienname`, `svnr` | `krankenstand.pdf` |
| `POST /api/v1/extraktion/zeitbestaetigung` | `file`, and optional `vorname`, `familienname` | `zeitbestaetigung.pdf` |
| `POST /api/v1/extraktion/kompetenzprofil` | `file` | `kompetenzprofil.pdf` |

**Classification:**

```bash
curl -sS -H "Authorization: Bearer $TOKEN" -F file=@krankenstand.pdf \
  $URL/api/v1/klassifikation | jq .
```

```json
{
  "requestId": "5e3c3f75-1929-4e2c-8c82-d1ccfe6a0015",
  "typ": "KRANKENSTANDSBESTAETIGUNG",
  "begruendung": "Das Formular ist ausdrücklich als Krankenstandsbestätigung einer Ärztin betitelt und bestätigt Arbeitsunfähigkeit über einen Zeitraum von-bis.",
  "manuellePruefung": false,
  "metadaten": {
    "provider": "anthropic", "modell": "claude-sonnet-5", "seiten": 1,
    "inputTokens": 3978, "outputTokens": 108, "dauerMs": 2437
  }
}
```

About 2 seconds warm, around 5 on the first call to a cold replica.

Try `unbekannt.pdf` too — it is an invoice for office supplies, and the useful part is that it is
**not** forced into a category: `"typ": "UNBEKANNT"` with `"manuellePruefung": true`.

**Extraction, with participant hints.** The hints are optional and are used for validation only;
they are multipart fields rather than query parameters so they never reach a URL or an access log
(SPEC §3):

```bash
curl -sS -H "Authorization: Bearer $TOKEN" -F file=@krankenstand.pdf \
  -F vorname=Max -F familienname=Mustermann -F "svnr=1238 010190" \
  $URL/api/v1/extraktion/krankenstand | jq .
```

```json
{
  "dokumenttyp": "KRANKENSTAND",
  "daten": {
    "vorname": "Max", "familienname": "Mustermann",
    "versicherungsnummer": "1238010190",
    "krankenstandsadresse": "Blumengasse 12, 1150 Wien",
    "arbeitsunfaehigVon": "2026-09-19",
    "letzterTagArbeitsunfaehigkeit": "2026-09-24",
    "ausstellungsdatum": "2026-09-20"
  },
  "probleme": [],
  "manuellePruefung": false
}
```

Note the SVNR comes back normalised (`1238 010190` in, `1238010190` out), and that no diagnosis or
medical detail is extracted even though the document shows more — that is a hard rule, not an
omission.

**Make a validation rule fire** by passing a hint that disagrees with the document:

```bash
curl -sS -H "Authorization: Bearer $TOKEN" -F file=@krankenstand.pdf \
  -F familienname=Mustermeier \
  $URL/api/v1/extraktion/krankenstand | jq '{probleme, manuellePruefung}'
```

```json
{
  "probleme": [
    { "feld": "familienname", "code": "NAME_WEICHT_AB",
      "schweregrad": "WARNUNG", "meldung": "Name weicht vom uebergebenen Wert ab." }
  ],
  "manuellePruefung": true
}
```

**Health, no token:**

```bash
curl -sS $URL/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
```

### Five rules that prevent most failures

1. **The file part is named `file`.** Not `document`, not `upload`. Any other name gives
   `400 missing-file`, which reads like a broken service rather than a typo.
2. **Use `-sS`, never bare `-s`.** The capital `S` hides the progress meter but keeps curl's own
   errors. With plain `-s`, a path that does not resolve prints nothing but `HTTP 000`.
3. **Fixture paths are relative.** `cd src/test/resources/fixtures`, or write
   `$HOME/projects/ai/doc-ai/src/test/resources/fixtures/…`.
4. **Tokens expire in about an hour.** Re-run step 1.
5. **Do not pipe `-w` into `jq`.** The status line is not JSON, so `jq` fails with
   `Invalid numeric literal` even when the call succeeded.

### Diagnosing a response

| Code | What it means | What to do |
|---|---|---|
| `000` | **Nothing was sent.** A local problem, nearly always an unresolvable file path | Re-run with `-sS` to see curl's reason |
| `401` | It reached the service; the token is missing, malformed or expired | Redo step 1 |
| `403` | The token is valid but carries no `DocAi.Process` role | Check the app role grant |
| `400` `missing-file` | It arrived, but no part named `file` | Use `-F file=@…` |
| `415` `unsupported-type` | Not a PDF, PNG or JPEG — or no file reached the server at all | Check the path resolves |
| `413` | Larger than 20 MB | — |
| `422` `unreadable-document` | More than 5 pages, 0 pages, a corrupt file, or an image the model could not read | See below — the code alone does not say which |
| `502` | The model returned something unmappable twice | Retry; check the logs |

The `000` versus `401` distinction is the one worth internalising: `000` never left your machine,
so nothing about the deployment is implicated.

**A `422` is deliberately ambiguous.** `DocumentIntakeService` raises the same
`unreadable-document` for a corrupt PDF, a PDF with no pages, and one with more pages than
`docai.intake.max-pages` (default 5). `zu-viele-seiten.pdf` returns

```json
{"status":422,"title":"unreadable-document","detail":"Das Dokument konnte nicht gelesen werden."}
```

which says nothing about page count. The service logs the reason instead, so that is where to look:

```
Rejected PDF: pages=9 max=5
```

That is intentional — the response says no more than a caller needs — but it does mean a `422`
cannot be diagnosed from the body alone.

To see the status code **and** the body, use two commands rather than combining `-w` with `jq`:

```bash
curl -sS -o /tmp/r.json -w 'HTTP %{http_code} in %{time_total}s\n' \
  -H "Authorization: Bearer $TOKEN" -F file=@krankenstand.pdf $URL/api/v1/klassifikation
jq . /tmp/r.json
```

### When the platform has the answer and curl does not

```bash
az containerapp revision list -g rg-docai-test -n doc-ai \
  --query "[].{rev:name,state:properties.runningState,health:properties.healthState}" -o table
az containerapp logs show -g rg-docai-test -n doc-ai --tail 50
```

Two WARNs at startup are expected: `HostedModelWarning` (page images go to the Anthropic API,
which SPEC §7 permits for synthetic documents only), and SpringDoc noting `/v3/api-docs` is
enabled, which `prod` keeps deliberately, behind a token.

---

## Part 2 — hoppscotch.io

### Read this first: the browser tab will not work

Opening `hoppscotch.io` in a normal tab and pointing it at this service **cannot** succeed, and it
fails in a way that looks like the service is down.

The service sends no CORS headers at all — it was never meant to be called from a web page. Worse,
the browser's preflight makes it unfixable from the page's side:

```
OPTIONS /api/v1/klassifikation      ->  HTTP 401
  (no access-control-* headers in the response)
```

A browser sends `OPTIONS` before a cross-origin POST that carries an `Authorization` header, and it
sends that preflight **without** the header. Spring Security sees an unauthenticated request to
`/api/**` and answers `401`, so the browser never sends the real request. You get a generic network
or CORS error, identical to what you would see if the service were unreachable.

So use one of the two options below. Both are official and both sidestep the page origin entirely.

### Option A: the Hoppscotch browser extension

1. Install the Hoppscotch extension for Chrome or Firefox.
2. Open its options and add the service host to the allowed origins list:
   `doc-ai.livelypond-7c5784c0.germanywestcentral.azurecontainerapps.io`
   (or `*.azurecontainerapps.io`).
3. In Hoppscotch's settings, switch the **Interceptor** from the default browser mode to the
   extension. This is the setting that actually matters — left on the browser's own fetch, nothing
   above helps. (Hoppscotch renames these options between versions, so go by the Interceptor
   section rather than an exact label.)

### Option B: the Hoppscotch desktop app

Not a web page, so not subject to CORS at all. Nothing to configure; this is the lower-friction
route if you do not want to manage an extension.

### Building the request

| Field | Value |
|---|---|
| Method | `POST` |
| URL | `https://doc-ai.livelypond-7c5784c0.germanywestcentral.azurecontainerapps.io/api/v1/klassifikation` |
| Authorization | type **Bearer**, token pasted from step 1 of Part 1 |
| Body | **multipart/form-data** — one field, key exactly `file`, type switched from *text* to *file*, then pick a fixture |

The field-type switch is easy to miss: Hoppscotch defaults a new multipart key to text, and a text
field named `file` produces `400 missing-file`, the same as a misnamed part.

Fixtures live in `src/test/resources/fixtures/` in this repository.

**Getting the token in.** Hoppscotch cannot read your shell, so run step 1 of Part 1 and copy the
value:

```bash
echo "$TOKEN"
```

To avoid pasting it into four requests, set it once as a Hoppscotch **environment variable** —
create an environment, add `token` with that value, and write `<<token>>` in the Authorization
field. Replacing an expired token is then one edit rather than four.

### A faster first test

Point Hoppscotch at `GET /actuator/health` before anything else. It needs no token and no body, so
a `200` there proves the interceptor and the host are set up correctly, and separates "my client is
misconfigured" from "my token or body is wrong". If health fails too, the problem is Option A or B,
not your request.

### If it works in curl but not in Hoppscotch

It is the interceptor, every time. Re-check that it is set to the extension (or that you are in the
desktop app) and that the host is allowed. The service cannot tell the difference between
Hoppscotch and curl — it only sees an HTTP request — so a difference in behaviour is on the client
side.

---

## Appendix

**Where the credentials live.** Both files are mode `600` and deliberately outside the repository,
because they identify a tenant:

| File | Holds | Used by |
|---|---|---|
| `~/.config/docai/deploy.env` | issuer, audience, `ANTHROPIC_API_KEY` | `deploy/deploy.sh`, automatically |
| `~/.config/docai/client-test.env` | test client id and secret | step 1 above |

**The deployment is billing** while it exists — the registry, one always-on replica and log
ingest. `az group delete -n rg-docai-test --yes --no-wait` removes it; the Entra app registrations
survive that, since they are directory objects rather than resources in the group.

**Further reading.** `deploy/README.md` covers the deployment itself: what the az CLI does, what
gets created, the Entra registrations, and how to redeploy.
