# Deploying doc-ai to Azure Container Apps

Written for someone who has not used the Azure CLI before. Three parts: what has actually been
done, how the CLI works, and how this deployment works end to end.

**It is deployed and working.** The endpoints answer from the public internet and have been called
end to end with real documents — go straight to
[Calling the endpoints](#calling-the-endpoints) for commands that have been run as written.

**What this is.** doc-ai is a **private learning project** with a single operator and no other
caller. The SPEC is written in the register of work software — a brief for an internal service
with a calling backend, German domain terms throughout — because building against a realistic
brief is the exercise. Nothing else consumes this service, and this deployment exists so that one
person can exercise the API from `curl` and a browser API client such as Hoppscotch. Read every
"the caller" in this guide as "you, from your laptop". No integration with any other system is
planned here, and no decision below should be weighed against one.

That is why ingress is **public**. An earlier version of these templates put the app on a VNet
with internal-only ingress, which is the right shape for a service another system calls from
inside the same network — and the wrong shape for this one, because a private address cannot be
reached from curl on a laptop, let alone from a browser. Git history has that variant.

**What protects it is authentication, not obscurity.** Every `/api/**` call needs an Entra token
carrying the `DocAi.Process` role (SPEC §7). `/actuator/health` is deliberately open, because the
platform's own probes carry no token.

---

## Part 1 — What has been done

### In this repository

| | |
|---|---|
| Branch | `azure-container-apps` |
| Pull request | [#15](https://github.com/bscheucher/doc-ai/pull/15) |
| Files | `deploy/infra.bicep`, `deploy/app.bicep`, `deploy/infra.parameters.json`, `deploy/deploy.sh`, a `Deploy` section in the root `README.md`, and this guide |

`./gradlew build` is unchanged and green. Nothing under `src/` was touched — no source file,
test or Gradle task references `deploy/`.

### In Azure

Three things, all free:

1. **A resource group**, `rg-docai-test` in westeurope. Empty. A resource group is just a folder;
   an empty one costs nothing.
2. **Five resource providers registered** on the subscription: `Microsoft.App`,
   `Microsoft.ContainerRegistry`, `Microsoft.OperationalInsights`, `Microsoft.ManagedIdentity`,
   `Microsoft.Network`. Registration is free, one-off per subscription, and a prerequisite for
   creating any resource of those kinds.
3. **The Entra app registrations** — `doc-ai-api` with the `DocAi.Process` app role, and
   `docai-test-client` with that role granted to it. See
   [The Entra app registrations](#the-entra-app-registrations). App registrations are free and
   are directory objects, not subscription resources, so `az group delete` does **not** remove
   them.

**No billable resource exists.** No registry, no environment, no container app. The image is
built, but it is sitting in the local Docker daemon, which costs nothing.

### What was verified

| Check | Result |
|---|---|
| `az bicep build` on both templates | exit 0 |
| `az bicep lint` on both templates | exit 0, no findings |
| Parameters file vs. template parameters | nothing undefined, nothing required missing |
| `az deployment group validate` (infra) | passes in germanywestcentral, northeurope, swedencentral, francecentral |
| `az deployment group what-if` (infra) | **4 resources to create**, no errors |
| A real client-credentials token vs. what `SecurityConfig` checks | `iss`, `aud`, `roles` and `ver` all match |
| `./gradlew bootBuildImage` | succeeds in 2m57s; 604MB, non-root `1002:1001` |
| The built image's auth chain, run locally against live Entra | 401 / 401 / 415 — see [Smoke-testing the image locally](#smoke-testing-the-image-locally) |

The four: the Log Analytics workspace, the container registry with `adminUserEnabled: false`, the
user-assigned identity with its `AcrPull` role assignment, and the Container Apps environment on
Azure-managed networking.

### One thing the verification found

The first validate, in westeurope, failed on **every** resource with the same error:

```
RequestDisallowedByAzure - Resource 'log-docai-test' was disallowed by Azure:
The selected region is currently not accepting new customers.
```

That is not a template problem. Azure sometimes closes a capacity-constrained region to
subscriptions that have never used it, and the error names each resource rather than the region,
which makes it look like five separate faults. The tell is that it is identical for five
unrelated resource types.

The same template validates cleanly in four other EU regions, so `deploy.sh` now defaults to
`germanywestcentral` — EU data residency, which matters for where this service is heading (SPEC
§7, GDPR Art. 9). Override with `LOCATION=northeurope ./deploy/deploy.sh ...` if you prefer.

### What has *not* been done

- No deployment. `validate` and `what-if` both create nothing — they ask Azure "would this
  work?" and "what would change?" and throw the answer away.
- Nothing pushed to a registry. The image exists **locally only** — there is no registry to push
  it to until `infra.bicep` has been applied, which is step 1 of `deploy.sh`.

---

## Part 2 — The Azure CLI from zero

### What `az` is

One command-line program that talks to the Azure REST API. Anything the Azure Portal can do,
`az` can do. The advantage over the Portal is that a command can be reviewed, repeated and
committed to a repository; a click cannot.

### Signing in

```bash
az login                      # opens a browser
az login --use-device-code    # when there is no browser (SSH, container)
```

This stores a token under `~/.azure`. It expires, so expect to repeat it occasionally.

```bash
az account show               # which subscription am I pointed at?
az account list -o table      # all of them
az account set -s "<name-or-id>"
```

### How Azure is organised

Four levels, each inside the previous:

```
Tenant            your Entra ID directory — the identity boundary ("who exists")
└── Subscription  the billing boundary ("who pays")
    └── Resource group   a folder, and the unit of deletion
        └── Resource     a registry, a VNet, a container app
```

Two consequences worth internalising early:

- **A resource group is the delete button.** `az group delete -n rg-docai-test` removes
  everything inside it. That is the cleanest way to undo an experiment, and the easiest way to
  destroy something you meant to keep.
- **A resource group has a location, but its resources do not have to match it.** Ours is in
  westeurope while the resources will be in germanywestcentral. That is legal and normal; the
  group's own location only says where its metadata lives.

### Resource providers

Azure's resource types are grouped into providers — `Microsoft.App` owns Container Apps,
`Microsoft.Network` owns VNets. A provider must be *registered* on your subscription before you
can create anything it owns. New subscriptions have almost none registered.

```bash
az provider show -n Microsoft.App --query registrationState -o tsv
az provider register -n Microsoft.App       # returns immediately; takes minutes
```

It is asynchronous: the command returns while the state is still `Registering`. Poll until it
reads `Registered`.

### The shape of a command

```
az <group> <subgroup...> <verb> --flags
   │                      │
   │                      └── create, delete, list, show, update
   └── account, group, acr, containerapp, deployment, ad, provider, bicep
```

```bash
az group create -n rg-docai-test -l germanywestcentral
az containerapp logs show -g rg-docai-test -n doc-ai --tail 50
```

`-g` is `--resource-group`, `-n` is `--name`, `-l` is `--location`. `az <anything> --help` works
at every level and is the fastest way to find a flag.

### Controlling the output

Default output is verbose JSON. Two flags make it usable:

```bash
az group list -o table                       # human-readable
az group list -o tsv                         # one value per line, for scripts
az group list --query "[].name" -o tsv       # just the names
az acr show -n myreg --query loginServer -o tsv
```

`--query` is [JMESPath](https://jmespath.org), a query language for JSON. `-o none` prints
nothing, which is what you want for a command run for its effect rather than its answer —
`deploy.sh` uses it throughout.

### Three ways to send a template to Azure

This distinction is the most useful thing to learn early, because two of the three are free and
harmless:

| Command | Creates anything? | Answers |
|---|---|---|
| `az deployment group validate` | No | "Would Azure accept this?" — syntax, permissions, quotas, region eligibility |
| `az deployment group what-if` | No | "What exactly would change?" — a diff of the resource graph |
| `az deployment group create` | **Yes** | Actually does it |

Run the first two freely. `what-if` in particular is the closest thing to a dry run and is how
the 6-resource list in Part 1 was produced.

### Deployments are idempotent

`az deployment group create` is declarative: it describes the desired end state, not steps. Run
it twice with the same template and the second run changes nothing. This is why `deploy.sh` can
re-apply the infrastructure on every deployment without checking whether it already exists.

---

## Part 3 — How this deployment works

### The pieces

```
  deploy/infra.bicep ─┐
  deploy/app.bicep  ──┼─► az bicep build ─► ARM JSON ─► Azure creates resources
                      │
  Java source ────────┴─► ./gradlew bootBuildImage ─► OCI image ─► docker push ─► ACR
                                                                                  │
                                            Container App pulls the image ◄────────┘
```

**Bicep** is a readable language that compiles to ARM JSON, Azure's native deployment format.
You write Bicep; Azure receives JSON. `az bicep build` does that translation locally, which is
why it catches errors before anything reaches Azure.

**Buildpacks** turn the Java source into a container image without a Dockerfile.
`./gradlew bootBuildImage` is configured in `build.gradle.kts` and uses Paketo buildpacks: they
detect a Spring Boot application, pick a JRE, and size the JVM heap for the container's memory
limit. This is why there is no Dockerfile in the repository and why a running Docker daemon is
required.

### What gets created, and why

| Resource | Purpose |
|---|---|
| **Log Analytics workspace** | Where the platform ships container stdout. `az containerapp logs show` reads from here |
| **Container registry (ACR)** | Stores the image. `adminUserEnabled: false` — no username/password exists at all |
| **User-assigned managed identity** | An identity for the app, with no password. Granted `AcrPull` so it can fetch its own image |
| **`AcrPull` role assignment** | The grant itself, scoped to this one registry |
| **Container Apps environment** | The boundary that holds apps: the log destination and the networking. Built with **no** `vnetConfiguration`, which is what lets an app in it carry a public FQDN |
| **Container app** | The service. Holds the image, env vars, secrets, probes and scaling rules |

A **managed identity** is worth understanding because it replaces a password. Azure vouches for
the app's identity directly, so nothing needs storing or rotating. The same identity is the right
thing to grant a Key Vault secret or an Azure-hosted model later.

### Why two templates

`infra.bicep` and `app.bicep` exist separately because of an ordering problem:

1. The container app cannot be created before its image exists.
2. The image cannot be pushed before the registry exists.

So the registry and the environment go in one template, the app in another, and `deploy.sh`
interleaves the image build:

```
infra.bicep ─► registry exists ─► build image ─► push ─► app.bicep ─► app runs
```

`app.bicep` can then be re-applied on its own for each new image, which is what a routine
deployment is.

### What `deploy.sh` does, step by step

```bash
./deploy/deploy.sh rg-docai-test
```

It needs `ANTHROPIC_API_KEY`, `DOCAI_JWT_ISSUER_URI` and `DOCAI_JWT_AUDIENCE`, and reads them from
`~/.config/docai/deploy.env` for whatever the environment does not already set — so there is
nothing to source by hand. An exported value always wins over the file, which is what lets CI
supply its own secrets; `DOCAI_ENV_FILE=/some/other/env` points it elsewhere.

1. **Checks its inputs.** Reads `~/.config/docai/deploy.env` for any of the three variables the
   environment does not set, then fails immediately if one is still missing, or if `az`, `jq` or
   Docker is unavailable, or if you are not logged in. It fails up front for the same reason the
   service itself refuses to start without an issuer: better than discovering it five minutes in.
   The key is kept in that file rather than in `~/.bashrc` on purpose — a shell rc would put it
   into the environment of every process you start, where a deployment needs it in one.
2. **Creates the resource group** if absent.
3. **Applies `infra.bicep`** and captures its outputs — registry name, login server, environment
   id, identity id.
4. **Builds the image** with buildpacks, tagged `<version>-<git-sha>`, plus `-dirty` if your
   working tree has uncommitted changes. Never `:latest`, so a running revision always names the
   build it came from.
5. **`az acr login`**, which hands Docker a short-lived Entra token, then **`docker push`**. This
   is why the registry needs no password.
6. **Applies `app.bicep`** with the image and the secrets, passed through a `0600` temporary file
   rather than on the command line — command-line arguments are visible to anyone who can run
   `ps`.
7. **Prints** the revision name, the public URL, and the commands to call it and to tail logs.

Re-running it is safe.

### What you get

Ingress is **public**, so the app has an `https://` URL that resolves from anywhere and you call
it with curl or Hoppscotch like any other API. See
[Calling the endpoints](#calling-the-endpoints) for exactly how, including how to get a token.

SPEC §13 Q1 asked whether Entra authentication was wanted from the start or whether network
isolation would do for v1. For a solo learning project the answer is authentication alone:
network isolation would only lock out the one person who needs in. The token check is not
weakened by this — it was never the second barrier here, it is the only one.

Two things are reachable without a token, both on purpose: `/actuator/health`, because the
platform's probes have none, and nothing else. `/v3/api-docs` stays behind a token, and the
Swagger UI is off entirely under `prod`.

When something does not come up, the platform has the reason and curl does not:

```bash
az containerapp revision show -g rg-docai-test -n doc-ai --revision <name> \
    --query '{running:properties.runningState, healthy:properties.healthState}'
az containerapp logs show -g rg-docai-test -n doc-ai --tail 50
```

Expect **two** WARNs at startup, both expected:

- `HostedModelWarning` — profiles `prod,anthropic` mean page images go to the Anthropic API,
  which SPEC §7 permits for synthetic documents only.
- `SpringDocAppInitializer` — `/v3/api-docs` is enabled. Under `prod`, `application.yml` turns the
  Swagger *UI* off and deliberately keeps the schema, which `SecurityConfig` leaves behind a token
  via `anyRequest().authenticated()` (SPEC §10). Nothing to fix.

Neither is worth acting on, and neither indicates a misconfiguration.

---

## Nothing is outstanding

Every prerequisite is cleared. For the record, since each of them cost something to find:

| Was needed | How it stands |
|---|---|
| Entra app registrations | `doc-ai-api` with the `DocAi.Process` role, `docai-test-client` holding it by an admin-consented grant. See [The Entra app registrations](#the-entra-app-registrations) |
| A usable Docker daemon | Two unrelated faults, both fixed. See [Docker, and why the usual advice is wrong](#docker-and-why-the-usual-advice-is-wrong) |
| `ANTHROPIC_API_KEY` | In `~/.config/docai/deploy.env`, which `deploy.sh` reads by itself |
| A way to reach the service | Public ingress, verified with curl. See [Calling the endpoints](#calling-the-endpoints) |

The only one you have to think about again is the key, and only if it is rotated. Note that the
[local smoke test](#smoke-testing-the-image-locally) does **not** need the real key — a dummy one
starts the application and exercises every HTTP path, because the model is only called when a
document is actually processed.

---

## Docker, and why the usual advice is wrong

Done, but worth keeping, because this trips people up. The buildpack build runs locally, so it
needs a Docker daemon this user can talk to. There were **two** separate faults, which look like
one:

1. The CLI context was `desktop-linux`, pointing at a Docker Desktop socket that was not running.
   Fixed with `docker context use default`, which points the CLI at `/var/run/docker.sock` — the
   *system* daemon, already `active` and `enabled`. Docker Desktop is not needed at all here.
2. `/var/run/docker.sock` is `root:docker` mode `660` and the user was not in the `docker` group,
   so it still failed with `permission denied`. Fixed with `sudo usermod -aG docker "$USER"`.

Membership of `docker` is effectively root-equivalent — anyone in it can start a container that
mounts the host filesystem — so step 2 is a privilege grant, not a permissions tweak. On a
single-user machine that is the usual trade-off. `sudo docker` avoids it, but Gradle talks to the
daemon from inside `bootBuildImage`, which makes running the build under `sudo` awkward.

**No logout is needed afterwards**, which is the part the usual advice gets wrong. Group
membership is fixed when a process starts, so shells that are already open never see it — but
`/usr/bin/sg` reads `/etc/group` live, so a single command can run with the new group at once:

```bash
sg docker -c 'docker info'
sg docker -c './deploy/deploy.sh rg-docai-test'
```

`newgrp docker` does the same for an interactive shell; new logins get the group automatically, so
`sg` stops being necessary once you have logged out and back in. Note that a new terminal *tab* is
usually not enough: it inherits its groups from the desktop session, which started before the
change.

### A third fault, which only shows up minutes in

Both of the above fail immediately. This one waits until the image has been built, which makes it
far more annoying, and `deploy.sh` now defends against it.

**More than one daemon can be running.** This machine has a system `dockerd` on
`/var/run/docker.sock` *and* Docker Desktop on its own socket. `bootBuildImage` and `docker push`
resolve the daemon independently, so they can pick different ones — and then the push fails with

```
An image does not exist locally with the tag: <registry>/doc-ai
```

for an image the build just reported as successful. Worse, starting or stopping Docker Desktop
rewrites the current context, so `docker context use default` does not stay put: it was reverted
mid-session here when Docker Desktop came up. `deploy.sh` resolves the current context once and
exports `DOCKER_HOST`, so the build and the push cannot disagree.

**And the credential helper can be unusable.** Docker Desktop sets `"credsStore": "desktop"`, whose
helper delegates to `pass`, which needs `gpg`, which needs a passphrase prompt. A non-interactive
deployment cannot answer one, so the push fails with

```
error getting credentials - ... gpg: decryption failed: No such file or directory
```

*after* `az acr login` has reported `Login Succeeded`, which is a confusing pair of messages. It
works intermittently, because gpg-agent caches the passphrase for a while after any interactive
use — so it can succeed once and fail an hour later with nothing changed. `deploy.sh` now points
`DOCKER_CONFIG` at a private `0600` directory with no helper configured, holding only the
short-lived ACR token.

---

## The Entra app registrations

Done, on 2026-10-01. The service is an OAuth2 resource server (SPEC §7), so it needs an audience
to accept tokens for and an app role for callers to hold. Two registrations exist:

| | |
|---|---|
| `doc-ai-api` | The API itself. Its application ID is `DOCAI_JWT_AUDIENCE`. Declares the `DocAi.Process` app role |
| `docai-test-client` | The caller — in practice you, from curl or Hoppscotch. Holds `DocAi.Process` by an admin-consented grant, and has one client secret |

The IDs are not in this repository — they identify a tenant and are nobody else's business, even
though they are not secrets. They are in `~/.config/docai/deploy.env` (mode `600`), which
`deploy.sh` expects you to source. A test client secret is in `~/.config/docai/client-test.env`.

### How it was built

```bash
API_APP_ID=$(az ad app create --display-name doc-ai-api \
    --sign-in-audience AzureADMyOrg --query appId -o tsv)
API_OBJ_ID=$(az ad app show --id "$API_APP_ID" --query id -o tsv)
ROLE_ID=$(cat /proc/sys/kernel/random/uuid)
```

Then one Graph PATCH on the API registration sets the identifier URI, the app role, **and the
access token version**:

```bash
az rest --method PATCH --url "https://graph.microsoft.com/v1.0/applications/$API_OBJ_ID" \
  --headers Content-Type=application/json --body '{
    "identifierUris": ["api://'"$API_APP_ID"'"],
    "api": { "requestedAccessTokenVersion": 2 },
    "appRoles": [{
      "id": "'"$ROLE_ID"'", "allowedMemberTypes": ["Application"],
      "description": "Process documents via doc-ai", "displayName": "DocAi.Process",
      "isEnabled": true, "value": "DocAi.Process"
    }]
  }'
```

`requestedAccessTokenVersion: 2` is the part worth stopping on. A new registration leaves it
`null`, which means **v1** tokens for a custom API — and a v1 token is issued by
`https://sts.windows.net/<tenant>/` with `aud` set to `api://<app-id>`. Neither matches the
`/v2.0` issuer and GUID audience configured below, so every call would 401 with the decoder's
"Cannot build the JWT decoder" error and nothing else to go on. Setting it to `2` is what makes
the documented configuration true.

Then the caller, and the grant that is what "admin consent" actually means:

```bash
API_SP_ID=$(az ad sp create --id "$API_APP_ID" --query id -o tsv)
CLIENT_APP_ID=$(az ad app create --display-name docai-test-client \
    --sign-in-audience AzureADMyOrg --query appId -o tsv)
CLIENT_SP_ID=$(az ad sp create --id "$CLIENT_APP_ID" --query id -o tsv)

# Admin consent, as an API call rather than a Portal click.
az rest --method POST \
  --url "https://graph.microsoft.com/v1.0/servicePrincipals/$API_SP_ID/appRoleAssignedTo" \
  --headers Content-Type=application/json \
  --body '{"principalId":"'"$CLIENT_SP_ID"'","resourceId":"'"$API_SP_ID"'","appRoleId":"'"$ROLE_ID"'"}'
```

Granting an *application* role needs a directory admin. The Portal path is Entra ID → App
registrations → the client → API permissions → Grant admin consent; the call above is the same
thing, and it is the step that is easy to forget because the permission shows up on the client
either way — unconsented, it simply never appears in a token.

### Confirming it works before deploying anything

The useful check is not reading the registration back, it is minting a real token and looking at
the claims the service will actually validate:

```bash
. ~/.config/docai/client-test.env
TOKEN=$(curl -s -X POST \
  "https://login.microsoftonline.com/$DOCAI_TEST_TENANT/oauth2/v2.0/token" \
  -d grant_type=client_credentials -d client_id="$DOCAI_TEST_CLIENT_ID" \
  -d client_secret="$DOCAI_TEST_CLIENT_SECRET" \
  -d scope="api://$DOCAI_TEST_API_ID/.default" | jq -r .access_token)

echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d | jq '{iss,aud,roles,ver}'
```

What it printed, and what each line has to match:

| Claim | Must equal |
|---|---|
| `iss` | `DOCAI_JWT_ISSUER_URI` — `https://login.microsoftonline.com/<tenant-GUID>/v2.0` |
| `aud` | `DOCAI_JWT_AUDIENCE` — the `doc-ai-api` application ID, a bare GUID |
| `roles` | `["DocAi.Process"]`, which `AppRoleAuthoritiesConverter` reads from the `roles` claim |
| `ver` | `2.0`, confirming the token version above |

For Entra v2.0 the issuer uses the tenant **GUID**, not the domain. `az account show --query
tenantId -o tsv` gives it.

### Undoing it

`az group delete` does not touch these — they are directory objects, not subscription resources:

```bash
az ad app delete --id <api-app-id>
az ad app delete --id <client-app-id>
```

---

## Calling the endpoints

Every command here was run against the live deployment and the output is what it actually
returned. Replace the host with the one `deploy.sh` printed, or get it from Azure:

```bash
URL="https://$(az containerapp show -g rg-docai-test -n doc-ai \
      --query properties.configuration.ingress.fqdn -o tsv)"
```

### Health, which needs no token

```bash
curl -s $URL/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
```

This one is deliberately public: the platform's own liveness and readiness probes carry no token,
so `SecurityConfig` permits the health endpoint by endpoint rather than by path.

### Getting a token

`/api/**` needs a client-credentials token carrying the `DocAi.Process` app role. Without one, or
with a malformed one, every call is `401`:

```bash
. ~/.config/docai/client-test.env
TOKEN=$(curl -s -X POST \
  "https://login.microsoftonline.com/$DOCAI_TEST_TENANT/oauth2/v2.0/token" \
  -d grant_type=client_credentials -d client_id="$DOCAI_TEST_CLIENT_ID" \
  -d client_secret="$DOCAI_TEST_CLIENT_SECRET" \
  -d scope="api://$DOCAI_TEST_API_ID/.default" | jq -r .access_token)
```

The token lasts about an hour. Mint a new one when calls start coming back `401` after having
worked — that is the usual cause, not a broken deployment.

### The four endpoints

**The file part is named `file`.** Sending it under any other name gives a `400` with
`missing-file`, which reads like the service is broken when it is only a typo.

```bash
cd src/test/resources/fixtures

# 1 - classification
curl -s -H "Authorization: Bearer $TOKEN" -F file=@krankenstand.pdf \
     $URL/api/v1/klassifikation

# 2 - Krankenstand, with the optional Teilnehmer hints
curl -s -H "Authorization: Bearer $TOKEN" -F file=@krankenstand.pdf \
     -F vorname=Max -F familienname=Mustermann -F "svnr=1238 010190" \
     $URL/api/v1/extraktion/krankenstand

# 3 - Zeitbestaetigung
curl -s -H "Authorization: Bearer $TOKEN" -F file=@zeitbestaetigung.pdf \
     $URL/api/v1/extraktion/zeitbestaetigung

# 4 - Kompetenzprofil
curl -s -H "Authorization: Bearer $TOKEN" -F file=@kompetenzprofil.pdf \
     $URL/api/v1/extraktion/kompetenzprofil
```

Classification of `krankenstand.pdf` came back in 4.8s on a cold replica and about 2s after:

```json
{"typ":"KRANKENSTANDSBESTAETIGUNG",
 "begruendung":"Das Formular einer Ärztin bestätigt Arbeitsunfähigkeit über einen Zeitraum von-bis.",
 "manuellePruefung":false,
 "metadaten":{"provider":"anthropic","modell":"claude-sonnet-5","seiten":1,
              "inputTokens":3978,"outputTokens":86,"dauerMs":3193}}
```

`unbekannt.pdf` is an office-supplies invoice, and the useful thing is that it is **not** forced
into a category — `"typ":"UNBEKANNT"` with `"manuellePruefung":true`.

Pass a hint that disagrees with the document to watch the comparison rules fire. With
`-F familienname=Mustermeier` against a document that says Mustermann:

```json
{"probleme":[{"feld":"familienname","code":"NAME_WEICHT_AB","schweregrad":"WARNUNG",
              "meldung":"Name weicht vom uebergebenen Wert ab."}],
 "manuellePruefung":true}
```

Note what the extraction does **not** contain: no diagnosis, no medical detail, nothing beyond the
fields SPEC asks for, even though the document shows more. The SVNR also comes back normalised
(`1238 010190` in, `1238010190` out).

### From Hoppscotch

Everything above works in [hoppscotch.io](https://hoppscotch.io) too, with one wrinkle worth
knowing before it wastes an evening.

1. Set the method to **POST** and the URL to `<URL>/api/v1/klassifikation`.
2. Under **Authorization**, choose **Bearer** and paste the token from above.
3. Under **Body**, choose **multipart/form-data**, add a field named exactly `file`, switch it to
   the file type, and pick a fixture.

**The wrinkle:** Hoppscotch runs in your browser, so the request is cross-origin, and this service
sends no CORS headers — it was never meant for a browser. A browser will not show you a `401` or a
`200` from a request it is not allowed to read; it reports a generic network or CORS failure
instead, which looks identical to the service being down. Two ways round it, both official:

- Install the **Hoppscotch browser extension**, which proxies the request outside the page's
  origin, and add your `https://doc-ai.*.azurecontainerapps.io` host to its allowed list.
- Or use the **Hoppscotch desktop app**, which is not a browser page and is not subject to CORS at
  all.

If a call fails in Hoppscotch but the same call works in curl, it is this, not the deployment.
`curl` is the quickest way to tell the two apart, which is why it is worth trying first.

---

## Smoke-testing the image locally

Worth doing before a deployment rather than after it: the same image, the same profiles and the
same live Entra tenant, with a round trip measured in seconds instead of minutes. It is the
fastest way to find out that a token or a profile is wrong. A **dummy** API key is enough, because
the model is only called when a document is actually processed, so the context starts and every
HTTP path works without the real key.

```bash
. ~/.config/docai/deploy.env     # for the issuer and audience; the key here can be a dummy
# (sourced by hand only because this is a plain docker run, not deploy.sh, which reads it itself)
docker run -d --name docai-smoke -p 18080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod,anthropic \
  -e DOCAI_JWT_ISSUER_URI="$DOCAI_JWT_ISSUER_URI" \
  -e DOCAI_JWT_AUDIENCE="$DOCAI_JWT_AUDIENCE" \
  -e ANTHROPIC_API_KEY=sk-ant-dummy-key-never-called \
  docai/doc-ai:0.0.1-SNAPSHOT

curl -s localhost:18080/actuator/health          # {"status":"UP"} in about 5s, no token
curl -so /dev/null -w '%{http_code}\n' -X POST localhost:18080/api/v1/klassifikation
```

Then with a real token, minted as in [the Entra section](#confirming-it-works-before-deploying-anything):

```bash
curl -so /dev/null -w '%{http_code}\n' -X POST \
  -H "Authorization: Bearer $TOKEN" localhost:18080/api/v1/klassifikation
docker rm -f docai-smoke
```

What it printed, and why each line is the result you want:

| Request | Code | What it proves |
|---|---|---|
| `GET /actuator/health`, no token | `200` | The probes are `permitAll`, so the platform's liveness and readiness checks will not 401 |
| `POST /api/v1/klassifikation`, no token | `401` | `/api/**` is closed |
| the same with `Bearer not.a.jwt` | `401` | A malformed token is rejected rather than ignored |
| the same with a real Entra token | `415` | **The whole chain works.** The token was fetched from Entra, its JWKS verified, its issuer and audience matched, its `roles` claim became the `DocAi.Process` authority, and the request reached the controller — which then correctly refused a request carrying no document |

A `415` is the success case here, and a more informative one than a `200` would be: anything
wrong with the token or the role would have stopped at `401` or `403` long before the controller
saw the request. The body is a proper RFC 7807 `ProblemDetail` with a `requestId` and no document
content in it, which is also what SPEC §7's logging rule asks for.

---

## Cost, and how to undo it

Three of the four resources bill:

- **ACR Basic** — a small fixed daily charge for the registry.
- **Container Apps** — per vCPU-second and GiB-second. There is a monthly free grant, but
  `minReplicas: 1` means one replica runs continuously by design (a JVM cold start would
  otherwise land on a caller waiting synchronously), so expect steady usage rather than none.
- **Log Analytics** — per GB ingested, with a free allowance.

Check current prices on the Azure pricing calculator rather than trusting a number written here.

To remove everything:

```bash
az group delete -n rg-docai-test --yes --no-wait
```

That deletes all four resources and the images in the registry, and cannot be undone. The
provider registrations stay, which is harmless and saves repeating the wait. The Entra app
registrations also stay — they are directory objects rather than resources in this group, so they
need `az ad app delete` (see [Undoing it](#undoing-it)).

---

## Order to do things in

All of it is done. Kept as the order to repeat it in, on a fresh subscription or after a teardown:

```
[x] 1. Create the Entra app registrations and the DocAi.Process role, grant admin consent
[x] 2. Fix Docker: join the `docker` group, and know which daemon you are talking to
[x] 3. Add ANTHROPIC_API_KEY to ~/.config/docai/deploy.env
[x] 4. Build the image and smoke-test it locally against live Entra
[x] 5. ./deploy/deploy.sh rg-docai-test   (prefix with `sg docker -c` until you re-login)
[x] 6. Check the revision is Running and Healthy, and read the logs
[x] 7. Call the endpoints from curl or Hoppscotch
[ ] 8. az group delete when you are done, because step 5 is what starts the bill
```

Steps 1 to 4 are free and reversible. Step 5 is the first one that creates anything billable, and
step 8 is the undo.

## What it is costing right now

Three resources bill: the registry, one always-on replica, and log ingest. It is a small amount
per day rather than per month, but it is continuous, because `minReplicas: 1` is deliberate — a
scale-to-zero cold start would land a JVM boot in front of a waiting caller. If you are not using
it for a while, `az group delete -n rg-docai-test --yes --no-wait` costs nothing to undo later:
re-running `deploy.sh` rebuilds all of it, and the Entra registrations survive a group delete
because they are directory objects.
