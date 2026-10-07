# Catalog image uploads

Upload or replace a catalog thumbnail with one `multipart/form-data` file part named
`file`. Use the resource's ID in the URL; no ID or catalog type is needed in the form.

| Resource | POST upload | GET download |
| --- | --- | --- |
| Engines (model, database, storage, vector, function, guardrail, virtual environment) | `/Monolith/api/e-{engineId}/image/upload` | `/Monolith/api/e-{engineId}/image/download` |
| Projects, agents (workspaces), skills, notebooks, and apps | `/Monolith/api/project-{projectId}/image/upload` | `/Monolith/api/project-{projectId}/projectImage/download` |

`/Monolith/api/project-{projectId}/projectImage/upload` is an alias for the project
upload route. Replace `/Monolith` if your installation uses a different context path.
Agents and skills use their **project ID** and the project's edit permissions.

The caller must be logged in and have edit permission on the resource. Send the usual
session cookies and CSRF header required by your deployment.

## Browser example

```javascript
const form = new FormData();
form.append("file", fileInput.files[0]);

const response = await fetch(`/Monolith/api/project-${projectId}/image/upload`, {
  method: "POST",
  credentials: "include",
  headers: csrfHeaders, // your application's normal CSRF headers
  body: form,
});
const result = await response.json();
if (!response.ok) throw new Error(result.errorMessage ?? "Image upload failed");
// result.imageUrl is the existing download route for the new image.
```

Let the browser set `Content-Type` so it includes the multipart boundary.

```sh
curl -b cookies.txt \
  -F 'file=@avatar.png' \
  'http://localhost:8080/Monolith/api/project-PROJECT_ID/image/upload'
```

Add your deployment's CSRF header to the curl request when required.

## Response and validation

A successful upload returns HTTP 200 with JSON:

```json
{
  "message": "Successfully updated image",
  "id": "resource-123",
  "name": "My agent",
  "imageUrl": "/Monolith/api/project-resource-123/projectImage/download",
  "contentType": "image/png"
}
```

These new routes accept PNG, JPEG, and GIF files up to **10 MiB** and
**25 million pixels**. The format is detected from the actual image bytes. The
client's filename and MIME type do not determine the stored filename. SVG, WebP,
and other formats are not accepted by these routes.

| Status | Meaning |
| --- | --- |
| 400 | Invalid ID, empty/corrupt image, invalid dimensions, missing `file`, or malformed multipart body |
| 401 | Missing session or anonymous user |
| 403 | Resource is missing or the caller cannot edit it |
| 413 | File/request size limit exceeded, or multiple multipart parts supplied |
| 415 | Request is not multipart, or image format is unsupported |
| 500 | Image storage or synchronization failed |

Validation runs before the current image is replaced. File writes are staged, and
other image extensions are removed after the new file is in place. Images use the
existing version-folder, CouchDB, and cluster image-storage conventions, so the
existing download routes display the uploaded image. A synchronization failure is
reported as an error; a local write may already have completed, and the upload can
be retried.

The legacy `/api/images/engine/upload`, `/api/images/projectImage/upload`, and
insight upload routes retain their existing request contracts.

## Shared stock images

The client uses the instance-managed badge for registered system projects and
uploaded images or themed stock artwork for other project catalogs (including
agents, skills, apps, notebooks, and automations). Engine catalog
cards and detail headers use their database-type or model-provider icons from
the existing frontend asset library. Engine image endpoints remain available
to other consumers.

When an ordinary project or engine has no saved image, the download route serves
a shared stock image directly from the requested stock collection. It does not copy that
fallback into the resource's version folder or upload it to cluster image storage.
The CouchDB project/database fallback likewise returns stock bytes without saving
a per-resource attachment. Uploaded images continue to take precedence.

Cluster downloads share a successful cloud-folder refresh for 30 seconds, so
cards without custom images do not each trigger another folder pull. Local
uploads take precedence immediately; a new upload from another node may take
up to 30 seconds to appear on a node that is currently using a stock fallback.

Selection uses the existing deterministic key: the resource name for local
version paths, the resource ID for cluster image paths, and the partition/name
for CouchDB. Paired light and dark collections use matching, sorted filenames,
so changing the theme keeps the same artwork. No per-resource reference file or
database record is required.

Both download routes accept `?theme=light` or `?theme=dark`. The catalog UI sends
its resolved theme, including the operating system preference when set to
System, and updates the URL whenever that theme changes. Upload cache revisions
remain in the URL as a separate `v` parameter. Ordinary projects' uploaded images
are returned unchanged for either theme.

Requests with no theme or an unsupported value use `DEFAULT_IMAGE_THEME`.
If the requested collection is missing or empty, the server tries the configured
theme and then the legacy `images/stock-engines` directory. Local and cluster
response ETags include the stock file path, so light and dark variants have
different cache validators; CouchDB ETags reflect the returned image bytes.

For ordinary projects, existing images, including stock copies created by
previous versions, are still served as saved images. This change does not delete
or migrate them. Removing old
copies requires a separate migration that identifies stock files without removing
custom uploads. Build and deploy SEMOSS core, Monolith, and the client together
to enable theme switching. The download route paths and existing callers remain
compatible; the theme query parameter is optional.

### Instance-managed projects

The project download endpoint identifies system projects using
`SystemDefaultEngines.isSystemProject(projectId)`, which includes the registered
platform apps, skills, MCPs, and agents. This uses exact IDs from the same lists
that seed projects at startup. Editable `SYSTEM` tags, project names, `platform__`
prefixes, missing creators, and global visibility do not classify user projects
as instance-managed. Additional managed project types can join these registry
lists without changing the image endpoint.

After the normal session and project permission checks, registered projects
receive `images/system-projects/instance-managed-light.svg` or
`instance-managed-dark.svg` from the SEMOSS base directory. These badges take
precedence over saved images for system projects only, consistently across local,
cluster, and CouchDB deployments. No saved images are deleted or modified, and
ordinary projects and engine provider/type icons keep their existing behavior.

An omitted or unsupported theme uses `DEFAULT_IMAGE_THEME`. If the selected badge
is missing, the resolver tries the configured theme and the other available badge.
If neither badge exists, the endpoint continues through its normal image lookup.
The badge files stay outside the random stock collections so they are never
assigned to user-created projects. They use the same private browser cache policy
and theme-specific ETags as other project images.

Publish SEMOSS core and Monolith together with the two SVG assets to enable this
selection. Existing catalog UI image requests need no frontend changes.

Image responses set `Content-Type` from the returned file or bytes. PNG stock
artwork is served as `image/png`; SVG uploads use `image/svg+xml`. Advertising
SVG in `@Produces` alone is insufficient: a browser's `Accept` header can select
SVG even when the response contains PNG bytes.
Image ETags include a representation version so previously cached responses
with incorrect content types are replaced when the browser revalidates.

When publishing an incremental local build, include newly added classes as well
as changed route classes. In particular, both routes require
`WEB-INF/classes/prerna/web/services/util/CatalogImageResponse.class`. A missing
helper causes `NoClassDefFoundError` and failed image requests, so the UI keeps
its initials fallback. Publish the complete build and reload the application;
clearing the browser cache cannot repair a missing server class.

## Browser caching

Successful engine and project image downloads use:

```http
Cache-Control: private, max-age=300, must-revalidate
ETag: "<image-version>"
Vary: Cookie, Authorization
```

The browser can reuse a fresh image for five minutes without contacting the
server. Once stale, it sends `If-None-Match`; an unchanged image returns
`304 Not Modified` with the same ETag, Cache-Control, and Vary headers and no
image body. This applies to local files, cluster files, and CouchDB responses.
Authentication and resource permissions are checked before any server response,
including a 304. Private caching avoids shared proxy storage, and Vary keeps
browser cache entries separate when session cookies or authorization change.

The UI's `theme` query parameter separates light and dark entries. A successful
upload changes `v`, bypassing the previous cached URL immediately in that browser
session. Other viewers can retain the previous image for the remaining five-minute
freshness window, plus any cluster synchronization delay. These URLs are mutable,
so they are deliberately not marked `immutable` or cached for a year.

To verify a deployed environment, open browser DevTools > Network and leave
**Disable cache** unchecked. Load a catalog image once, then navigate away and
back: the same URL should show a memory/disk cache hit. After five minutes, its
conditional request should return 304 if the image has not changed. Changing
theme should request the other URL once; switching back can reuse its cached
variant. Uploading an image should request a new `v` URL. Reload and hard-refresh
can force revalidation, so use normal navigation for the freshness check.

Inspect the final response headers through your deployed proxy. A proxy that
adds `no-store` or `no-cache`, drops the ETag, or changes query-string handling
can override or defeat this behavior.
