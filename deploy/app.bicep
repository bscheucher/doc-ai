// The container app itself. Takes the image and the infra.bicep outputs, so it can be
// re-applied on its own for every new image tag.
//
//   az deployment group create -g <rg> -f deploy/app.bicep -p image=... environmentId=... ...

targetScope = 'resourceGroup'

@description('Azure region; must match the Container Apps environment.')
param location string = resourceGroup().location

@description('Name of the container app.')
param appName string = 'doc-ai'

@description('Resource id of the Container Apps environment (infra.bicep output).')
param environmentId string

@description('Resource id of the user-assigned identity that pulls the image (infra.bicep output).')
param identityId string

@description('Login server of the registry, e.g. docaitest1234.azurecr.io (infra.bicep output).')
param registryLoginServer string

@description('Fully qualified image reference, including tag. Never :latest - a revision has to be traceable to a build.')
param image string

@description('''
Spring profiles. `prod` switches the Swagger UI off (SPEC §10); the second entry selects the
model provider (SPEC §8). Naming any profile replaces spring.profiles.default, so the provider
has to be named here explicitly.

`anthropic` sends page images to the Anthropic API. SPEC §7 forbids that for real
Krankenstandsbestätigungen until data protection approves it - this environment is for
synthetic documents. HostedModelWarning logs a WARN at startup to say so.
''')
param springProfilesActive string = 'prod,anthropic'

@description('Entra issuer. For v2.0 the tenant GUID, not the domain: https://login.microsoftonline.com/<tenant-guid>/v2.0')
param jwtIssuerUri string

@description('The `aud` this API accepts: the client id of its own app registration.')
param jwtAudience string

@description('Anthropic API key. Held as a container app secret; see README for the Key Vault alternative.')
@secure()
param anthropicApiKey string

@description('Keep at least one replica warm: a JVM cold start would otherwise land on a caller waiting synchronously.')
@minValue(1)
param minReplicas int = 1

@minValue(1)
param maxReplicas int = 5

var port = 8080

resource app 'Microsoft.App/containerApps@2024-03-01' = {
  name: appName
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: { '${identityId}': {} }
  }
  properties: {
    managedEnvironmentId: environmentId
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: false
        targetPort: port
        transport: 'auto'
        allowInsecure: false
        traffic: [ { latestRevision: true, weight: 100 } ]
      }
      registries: [
        { server: registryLoginServer, identity: identityId }
      ]
      secrets: [
        { name: 'anthropic-api-key', value: anthropicApiKey }
      ]
    }
    template: {
      containers: [
        {
          name: 'doc-ai'
          image: image
          resources: {
            // PDFBox rasterises up to 5 pages at 150 dpi in memory (SPEC §2, §5). 0.5 CPU /
            // 1 Gi is the platform default and too tight for that; ACA only accepts fixed
            // CPU/memory pairs, and 1.0 / 2.0Gi is the next one up.
            cpu: json('1.0')
            memory: '2.0Gi'
          }
          env: [
            { name: 'SPRING_PROFILES_ACTIVE', value: springProfilesActive }
            { name: 'DOCAI_JWT_ISSUER_URI', value: jwtIssuerUri }
            { name: 'DOCAI_JWT_AUDIENCE', value: jwtAudience }
            { name: 'ANTHROPIC_API_KEY', secretRef: 'anthropic-api-key' }
          ]
          probes: [
            {
              // Spring Boot's own probes, enabled by management.endpoint.health.probes in
              // application.yml and left public by SecurityConfig, which permits the health
              // endpoint by endpoint rather than by path. The platform has no token.
              type: 'Startup'
              httpGet: { path: '/actuator/health/liveness', port: port }
              initialDelaySeconds: 10
              periodSeconds: 5
              timeoutSeconds: 3
              // 30 x 5s. Generous on purpose: the first start also resolves the Entra metadata.
              failureThreshold: 30
            }
            {
              type: 'Liveness'
              httpGet: { path: '/actuator/health/liveness', port: port }
              periodSeconds: 20
              timeoutSeconds: 3
              failureThreshold: 3
            }
            {
              // Readiness, not liveness, is what ingress takes a replica out of rotation on.
              type: 'Readiness'
              httpGet: { path: '/actuator/health/readiness', port: port }
              periodSeconds: 10
              timeoutSeconds: 5
              failureThreshold: 3
            }
          ]
        }
      ]
      scale: {
        minReplicas: minReplicas
        maxReplicas: maxReplicas
        rules: [
          {
            name: 'http-concurrency'
            http: {
              metadata: {
                // A request is one model call, seconds not milliseconds, and it holds a
                // virtual thread rather than a platform one. Low concurrency per replica
                // keeps a queue from forming behind the 60s docai.ai.timeout.
                concurrentRequests: '4'
              }
            }
          }
        ]
      }
    }
  }
}

@description('Internal FQDN. Resolvable only from inside the VNet or a network peered with it.')
output internalFqdn string = app.properties.configuration.ingress.fqdn

output latestRevisionName string = app.properties.latestRevisionName
