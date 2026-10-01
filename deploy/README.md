# Deploying doc-ai to Azure Container Apps

Written for someone who has not used the Azure CLI before. Three parts: what has actually been
done so far, how the CLI works, and how this deployment works end to end.

The short version: the templates in this directory are finished and verified against live
Azure, but **nothing has been deployed yet** and three things are missing before it can be. They
are listed in [What is still missing](#what-is-still-missing).

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

Only two things, both free:

1. **A resource group**, `rg-docai-test` in westeurope. Empty. A resource group is just a folder;
   an empty one costs nothing.
2. **Five resource providers registered** on the subscription: `Microsoft.App`,
   `Microsoft.ContainerRegistry`, `Microsoft.OperationalInsights`, `Microsoft.ManagedIdentity`,
   `Microsoft.Network`. Registration is free, one-off per subscription, and a prerequisite for
   creating any resource of those kinds.

**No billable resource exists.** No registry, no environment, no container app, no image.

### What was verified

| Check | Result |
|---|---|
| `az bicep build` on both templates | exit 0 |
| `az bicep lint` on both templates | exit 0, no findings |
| Parameters file vs. template parameters | nothing undefined, nothing required missing |
| `az deployment group validate` (infra) | passes in germanywestcentral, northeurope, swedencentral, francecentral |
| `az deployment group what-if` (infra) | **6 resources to create**, no errors |

The six: the VNet with its `/27` delegated subnet, the Log Analytics workspace, the container
registry with `adminUserEnabled: false`, the user-assigned identity, the `AcrPull` role
assignment, and the Container Apps environment with `internal: true`.

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
- No image built. The Docker daemon on this machine is not reachable.
- No Entra app registration, so the service has no identity for callers to get a token for.

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
| **Virtual network + subnet** | The network the service lives in. The subnet must be `/27` or larger and delegated to `Microsoft.App/environments` |
| **Container registry (ACR)** | Stores the image. `adminUserEnabled: false` — no username/password exists at all |
| **User-assigned managed identity** | An identity for the app, with no password. Granted `AcrPull` so it can fetch its own image |
| **`AcrPull` role assignment** | The grant itself, scoped to this one registry |
| **Container Apps environment** | The boundary that holds apps: the VNet attachment, the log destination, `internal: true` |
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
ANTHROPIC_API_KEY=... DOCAI_JWT_ISSUER_URI=... DOCAI_JWT_AUDIENCE=... \
  ./deploy/deploy.sh rg-docai-test
```

1. **Checks its inputs.** Fails immediately if any of the three variables is missing, or if `az`,
   `jq` or Docker is unavailable, or if you are not logged in. It fails up front for the same
   reason the service itself refuses to start without an issuer: better than discovering it five
   minutes in.
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
7. **Prints** the revision name, the internal URL, and the commands to check health and tail logs.

Re-running it is safe.

### What you get, and what you cannot reach

Ingress is **internal**: the app has a URL, but it resolves only from inside the VNet. From your
laptop it does not resolve at all. That is deliberate — see SPEC §13 Q1 — and it means
"deployed successfully" has to be confirmed by asking the platform rather than by curling it:

```bash
az containerapp revision show -g rg-docai-test -n doc-ai --revision <name> \
    --query '{running:properties.runningState, healthy:properties.healthState}'
az containerapp logs show -g rg-docai-test -n doc-ai --tail 50
```

Expect exactly one WARN at startup, from `HostedModelWarning`: profiles `prod,anthropic` mean
page images go to the Anthropic API, which SPEC §7 permits for synthetic documents only.

To let ibosNG reach it, set `infrastructureSubnetId` in `deploy/infra.parameters.json` to a
subnet in ibosNG's own VNet, or peer the two networks. Left at its default the template builds
its own VNet, which nothing else can reach.

---

## What is still missing

Three things, none of them in this repository.

### 1. A reachable Docker daemon

The buildpack build runs locally. On this machine `docker info` fails: the context is
`desktop-linux`, so it expects Docker Desktop to be running, and the user is not in the `docker`
group either. Start Docker Desktop, or for the system daemon:

```bash
sudo usermod -aG docker "$USER"    # then log out and back in
docker info                        # must succeed
```

### 2. `ANTHROPIC_API_KEY`

The same key the `llm`-tagged tests use.

### 3. An Entra app registration

The service is an OAuth2 resource server (SPEC §7). It needs an app registration so that it has
an audience to accept tokens for, and an app role for callers to hold. This does not exist yet,
which is why `DOCAI_JWT_ISSUER_URI` and `DOCAI_JWT_AUDIENCE` have no values.

Review before running — this modifies your Entra tenant, not just a resource group:

```bash
# The API's own registration
az ad app create --display-name doc-ai-api --query appId -o tsv
# -> <api-app-id>.  This is DOCAI_JWT_AUDIENCE.

# The DocAi.Process app role that SecurityConfig requires on every /api/** call
cat > /tmp/docai-role.json <<'JSON'
[{
  "allowedMemberTypes": ["Application"],
  "description": "Process documents via doc-ai",
  "displayName": "DocAi.Process",
  "isEnabled": true,
  "value": "DocAi.Process"
}]
JSON
az ad app update --id <api-app-id> --app-roles @/tmp/docai-role.json

# A registration for the caller (ibosNG), and the grant of that role to it
az ad app create --display-name ibosng-docai-client --query appId -o tsv
az ad sp create --id <client-app-id>
```

Granting an *application* role needs admin consent, which is a Portal step (Entra ID → App
registrations → the client → API permissions → Grant admin consent) or an `az rest` call against
Microsoft Graph.

Then:

```bash
export DOCAI_JWT_ISSUER_URI="https://login.microsoftonline.com/<tenant-guid>/v2.0"
export DOCAI_JWT_AUDIENCE="<api-app-id>"
```

`az account show --query tenantId -o tsv` gives the tenant GUID. For Entra v2.0 tokens the
issuer uses the **GUID**, not the domain — `SecurityConfig` logs an explicit error about this,
because getting it wrong 401s every call and otherwise says nothing.

---

## Cost, and how to undo it

Nothing billable exists yet. Once deployed, three of the six resources bill:

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

That deletes all six resources and the images in the registry, and cannot be undone. The provider
registrations stay, which is harmless and saves repeating the wait.

---

## Order to do things in

```
[ ] 1. Start Docker, confirm `docker info` succeeds
[ ] 2. Create the Entra app registrations and the DocAi.Process role, grant admin consent
[ ] 3. export ANTHROPIC_API_KEY, DOCAI_JWT_ISSUER_URI, DOCAI_JWT_AUDIENCE
[ ] 4. ./deploy/deploy.sh rg-docai-test
[ ] 5. Check the revision is running and read the logs
[ ] 6. Decide on the VNet: peer with ibosNG, or set infrastructureSubnetId
[ ] 7. az group delete when the experiment is over
```

Steps 1–3 need decisions or credentials that are yours. Step 4 onwards is mechanical.
