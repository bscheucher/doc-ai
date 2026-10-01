#!/usr/bin/env bash
#
# Builds the image and deploys doc-ai to Azure Container Apps.
#
#   ANTHROPIC_API_KEY=... DOCAI_JWT_ISSUER_URI=... DOCAI_JWT_AUDIENCE=... \
#     ./deploy/deploy.sh rg-docai-test
#
# Idempotent: applies infra.bicep, pushes a new image, applies app.bicep. Re-running it with
# no code change produces a no-op infrastructure deployment and a new revision of the same app.

set -euo pipefail

RESOURCE_GROUP="${1:-}"
LOCATION="${LOCATION:-westeurope}"
APP_NAME="${APP_NAME:-doc-ai}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

die() { printf '%s\n' "$*" >&2; exit 1; }

[[ -n "$RESOURCE_GROUP" ]] || die "usage: $0 <resource-group>    (region via LOCATION, default $LOCATION)"

# The app refuses to start without these rather than accepting every token in the tenant
# (SPEC §7). Fail here for the same reason, instead of after a five-minute deployment.
for var in ANTHROPIC_API_KEY DOCAI_JWT_ISSUER_URI DOCAI_JWT_AUDIENCE; do
    [[ -n "${!var:-}" ]] || die "$var is not set. Needed by the deployed app; see README 'Configure'."
done

command -v az >/dev/null     || die "az CLI not found: https://learn.microsoft.com/cli/azure/install-azure-cli"
command -v jq >/dev/null     || die "jq not found."
az account show >/dev/null 2>&1 || die "Not logged in. Run: az login"
docker info >/dev/null 2>&1  || die "No reachable Docker daemon. bootBuildImage builds the image locally."

version="$(./gradlew -q properties --property version 2>/dev/null | awk '/^version:/ {print $2}')"
# Tagged by commit, never :latest - a running revision has to be traceable back to a build.
git_sha="$(git rev-parse --short HEAD)"
dirty=""
git diff --quiet HEAD -- || dirty="-dirty"
TAG="${TAG:-${version}-${git_sha}${dirty}}"

echo "==> doc-ai $TAG -> resource group $RESOURCE_GROUP ($LOCATION)"
[[ -n "$dirty" ]] && echo "    working tree has uncommitted changes; tag marked -dirty"

az group create -n "$RESOURCE_GROUP" -l "$LOCATION" -o none

echo "==> infrastructure (registry, Container Apps environment, identity)"
infra_outputs="$(az deployment group create \
    --resource-group "$RESOURCE_GROUP" \
    --name "docai-infra-$(date +%Y%m%d%H%M%S)" \
    --template-file deploy/infra.bicep \
    --parameters @deploy/infra.parameters.json \
    --parameters location="$LOCATION" \
    --query properties.outputs -o json)"

registry_name="$(jq -r .registryName.value <<<"$infra_outputs")"
registry_server="$(jq -r .registryLoginServer.value <<<"$infra_outputs")"
environment_id="$(jq -r .environmentId.value <<<"$infra_outputs")"
identity_id="$(jq -r .identityId.value <<<"$infra_outputs")"

image="${registry_server}/doc-ai:${TAG}"

echo "==> image $image"
# Buildpacks rather than a Dockerfile (build.gradle.kts). Pushed with docker, not
# --publishImage: `az acr login` gives Docker an Entra token, so the registry needs no
# admin user and no password reaches Gradle.
./gradlew bootBuildImage -PimageName="$image"
az acr login -n "$registry_name" -o none
docker push "$image"

echo "==> container app"
# Via a parameters file, not -p key=value: command-line arguments are visible in `ps`.
params_file="$(mktemp)"
chmod 600 "$params_file"
trap 'rm -f "$params_file"' EXIT
jq -n \
    --arg image "$image" \
    --arg environmentId "$environment_id" \
    --arg identityId "$identity_id" \
    --arg registryLoginServer "$registry_server" \
    --arg appName "$APP_NAME" \
    --arg location "$LOCATION" \
    --arg jwtIssuerUri "$DOCAI_JWT_ISSUER_URI" \
    --arg jwtAudience "$DOCAI_JWT_AUDIENCE" \
    --arg anthropicApiKey "$ANTHROPIC_API_KEY" \
    '{
       "$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentParameters.json#",
       contentVersion: "1.0.0.0",
       parameters: ( [ $ARGS.named | to_entries[] | { key: .key, value: { value: .value } } ] | from_entries )
     }' > "$params_file"

app_outputs="$(az deployment group create \
    --resource-group "$RESOURCE_GROUP" \
    --name "docai-app-$(date +%Y%m%d%H%M%S)" \
    --template-file deploy/app.bicep \
    --parameters "@$params_file" \
    --query properties.outputs -o json)"

fqdn="$(jq -r .internalFqdn.value <<<"$app_outputs")"
revision="$(jq -r .latestRevisionName.value <<<"$app_outputs")"

cat <<EOF

==> deployed
    revision   $revision
    internal   https://$fqdn
    health     https://$fqdn/actuator/health   (from inside the VNet only)

Ingress is internal: that FQDN does not resolve from here. To check the revision came up:

    az containerapp revision show -g $RESOURCE_GROUP -n $APP_NAME --revision $revision \\
        --query '{running:properties.runningState,healthy:properties.healthState,replicas:properties.replicas}'
    az containerapp logs show -g $RESOURCE_GROUP -n $APP_NAME --revision $revision --tail 50

Expect one WARN at startup from HostedModelWarning: profile 'anthropic' with 'prod' means page
images go to the Anthropic API, which SPEC §7 allows for synthetic documents only.
EOF
