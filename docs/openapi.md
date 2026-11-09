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
| `x-semoss-reactor` | version 1: every general reactor in the `prerna` packages that declares something about itself, with the contract it declares |
| `x-semoss-inferred` | on a reactor whose contract is inferred in part |
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
  class (all of its methods), or on a reactor class.
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
  - what the current code publishes (`/api/config`, the general reactors that declare
    metadata, and the pinned types of the three pilot reactors);
  - on fixtures in `test/openapi/fixtures`:
    - unannotated and `@Hidden` methods are left out;
    - reactors that declare nothing, `@Hidden` reactors and frame-specific reactors are left out;
    - inferred, defined, keyless and JSON Schema output reactors are read correctly;
    - unreadable and twice-named reactors are skipped and reported;
    - `@Internal` ones get `x-internal`;
    - a duplicate `operationId`, or a definition naming a missing key (`test/openapi/badfixtures`), fails;
  - that every servlet's application constructs, and that `SERVLETS` matches
    `WebContent/WEB-INF/web.xml`.
- `MCPReaperStructuredContentTest` checks the `structuredContent` a tool call returns.
- In Semoss, `ReactorContractTest` covers the contract rules, `ReactorDefinitionTest`
  covers what the MCP tool and help text read, and `InternalMCPTest` covers the
  protocol version and capabilities `initialize` answers with.
- `ServerConfigContractTest` checks that `ServerConfig` documents exactly the keys
  `ServerConfigurationResource` puts.
