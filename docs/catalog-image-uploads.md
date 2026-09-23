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

When a project or engine has no saved image, the download route serves a shared
stock image directly from the configured stock collection. It does not copy that
fallback into the resource's version folder or upload it to cluster image storage.
The CouchDB project/database fallback likewise returns stock bytes without saving
a per-resource attachment. Uploaded images continue to take precedence.

Cluster downloads share a successful cloud-folder refresh for 30 seconds, so
cards without custom images do not each trigger another folder pull. Local
uploads take precedence immediately; a new upload from another node may take
up to 30 seconds to appear on a node that is currently using a stock fallback.

Selection uses the existing deterministic key: the resource name for local
version paths, the resource ID for cluster image paths, and the partition/name
for CouchDB. The selection stays stable while that key, the configured theme,
and the stock collection stay unchanged. No per-resource reference file or
database record is required. `DEFAULT_IMAGE_THEME` selects the light or dark
stock collection, with `images/stock-engines` as the legacy fallback directory.

Existing images, including stock copies created by previous versions, are still
served as saved images. This change does not delete or migrate them. Removing old
copies requires a separate migration that identifies stock files without removing
custom uploads. Build and deploy both SEMOSS core and Monolith for this behavior;
the SDK and frontend download URLs are unchanged.
