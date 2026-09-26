# Endpoints by Application

The Monolith WAR hosts several Jakarta REST applications. Each application has its own servlet mapping in [web.xml](../WebContent/WEB-INF/web.xml). The default web context is `/Monolith`; replace it and add any deployment prefix when constructing URLs.

| Application | Base URL | Responsibility |
| --- | --- | --- |
| [MonolithApplication](../src/prerna/semoss/web/app/MonolithApplication.java) | `/Monolith/api` | Pixel, engines, projects, sessions, permissions, and integration APIs |
| [GitHubApplication](../src/prerna/semoss/web/app/GitHubApplication.java) | `/Monolith/github` | GitHub App setup, project links, callbacks, and push webhooks |
| [MicrosoftGraphApplication](../src/prerna/semoss/web/app/MicrosoftGraphApplication.java) | `/Monolith/msgraph` | Microsoft mailbox/calendar subscriptions and notifications |
| [HealthApplication](../src/prerna/semoss/web/app/HealthApplication.java) | `/Monolith/health` | Liveness, readiness, and resource status |
| [TrustedTokenApplication](../src/prerna/semoss/web/app/TrustedTokenApplication.java) | `/Monolith/token` | Trusted integration token service |
| [AdminApplication](../src/prerna/semoss/web/app/AdminApplication.java) | `/Monolith/adminconfig` | Initial administrator setup |

Paths below include the application base. `{...}` denotes an ID or other path parameter to replace; `*` denotes a route family for navigation, not a literal request URL. Engine IDs follow a hyphen in these routes: for example, `/Monolith/api/storage-<storage-id>/list`.

The tables describe registered routes and their main inputs. Resource implementations define the full contract, and operations still enforce the configured authentication, CSRF, and resource permissions. Dedicated applications apply their own checks and provider-verification rules.

## MonolithApplication

Base: **`/Monolith/api`**. [MonolithApplication](../src/prerna/semoss/web/app/MonolithApplication.java) registers the core resources below.

### Pixel execution and sessions

| Method and endpoint / route family | Purpose |
| --- | --- |
| `POST /Monolith/api/engine/runPixel` | Execute Pixel expressions in an Insight |
| `POST /Monolith/api/engine/runPixelAsync` | Submit asynchronous Pixel execution |
| `POST /Monolith/api/engine/pixelJobStreaming` | Poll Pixel job output |
| `POST /Monolith/api/engine/agentRunStreaming` | Drain live agent events and return a durable run snapshot |
| `/Monolith/api/session/*` | Session activity, cleanup, invalidation, and insights |
| `/Monolith/api/auth/*` | Login, logout, user identity, and provider callbacks |

Sources: [NameServer](../src/prerna/semoss/web/services/local/NameServer.java), [SessionResource](../src/prerna/semoss/web/services/local/SessionResource.java), and [UserResource](../src/prerna/semoss/web/services/local/UserResource.java). `RunAgent` is a Pixel operation submitted through the execution API; see [agent runs](https://github.com/SEMOSS/Semoss/blob/dev/docs/agents/agent_runs.md).

### Database engines

[DatabaseEngineResource](../src/prerna/semoss/web/services/local/DatabaseEngineResource.java) serves **`/Monolith/api/database-{databaseId}`**.

| Method and endpoint | Inputs / purpose |
| --- | --- |
| `GET /Monolith/api/database-{databaseId}/type` | Return database type and catalog subtype |
| `POST /Monolith/api/database-{databaseId}/reload` | Reload the configured database through `ReloadDatabase` |
| `POST /Monolith/api/database-{databaseId}/query` | Required `query`; execute a query through the database engine |

### Storage engines

[StorageEngineResource](../src/prerna/semoss/web/services/local/StorageEngineResource.java) serves **`/Monolith/api/storage-{storageId}`**.

| Method and endpoint | Inputs / purpose |
| --- | --- |
| `POST /Monolith/api/storage-{storageId}/list` | Required `storagePath`; list a storage path |
| `POST /Monolith/api/storage-{storageId}/listDetails` | Required `storagePath`; list entries with details |
| `POST /Monolith/api/storage-{storageId}/delete` | Required `storagePath`; delete the selected storage content |

### Vector engines

[VectorEngineResource](../src/prerna/semoss/web/services/local/VectorEngineResource.java) serves **`/Monolith/api/vector-{vectorId}`**.

| Method and endpoint | Inputs / purpose |
| --- | --- |
| `POST /Monolith/api/vector-{vectorId}/query` | Required `command`; optional `limit`, `paramValues`; search the vector database |
| `POST /Monolith/api/vector-{vectorId}/listDocuments` | Optional `paramValues`; list indexed documents |
| `POST /Monolith/api/vector-{vectorId}/removeDocument` | Required `fileNames`; optional `paramValues`; remove indexed documents |

### Model engines

[ModelEngineResource](../src/prerna/semoss/web/services/local/ModelEngineResource.java) serves **`/Monolith/api/model-{modelId}`**.

| Method and endpoint | Inputs / purpose |
| --- | --- |
| `POST /Monolith/api/model-{modelId}/llm` | Required `command`; optional `context`, `useHistory`, `paramValues`, `roomId`, `image`, `url`; invoke `LLM` |
| `POST /Monolith/api/model-{modelId}/llmStreaming` | Stream the LLM operation as server-sent events |
| `POST /Monolith/api/model-{modelId}/embeddings` | Required `values`; optional `paramValues`; generate embeddings |
| `POST /Monolith/api/model-{modelId}/vision` | Required `command`, `image`; optional `paramValues`; invoke vision |

`llmStreaming` emits SEMOSS job payloads and ends with `data: [DONE]`. Use the compatibility endpoints below when a client expects a provider-specific response format. Supported generation, embeddings, media, and streaming capabilities depend on the selected model engine.

### Function engines

[FunctionEngineResource](../src/prerna/semoss/web/services/local/FunctionEngineResource.java) serves **`/Monolith/api/function-{functionId}`**.

| Method and endpoint | Inputs / purpose |
| --- | --- |
| `POST /Monolith/api/function-{functionId}/execute` | Optional `map` or its alias `inputs`; execute the function using its configured input contract |
| `GET /Monolith/api/function-{functionId}/definition` | Return the function engine definition |

### Shared engine operations

[EngineRouteResource](../src/prerna/semoss/web/services/local/EngineRouteResource.java) serves **`/Monolith/api/e-{engineId}`** for operations shared across engine catalogs.

| Method and endpoint | Purpose |
| --- | --- |
| `GET /Monolith/api/e-{engineId}/type` | Return engine type and subtype |
| `POST /Monolith/api/e-{engineId}/updateSmssFile` | Update engine configuration using the `smss` parameter |
| `POST /Monolith/api/e-{engineId}/image/upload` | Upload a catalog image using multipart field `file` |
| `GET /Monolith/api/e-{engineId}/image/download` | Download the engine catalog image |

See [catalog image uploads](catalog-image-uploads.md) for supported images and permission requirements. Guardrail and virtual-environment engines have no dedicated operation resource registered alongside the database/model/storage/vector/function resources in this application; use their supported Pixel operations and shared catalog routes where applicable.

### Projects, agents, and skills

[ProjectResource](../src/prerna/semoss/web/services/local/ProjectResource.java) serves **`/Monolith/api/project-{projectId}`**. Workspace agents and skills use their project IDs for these project operations.

| Method and endpoint | Purpose |
| --- | --- |
| `POST /Monolith/api/project-{projectId}/runReactor/{reactorName}` | Execute a project reactor |
| `POST /Monolith/api/project-{projectId}/updateSmssFile` | Update project configuration |
| `GET /Monolith/api/project-{projectId}/landing` | Serve the project landing content |
| `GET /Monolith/api/project-{projectId}/downloadProjectAsset/{relPath}` | Download a project asset |
| `POST /Monolith/api/project-{projectId}/image/upload` | Upload a project catalog image |
| `POST /Monolith/api/project-{projectId}/projectImage/upload` | Alias for project catalog image upload |
| `GET /Monolith/api/project-{projectId}/projectImage/download` | Download the project catalog image |

The same resource also defines embedded logos, insight images, and JDBC-format result routes. See its source for those request parameters and the [project guide](https://github.com/SEMOSS/Semoss/blob/dev/docs/engines/project_engines.md) for project types and assets.

### Engine request bodies and results

The database query, storage, vector, model, and function execution wrappers accept JSON or form-encoded bodies. Prefer JSON for nested maps and arrays. For example, send this JSON to `POST /Monolith/api/vector-{vectorId}/query` after replacing the vector ID:

```json
{
  "command": "Find documents about agent configuration",
  "limit": 5,
  "paramValues": {}
}
```

These wrappers invoke the corresponding Pixel operations through [ResourceUtility](../src/prerna/semoss/web/services/local/ResourceUtility.java), with the authenticated user and a temporary Insight. Standard wrapper results use the SEMOSS Pixel response structure; Pixel errors or invalid syntax produce HTTP `400`, and a missing session produces `401`. Metadata, image, streaming, and compatibility routes have their own response contracts. Existing session state and permissions remain necessary when calling the typed routes directly.

### Model compatibility APIs

These resources also belong to **MonolithApplication** and retain the `/Monolith/api` prefix.

| Resource | Endpoints |
| --- | --- |
| [OpenAIEndpoints](../src/prerna/semoss/web/services/local/OpenAIEndpoints.java) | `POST /Monolith/api/model/openai/v1/chat/completions`, `POST /Monolith/api/model/openai/v1/responses`, `POST /Monolith/api/model/openai/v1/images/generations`, `POST /Monolith/api/model/openai/v1/embeddings`, `GET /Monolith/api/model/openai/v1/models`, `GET /Monolith/api/model/openai/v1/models/{modelId}` |
| [AnthropicEndpoints](../src/prerna/semoss/web/services/local/AnthropicEndpoints.java) | `POST /Monolith/api/model/anthropic/v1/messages` |
| [OllamaEndpoints](../src/prerna/semoss/web/services/local/OllamaEndpoints.java) | `POST /Monolith/api/model/ollama/api/chat`, `POST /Monolith/api/model/ollama/api/generate`, `POST /Monolith/api/model/ollama/embeddings` |

OpenAI also exposes the listed routes without `/v1` and a legacy `POST /Monolith/api/model/openai/completions` route. Ollama exposes `/chat` and `/generate` aliases beneath `/Monolith/api/model/ollama`. The second `/api` in the Ollama chat/generate paths belongs to the compatibility route itself.

### MCP, A2A, and other core resources

| Endpoint / route family | Purpose / implementation |
| --- | --- |
| `POST /Monolith/api/ext/mcp/{toolbox_id}/comms` | [MCPResource](../src/prerna/semoss/web/services/local/MCPResource.java) tool communication |
| `GET /Monolith/api/ext/a2a/workspace/{workspaceId}/.well-known/agent-card.json` | [A2AResource](../src/prerna/semoss/web/services/local/A2AResource.java) agent card |
| `POST /Monolith/api/ext/a2a/workspace/{workspaceId}/rpc` | A2A JSON-RPC operations |
| `/Monolith/api/browser-sessions/*` | [RemoteBrowserSessionController](../src/prerna/remoteviewer/api/RemoteBrowserSessionController.java) sessions, steps, and recordings |
| `/Monolith/api/uploadFile/*`, `/Monolith/api/images/*` | [FileUploader](../src/prerna/upload/FileUploader.java) and [ImageUploader](../src/prerna/upload/ImageUploader.java) |
| `/Monolith/api/authorization/*` | [AuthorizationResource](../src/prerna/semoss/web/services/local/AuthorizationResource.java) |
| `/Monolith/api/auth/engine/*`, `/Monolith/api/auth/project/*`, `/Monolith/api/auth/insight/*`, `/Monolith/api/auth/user/*` | [Resource authorization services](../src/prerna/semoss/web/services/local/auth/) |
| `/Monolith/api/auth/group/*`, `/Monolith/api/auth/admin/*` | Group permissions and administrator operations in the main API |
| `/Monolith/api/exec/*`, `/Monolith/api/share/*` | [ExecuteInsightResource](../src/prerna/semoss/web/services/local/ExecuteInsightResource.java) and [ShareInsightResource](../src/prerna/semoss/web/services/local/ShareInsightResource.java) |
| `/Monolith/api/schedule/*` | [SchedulerResource](../src/prerna/semoss/web/services/local/SchedulerResource.java) |
| `/Monolith/api/themes/*` | [AdminThemeResource](../src/prerna/semoss/web/services/local/AdminThemeResource.java) |
| `/Monolith/api/config/*` | [ServerConfigurationResource](../src/prerna/semoss/web/services/config/ServerConfigurationResource.java) |
| `/Monolith/api/form/*` | [FormResource](../src/prerna/semoss/web/form/FormResource.java) legacy forms |

`NameServer` also mounts legacy subresources at `/Monolith/api/engine/i-{insightID}/*` for frames and `/Monolith/api/engine/e-{engine}/*` for older engine operations. The shared engine catalog resource documented above is `/Monolith/api/e-{engineId}/*`.

### Runtime endpoint discovery

`GET /Monolith/api/config/endpoints` lists HTTP methods, resource-relative paths, resource classes, operations, and declared content types for the main application's registered resources. The response includes `contextPath`, `count`, `includesAdminResources`, and `endpoints`.

Prefix each returned `path` with the deployment context and `/api` when building a URL. The current implementation omits resource classes whose names start with `Admin` unless the caller is an administrator. It inspects MonolithApplication's resources; use the separate application sections below for their routes. Subresource-locator children are not recursively enumerated.

## GitHubApplication

Base: **`/Monolith/github`**. Registered resources: [GitHubService](../src/prerna/semoss/web/github/GitHubService.java) and [AdminGitHubService](../src/prerna/semoss/web/github/AdminGitHubService.java).

| Endpoint / route family | Purpose |
| --- | --- |
| `POST /Monolith/github/webhook` | Signature-verified GitHub App events; pushes dispatch linked-project synchronization |
| `GET /Monolith/github/available` | App configuration preflight |
| `/Monolith/github/manifest/*` | Administrator app creation, callback, configuration, and project-link inventory |
| `/Monolith/github/install/*` | Installation callback and repository/branch selection |
| `/Monolith/github/user/*` | GitHub user authorization and callback |
| `/Monolith/github/project/*` | Read/disconnect project links and update tracked branches |

See [GitHub webhook and setup endpoints](https://github.com/SEMOSS/Semoss/blob/dev/docs/integrations/monolith_webhooks.md#github) for every method, parameter, callback, and response. These paths start with `/Monolith/github` directly.

## MicrosoftGraphApplication

Base: **`/Monolith/msgraph`**. Registered resources: [MicrosoftGraphNotificationService](../src/prerna/semoss/web/ms/MicrosoftGraphNotificationService.java) and [MicrosoftGraphSubscriptionService](../src/prerna/semoss/web/ms/MicrosoftGraphSubscriptionService.java).

| Method and endpoint | Purpose |
| --- | --- |
| `POST /Monolith/msgraph/notifications/messages` | Mailbox notifications and subscription validation |
| `POST /Monolith/msgraph/notifications/events` | Calendar notifications and subscription validation |
| `GET /Monolith/msgraph/available` | Microsoft identity, scope, and public-URL preflight |
| `POST /Monolith/msgraph/subscribe` | Create a mailbox/calendar subscription |
| `GET /Monolith/msgraph/subscriptions` | Compare recorded subscriptions with Microsoft's state |
| `POST /Monolith/msgraph/renew` | Renew an owned subscription |
| `DELETE /Monolith/msgraph/subscription` | Remove an owned subscription |

See [Microsoft Graph notification endpoints](https://github.com/SEMOSS/Semoss/blob/dev/docs/integrations/monolith_webhooks.md#microsoft-graph) for parameters, validation, persistence, and renewal. Receivers currently fetch and log changes; they do not automatically submit agent runs.

## HealthApplication

Base: **`/Monolith/health`**. Registered resource: [HealthResource](../src/prerna/semoss/web/services/config/HealthResource.java).

| Method and endpoint | Purpose |
| --- | --- |
| `GET /Monolith/health/` | Liveness |
| `GET /Monolith/health/ready` | Startup readiness: `200` when ready, `503` otherwise |
| `GET /Monolith/health/details` | Required/enabled system-resource status |

Health probes use this dedicated application and do not require a user session.

## TrustedTokenApplication

Base: **`/Monolith/token`**. Registered resource: [TrustedTokenService](../src/prerna/semoss/web/services/config/TrustedTokenService.java).

| Method and endpoint | Purpose |
| --- | --- |
| `POST /Monolith/token/getToken` | Request a trusted integration token using the configured client-credential flow |
| `GET /Monolith/token/getToken` | Legacy variant; rejected when client/secret validation is enabled |

The POST flow reads `client_id` and, when credential validation is enabled, `secret_key`. Configure trusted integrations through the deployment's token and filter settings. This application is separate from the login and resource-authorization APIs under `/Monolith/api/auth`.

## AdminApplication

Base: **`/Monolith/adminconfig`**. Registered resource: [AdminConfigService](../src/prerna/semoss/web/services/config/AdminConfigService.java).

| Method and endpoint | Purpose |
| --- | --- |
| `POST /Monolith/adminconfig/setInitialAdmins` | Initial administrator registration during deployment setup |

The startup filter and service restrict this setup path once the security database contains users. Ongoing administrator operations are served by the main API's `/Monolith/api/auth/admin/*` resources.

## Other web mappings

Direct servlet mappings, static assets, and WebSockets are outside these six REST applications. For example, [SamlVerifierServlet](../src/prerna/semoss/web/services/saml/SamlVerifierServlet.java) is mapped at `/Monolith/saml/config/fedletapplication`. Consult [web.xml](../WebContent/WEB-INF/web.xml) and the relevant WebSocket implementation for those contracts.

When testing route changes in the local Docker image, verify the effective descriptor: the [local build workflow](../README.md#quick-start-with-docker) preserves `web.xml` from the base image. See the [core integration guide](https://github.com/SEMOSS/Semoss/blob/dev/docs/integrations/monolith_interaction.md) for request execution and agent boundaries.
