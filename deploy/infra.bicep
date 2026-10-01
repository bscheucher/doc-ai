// Everything that outlives a single image: registry, Container Apps environment, the identity
// that pulls from the one into the other. Split from app.bicep because the app cannot be
// created before its image exists, and the image cannot be pushed before the registry does.
//
// This is a private learning project with one operator and no other caller, so the environment
// uses Azure-managed networking and the app is reachable over the public internet - that is the
// point: the endpoints have to be callable from curl and a browser API client. There is no VNet
// and no subnet. Authentication is what protects the API: every /api/** call needs an Entra
// token carrying DocAi.Process, and only /actuator/health is open (the platform's probes have no
// token). Git history has the earlier VNet-injected, internal-only variant if it is ever wanted.
//
//   az deployment group create -g <rg> -f deploy/infra.bicep -p @deploy/infra.parameters.json
//
// Safe to re-apply; deploy.sh runs it on every deployment.

targetScope = 'resourceGroup'

@description('Azure region for every resource.')
param location string = resourceGroup().location

@description('Prefix for resource names. Lower-case letters and digits.')
@minLength(3)
@maxLength(11)
param namePrefix string = 'docai'

@description('Short environment name, part of every resource name.')
@allowed([ 'test', 'stage', 'prod' ])
param environmentName string = 'test'

@description('Retention for the workspace the platform writes container logs to.')
@minValue(30)
@maxValue(730)
param logRetentionInDays int = 30

var suffix = '${namePrefix}-${environmentName}'
// Registry names are globally unique and allow no hyphens.
var computedRegistryName = toLower('${namePrefix}${environmentName}${uniqueString(resourceGroup().id)}')

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: 'log-${suffix}'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: logRetentionInDays
  }
}

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: computedRegistryName
  location: location
  sku: { name: 'Basic' }
  properties: {
    // No admin user: the app pulls with the managed identity below, and `az acr login`
    // gives the local Docker an Entra token for the push. Nothing needs a registry password.
    adminUserEnabled: false
  }
}

// The app's identity. Used for the registry pull, and the thing to grant any future Azure
// resource access to (Key Vault for the API key, an Azure-hosted model) instead of a secret.
resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${suffix}'
  location: location
}

var acrPullRoleId = '7f951dda-4ed3-4680-a7ca-43fe172d538d'

resource acrPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: registry
  name: guid(registry.id, identity.id, acrPullRoleId)
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPullRoleId)
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource environment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: 'cae-${suffix}'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
    // No vnetConfiguration: Azure manages the networking and the environment can carry a
    // public FQDN. An environment's networking is fixed at creation - there is no switching
    // between internal and external later, only rebuilding - so this is the one decision here
    // that cannot be changed in place.
    workloadProfiles: [
      { name: 'Consumption', workloadProfileType: 'Consumption' }
    ]
    zoneRedundant: false
  }
}

output environmentId string = environment.id
output registryName string = registry.name
output registryLoginServer string = registry.properties.loginServer
output identityId string = identity.id
