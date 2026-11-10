# OpenAPI document for the SDKs

The backend owns the contract the SEMOSS SDKs are generated from. Monolith writes
one OpenAPI 3.1 document, and each SDK reads it: the TypeScript SDK in
`semoss-ui/libs/sdk` today, and later the Python SDK.

```bash
scripts/generate-api                               # writes target/generator/openapi.json
scripts/generate-api --sdk ../semoss-ui/libs/sdk   # ...then regenerates the TypeScript SDK
```

The script installs `../Semoss` first; pass `--semoss PATH` or `--skip-semoss-install`
to change that. The document is build output (under `target/`) and is not committed.

## What is in it

| Part | Comes from |
|---|---|
| `paths` | every resource method with a swagger-core `@Operation(operationId = ...)`, in the resources each servlet's JAX-RS application registers and the sub-resources their locators return |
| `tags` | one per module, on each operation and listed at the top level (see [Operation names](#operation-names)) |
| `servers` | one server, `{contextPath}`, whose `contextPath` variable is the web app's context path (default `/Monolith`) |
| `components.schemas` | the records those annotations name (such as `ServerConfig`), and each reactor's input and output |
| `x-semoss-servlets` | each servlet prefix and the SDK module it is published under (`"/api": "monolith"`) |
| `x-semoss-reactor` | version 1: every general reactor in the `prerna` packages that declares something about itself, with the contract it declares |
| `x-semoss-inferred` | on a reactor whose contract is inferred in part |
| `x-internal`, `x-internal-reason` | on an operation or reactor marked `@Internal` |
| `x-semoss-sdk-skip` | on an operation no SDK generates a call for, with the reason (see [Operations SDKs skip](#operations-sdks-skip)) |

The document is canonical: two runs over the same code write the same bytes. It is
checked with swagger-parser and rejected if it uses OpenAPI 3.0 forms
(`nullable`, `example`) or leaves a property undocumented.

Nothing is listed by hand: what is annotated is published. The one table the
exporter keeps, `SERVLETS` in `OpenApiExporter`, maps each servlet prefix to its
application and SDK module name, and the tests check it against `web.xml`.

## Hiding or marking an API

- **`@Hidden`** (swagger-core, `io.swagger.v3.oas.annotations.Hidden`) leaves an API out of
  the document, and so out of every SDK. Put it on a resource method, on a resource
  class (all of its methods), or on a reactor class.
- **`@Internal`** (Semoss, `prerna.openapi.Internal`) publishes it with
  `"x-internal": true`, plus `"x-internal-reason"` when you give one:
  `@Internal("Used by the admin console only")`. Generated SDK code tags it
  `@internal`, so editors warn anyone who uses it. It is not access control: the API
  still works for everyone.

## Publishing an endpoint

1. Annotate the method. The `operationId` you write is the SDK function name (see
   [Operation names](#operation-names)):

   ```java
   @Operation(operationId = "config", summary = "Read the server configuration", description = "...")
   @ApiResponse(responseCode = "200", description = "The server configuration",
   		content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServerConfig.class)))
   ```

2. Describe the response with a record next to the resource. Give every component
   a `@Schema(description = ...)`, and mark the ones always present
   `requiredMode = REQUIRED`. Responses leave out null values, so a value that can be
   null is optional.
3. Declare query parameters and form bodies with `@Parameter` and `@RequestBody`.
   Most resources read them from the request by hand, so swagger-core cannot see
   them otherwise.
4. Put the error responses the method sends next to its 200: most send
   `{ "errorMessage": ... }`, which is `CommonSchemas.ErrorMessage`. Shapes many
   resources share live in `CommonSchemas`; a resource's own records go in a
   `<Resource>Schemas` class next to it. A schema is named by its record's simple
   name, so give each record a name no other record has (`AuthProjectGetProjectsRow`,
   not `Row`).
5. Path parameters, JSON and multipart bodies, and non-JSON responses (files, images,
   `text/event-stream`, ndjson) are declared with their real media types, so the
   document is the whole contract even where an SDK cannot generate a call yet.
6. Every REST method a servlet serves must have `@Operation` or `@Hidden`;
   `OpenApiCoverageTest` fails otherwise.

## Operation names

The document's `operationId` is unique across the whole document, as OpenAPI requires,
and an SDK reads its module and function name from it:
`{servlet module}.{module segments}.{name}`, such as `monolith.auth.project.getProjects`
for `POST /api/auth/project/getProjects`.

- **The name** is what `@Operation(operationId = ...)` says. It must be the last path
  segment when that segment is a name, so every SDK names calls the same way. When it is
  not a name (a path parameter, the resource root, a segment with a `-`), choose one.
- **A path with several methods** gives the plain name to its POST, else its GET, and
  every other method adds its own name: the POST of `/auth/project/getProjects` is
  `getProjects` and its GET is `getProjectsGet`; the GET of `/github/project/link` is
  `link` and its DELETE is `linkDelete`. All of them are published.
- **The module segments** are the path's other segments, each without its path
  parameters and in lower camel case: `/api/e-{engineId}/type` is `monolith.e.type`, and
  `/api/browser-sessions/{id}/steps` is `monolith.browserSessions.steps`. A segment that
  is only a parameter is left out.
- **The same name** may be used in different modules (`auth.project.getProjects` and
  `auth.admin.project.getProjects`). Two operations published under one id fail the
  export.
- **The tag** of every operation is its module, the `operationId` without its last part:
  `monolith.auth.project.getProjects` is tagged `monolith.auth.project`. The exporter adds
  it, and lists each tag at the top level with the paths its operations are under, so
  editors, Swagger UI and generators group operations by module. An `@Operation` does not
  declare `tags`; the export fails if one does.

## When an API is hidden

`@Hidden` is for an endpoint no client calls whose shape an SDK cannot generate a call
for yet: a path parameter, a JSON or multipart body, a method other than GET and POST, or
a response that is not JSON. Each one says why in a comment above it. Everything else is
published, whether or not a client uses it yet. The TypeScript generator now reads those
shapes (see [What the SDKs read](#what-the-sdks-read)), so such an endpoint can be
published once a client needs it. One that no SDK should call is published with
`x-semoss-sdk-skip` rather than hidden (see [Operations SDKs skip](#operations-sdks-skip)).

## Operations SDKs skip

Some published operations are not for an SDK to call. The exporter marks each one with
`"x-semoss-sdk-skip"` and the reason, so every SDK skips the same ones and none keeps a
rule of its own:

- **Browser navigations**, marked by `OpenApiExporter.markSdkSkip` without anything on
  the method: an operation with no 2xx response (it only redirects, such as
  `/github/install/app`) or whose 2xx responses are all `text/html` pages (the
  `/api/auth/login/*` sign-in pages).
- **Endpoints other servers call**, such as webhooks, declared on the method with a
  swagger-core extension:

  ```java
  @Operation(operationId = "webhook", ...,
  		extensions = @Extension(properties = @ExtensionProperty(name = "x-semoss-sdk-skip", value = "Webhook: GitHub calls it, not clients")))
  ```

  The GitHub and Microsoft Graph webhooks and the MCP server endpoint
  (`/api/ext/mcp/{toolbox_id}/comms`) are marked this way.

A skipped operation is still published, so the document stays the whole contract.

## How SDKs are generated

`scripts/generate-api --sdk PATH` writes the document and then runs the TypeScript
generator on it (`pnpm generate --openapi` in `libs/sdk`). Every SDK reads the same rules
from the document, so none keeps its own:

| What | Read from |
|---|---|
| A call's module | its `tags[0]`, always its `operationId` without the last part |
| A call's function name | the last part of its `operationId` |
| Which calls to leave out | `x-semoss-sdk-skip` |
| Types | `components.schemas`, one record per payload |
| Typed Pixel | `x-semoss-reactor` |

Off-the-shelf generators work on the same document. openapi-generator, for one, makes a
class per tag and, with the module prefix removed from the operationId, names each method
as the SDKs do:

```bash
openapi-generator-cli generate -i target/generator/openapi.json -g python -o OUT \
	--additional-properties=removeOperationIdPrefix=true,removeOperationIdPrefixDelimiter=.,removeOperationIdPrefixCount=-1
```

gives `MonolithAuthProjectApi().get_projects()` for `monolith.auth.project.getProjects`.
They do not read `x-semoss-reactor`, so they make no typed Pixel calls.

## What the SDKs read

The TypeScript generator in `semoss-ui/libs/sdk` (`scripts/sdk-generator/README.md`)
reads everything else in the document:

- the qualified `operationId`, for the module and the name, method suffixes included;
- path parameters, encoded into the path;
- form, `multipart/form-data` and `application/json` request bodies, sending the first of
  those an operation accepts;
- GET, POST, PUT, DELETE and PATCH;
- JSON responses, typed; other `text/*` as text; `text/event-stream` and ndjson as a
  stream; anything else (files, images) as binary; and 2xx responses without a body.

It rejects header and cookie parameters, and a 2xx response offering media types that are
read differently, so an operation that needs either has to be marked
`x-semoss-sdk-skip`.

## Publishing a reactor

A reactor describes itself by overriding `getReactorDefinition()` (Semoss,
`prerna.reactor.ReactorDefinition`). Its MCP tool, its help text and the SDKs all read
the same definition. It starts from `keysToGet` and `keyRequired`, so a reactor only
adds what it knows, and it can use anything the constructor set:

```java
@Override
public ReactorDefinition getReactorDefinition() {
	return super.getReactorDefinition()
			.description("Stages or unstages files in " + this.target.getLabelWithArticle() + "'s git repository")
			.key(PATHS_KEY, key -> key.list(String.class).description("The repo-relative file paths"))
			.key(ReactorKeysEnum.COMMENT_KEY, "The commit message")
			.key("value", key -> key.type(Object.class))          // any value
			.output(String.class, "The refreshed status");
}

@Override
public ReactorDefinition getReactorDefinition() {
	return super.getReactorDefinition()
			.noKeys()                                            // takes no input on purpose
			.outputSchema(responseSchema());                     // a JSON Schema, used as is
}
```

| Contract | Read from |
|---|---|
| description | `description(...)`, or a placeholder |
| inputs | `keysToGet`, by canonical name (before any `,alias`) |
| required inputs | `keyRequired`, or `key.required(...)`; with neither, nothing is required, as `organizeKeys()` checks |
| input types and descriptions | `key(...)`; a key the definition leaves out keeps the shared `ReactorKeysEnum` description and the default type |
| no inputs | `noKeys()` declares an empty input on purpose |
| output | `output(...)`, `outputList(...)` or `outputSchema(...)`; without one, any value |

A subclass calls `super.getReactorDefinition()` and adds to its parent's. The
definition is metadata only: `keysToGet` and `keyRequired` still decide how a call's
inputs are read. Reactors that still override the deprecated `getReactorDescription`,
`getDescriptionForKey` or `getKeyTypeForMCP` are read through the definition too.

- **What is published:** every general reactor that declares something about itself,
  by overriding `getReactorDefinition()` or one of those methods, on it or a parent, or
  by being marked `@Internal`. A reactor that declares nothing is not published and is
  not reported.
- **What is left out:**
  - Frame-specific reactors, the R, Python, SQL and graph versions `ReactorFactory` keeps per frame type. They share the general reactor's name, which is what Pixel calls.
  - `@Hidden` reactors.
- **Skipped reactors:** a published reactor whose contract cannot be read (its constructor fails, a key or name is not an identifier, or two classes claim one name) is left out and listed with the reason in `target/generator/skipped.txt`. The list is the same on every run.
- **Failed exports:** a definition that is wrong fails the export: an empty key or a key listed twice in the reactor's `keysToGet`, a key it describes that is not in `keysToGet`, or `noKeys()` on a reactor with keys. The export reads every reactor first and then lists each wrong definition with the class whose code made the mistake, which may be a parent. A parent describes only the keys all its reactors take; text several reactors share can live in the parent as a constant, and a reactor that does not take its parent's keys starts from `startDefinition()` instead of `super.getReactorDefinition()`.
- **Flagged contracts:** a reactor with anything inferred gets `"x-semoss-inferred": true`, which the SDK notes in its TSDoc. A reactor with no keys counts as inferred unless its definition says `noKeys()`.

## MCP

The same definition builds each reactor's MCP tool. A reactor with a declared output
gets an `outputSchema`; since MCP structured results are objects, an output that is
not one (a list, a string) is wrapped as `{ "result": ... }` and the schema is marked
`"x-semoss-wrap-result": true`. `MCPReaper` answers `tools/call` for such a tool with
`structuredContent` in that shape, beside the usual text content.

`initialize` answers with the client's protocol version when the server speaks it
(`2025-11-25`, `2025-06-18`, `2025-03-26`, `2024-11-05`), and with `2025-11-25`
otherwise.

## Tests

- `OpenApiExporterTest` checks:
  - that two exports are identical and skip the same reactors;
  - what the current code publishes (`/api/config` as `monolith.config`, every `operationId` qualified and unique, the general reactors that declare
    metadata, and the pinned types of the three pilot reactors);
  - on fixtures in `test/openapi/fixtures`:
    - unannotated and `@Hidden` methods are left out;
    - reactors that declare nothing, `@Hidden` reactors and frame-specific reactors are left out;
    - inferred, defined, keyless and JSON Schema output reactors are read correctly;
    - unreadable and twice-named reactors are skipped and reported;
    - `@Internal` ones get `x-internal`;
    - qualified operation names, method suffixes, path parameter segments and sub-resources (`NamingApplication`);
    - two operations published under one id (`DuplicateApplication`), an `operationId` that is not its last path segment (`MismatchApplication`), or a definition naming a missing key (`test/openapi/badfixtures`) fails;
  - that every servlet's application constructs, and that `SERVLETS` matches
    `WebContent/WEB-INF/web.xml`.
- `OpenApiCoverageTest` checks that every REST method a servlet serves, sub-resources
  included, has `@Operation` or `@Hidden`.
- `MCPReaperStructuredContentTest` checks the `structuredContent` a tool call returns.
- In Semoss, `ReactorContractTest` covers the contract rules, `ReactorDefinitionTest`
  covers what the MCP tool and help text read, and `InternalMCPTest` covers the
  protocol version and capabilities `initialize` answers with.
- `ServerConfigContractTest` checks that `ServerConfig` documents exactly the keys
  `ServerConfigurationResource` puts.
