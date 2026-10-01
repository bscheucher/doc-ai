// Everything that outlives a single image: registry, Container Apps environment, the identity
// that pulls from the one into the other. Split from app.bicep because the app cannot be
// created before its image exists, and the image cannot be pushed before the registry does.
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

@description('''
Existing subnet for the Container Apps infrastructure. Leave empty to have this template create
a VNet and subnet. Supply one when ibosNG already lives in a VNet: internal ingress is only
reachable from inside the VNet (or something peered with it), so putting doc-ai in the caller's
network is the point of choosing internal.

The subnet must be at least /27 and delegated to Microsoft.App/environments.
''')
param infrastructureSubnetId string = ''

@description('Address space for the VNet created when infrastructureSubnetId is empty.')
param vnetAddressPrefix string = '10.40.0.0/23'

@description('Address range for the created infrastructure subnet. Must be /27 or larger.')
param infraSubnetPrefix string = '10.40.0.0/27'

@description('Retention for the workspace the platform writes container logs to.')
@minValue(30)
@maxValue(730)
param logRetentionInDays int = 30

var suffix = '${namePrefix}-${environmentName}'
// Registry names are globally unique and allow no hyphens.
var computedRegistryName = toLower('${namePrefix}${environmentName}${uniqueString(resourceGroup().id)}')
var createNetwork = empty(infrastructureSubnetId)
var createdVnetName = 'vnet-${suffix}'
var subnetName = 'snet-aca-infra'
// Built with resourceId() rather than read off the vnet resource: ARM does not reliably
// short-circuit a ternary, so `createNetwork ? vnet.properties... : param` can still try to
// resolve a vnet that was never deployed. resourceId() is string arithmetic and resolves either
// way; the environment declares its dependency on the vnet explicitly instead.
var effectiveSubnetId = createNetwork
  ? resourceId('Microsoft.Network/virtualNetworks/subnets', createdVnetName, subnetName)
  : infrastructureSubnetId

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: 'log-${suffix}'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: logRetentionInDays
  }
}

resource vnet 'Microsoft.Network/virtualNetworks@2024-05-01' = if (createNetwork) {
  name: createdVnetName
  location: location
  properties: {
    addressSpace: { addressPrefixes: [ vnetAddressPrefix ] }
    subnets: [
      {
        name: subnetName
        properties: {
          addressPrefix: infraSubnetPrefix
          // Required for a workload-profiles environment; without it the environment is
          // rejected with a delegation error that does not name this subnet.
          delegations: [
            {
              name: 'Microsoft.App.environments'
              properties: { serviceName: 'Microsoft.App/environments' }
            }
          ]
        }
      }
    ]
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
    vnetConfiguration: {
      // The decision behind this file: no public FQDN. Reachable only from inside the VNet.
      internal: true
      infrastructureSubnetId: effectiveSubnetId
    }
    workloadProfiles: [
      { name: 'Consumption', workloadProfileType: 'Consumption' }
    ]
    zoneRedundant: false
  }
  dependsOn: createNetwork ? [ vnet ] : []
}

output environmentId string = environment.id
output registryName string = registry.name
output registryLoginServer string = registry.properties.loginServer
output identityId string = identity.id
output vnetName string = createNetwork ? createdVnetName : ''
output acaInfrastructureSubnetId string = effectiveSubnetId
