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
| `paths` | every resource method with a swagger-core `@Operation(operationId = ...)`, in the resources each servlet's JAX-RS application registers |
| `components.schemas` | the records those annotations name (such as `ServerConfig`), and each reactor's input and output |
| `x-semoss-servlets` | each servlet prefix and the SDK module it is published under (`"/api": "monolith"`) |
| `x-semoss-pixels` | version 1: every `AbstractReactor2` in the `prerna` packages, read from its input record and output type |
| `x-internal`, `x-internal-reason` | on an operation or reactor marked `@Internal` |

The document is canonical: two runs over the same code write the same bytes. It is
checked with swagger-parser and rejected if it uses OpenAPI 3.0 forms
(`nullable`, `example`) or leaves a property undocumented.

Nothing is listed by hand: what is annotated is published. The one table the
exporter keeps, `SERVLETS` in `OpenApiExporter`, maps each servlet prefix to its
application and SDK module name, and the tests check it against `web.xml`.

## Hiding or marking an API

- **`@Hidden`** (swagger-core, `io.swagger.v3.oas.annotations.Hidden`) leaves an API out of
  the document, and so out of every SDK. Put it on a resource method, on a resource
  class (all of its methods), or on an `AbstractReactor2` class.
- **`@Internal`** (Semoss, `prerna.openapi.Internal`) publishes it with
  `"x-internal": true`, plus `"x-internal-reason"` when you give one:
  `@Internal("Used by the admin console only")`. Generated SDK code tags it
  `@internal`, so editors warn anyone who uses it. It is not access control: the API
  still works for everyone.

## Publishing an endpoint

1. Annotate the method. The `operationId` is the last URL segment, and becomes the
   SDK function name:

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
4. The `operationId` must be unique within its servlet; the export fails otherwise. A
   method without `@Operation` is not published.

## Publishing a reactor

1. Extend `AbstractReactor2<Input, Output>` (in Semoss) and declare the inputs as a
   record. See its Javadoc for the supported types.
2. Give it a `getReactorDescription()`.
3. That is all: every concrete `AbstractReactor2` is published unless it is `@Hidden`.
   Two reactors with the same Pixel name fail the export.

## Tests

- `OpenApiExporterTest` checks:
  - that two exports are identical;
  - what the current code publishes (`/api/config` and the three reactors);
  - on fixtures in `test/openapi/fixtures`: that unannotated and `@Hidden` methods and
    reactors are left out, that `@Internal` ones get `x-internal`, and that a duplicate
    `operationId` fails;
  - that every servlet's application constructs, and that `SERVLETS` matches
    `WebContent/WEB-INF/web.xml`.
- `ServerConfigContractTest` checks that `ServerConfig` documents exactly the keys
  `ServerConfigurationResource` puts.
