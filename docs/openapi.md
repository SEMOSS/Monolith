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
| `x-semoss-pixels` | version 1: every general reactor in the `prerna` packages, its contract inferred from what it declares and refined by `@PixelInput` and `@PixelOutput` |
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

Every general reactor is published with no changes, its contract inferred from what
it already declares (`prerna.reactor.ReactorContract` in Semoss):

| Contract | Inferred from |
|---|---|
| inputs | `keysToGet`, by canonical name (before any `,alias`) |
| required inputs | `keyRequired`; with none, nothing is required, as `organizeKeys()` checks |
| input types | `getKeyTypeForMCP` (string, integer, number, boolean, array, object) |
| descriptions | `getDescriptionForKey` and `getReactorDescription`, or a placeholder |
| output | nothing: published as any value unless declared |

Refine what inference cannot see with annotations from `prerna.openapi`:

```java
@PixelOutput(value = String.class, description = "The id of the current insight")
@PixelInput(name = "ids", type = List.class, items = String.class, description = "The job ids")
@PixelInput(name = "value", type = Object.class)   // any value
public class SomeReactor extends AbstractReactor { ... }
```

- **What is left out:**
  - Frame-specific reactors, the R, Python, SQL and graph versions `ReactorFactory` keeps per frame type. They share the general reactor's name, which is what Pixel calls.
  - `@Hidden` reactors.
- **Skipped reactors:** a reactor whose contract cannot be read (its constructor fails, a key or name is not an identifier, or two classes claim one name) is left out and listed with the reason in `target/generator/skipped.txt`. The list is the same on every run. A reactor with `@PixelInput` or `@PixelOutput` fails the export instead, as does a `@PixelInput` for a key the reactor does not have.
- **Flagged contracts:** a reactor with anything inferred gets `"x-semoss-inferred": true`, which the SDK notes in its TSDoc.

## Tests

- `OpenApiExporterTest` checks:
  - that two exports are identical and skip the same reactors;
  - what the current code publishes (`/api/config`, every general reactor, and the pinned
    types of the three annotated pilot reactors);
  - on fixtures in `test/openapi/fixtures`:
    - unannotated and `@Hidden` methods are left out;
    - `@Hidden` and frame-specific reactors are left out;
    - inferred and annotated reactors are read correctly;
    - unreadable and twice-named reactors are skipped and reported;
    - `@Internal` ones get `x-internal`;
    - a duplicate `operationId`, or a bad `@PixelInput` (`test/openapi/badfixtures`), fails;
  - that every servlet's application constructs, and that `SERVLETS` matches
    `WebContent/WEB-INF/web.xml`.
- In Semoss, `ReactorContractTest` covers the inference rules and the annotations.
- `ServerConfigContractTest` checks that `ServerConfig` documents exactly the keys
  `ServerConfigurationResource` puts.
