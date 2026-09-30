/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.web.services.util;

import java.io.ByteArrayInputStream;
import java.io.File;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import prerna.util.MimeTypeUtility;

/**
 * Image content types and browser caching for authenticated image downloads.
 */
public final class CatalogImageResponse {

	private static final int MAX_AGE_SECONDS = 300;
	// Re-fetch responses cached before image Content-Type was set explicitly.
	private static final String IMAGE_ETAG_PREFIX = "image-v2-";

	private CatalogImageResponse() {
	}

	/**
	 * Sets the type from the returned image, so a browser's preference for SVG
	 * cannot cause PNG or JPEG bytes to be labeled as SVG during negotiation.
	 * Handles both local/cluster files and CouchDB byte responses.
	 */
	public static Response withContentType(Response response) {
		if (response.getStatus() != Response.Status.OK.getStatusCode() || response.getMediaType() != null) {
			return response;
		}
		String contentType;
		Object entity = response.getEntity();
		if (entity instanceof File file) {
			contentType = MimeTypeUtility.detectMimeType(file);
		} else if (entity instanceof byte[] bytes) {
			contentType = MimeTypeUtility.detectMimeType(new ByteArrayInputStream(bytes), null);
		} else {
			return response;
		}
		return Response.fromResponse(response)
				.type(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : contentType).build();
	}

	/**
	 * Reuses a fresh image for five minutes, then validates its ETag. Call only
	 * after authorizing the resource. Theme and upload revision query parameters
	 * distinguish image URLs; Vary separates browser entries between sessions.
	 * Applies the same cache policy to 200 and 304, preserving other responses.
	 */
	public static Response withBrowserCache(Request request, Response response) {
		EntityTag etag = response.getEntityTag();
		if (response.getStatus() != Response.Status.OK.getStatusCode() || etag == null) {
			return withContentType(response);
		}
		etag = new EntityTag(IMAGE_ETAG_PREFIX + etag.getValue(), etag.isWeak());

		ResponseBuilder builder = request.evaluatePreconditions(etag);
		if (builder == null) {
			builder = Response.fromResponse(withContentType(response));
		} else {
			Response conditional = builder.tag(etag).build();
			if (conditional.getStatus() != Response.Status.NOT_MODIFIED.getStatusCode()) {
				return conditional;
			}
			builder = Response.fromResponse(conditional);
			String vary = response.getHeaderString(HttpHeaders.VARY);
			if (vary != null) {
				builder.header(HttpHeaders.VARY, vary);
			}
		}

		CacheControl cache = new CacheControl();
		cache.setPrivate(true);
		cache.setMaxAge(MAX_AGE_SECONDS);
		cache.setMustRevalidate(true);
		cache.setNoTransform(false);
		return builder.tag(etag).cacheControl(cache).header(HttpHeaders.VARY, "Cookie, Authorization").build();
	}
}
