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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;

class CatalogImageResponseTest {

	private static final byte[] PNG = new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D,
			'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89 };

	@TempDir
	Path tempDir;

	private static Request freshRequest() {
		Request request = mock(Request.class);
		when(request.evaluatePreconditions(any(EntityTag.class))).thenReturn(null);
		return request;
	}

	@Test
	void labelsAFileEntityFromItsBytesNotItsName() throws Exception {
		File file = Files.write(tempDir.resolve("image.svg"), PNG).toFile();

		Response response = CatalogImageResponse.withContentType(Response.ok(file).build());

		assertEquals("image/png", response.getMediaType().toString());
		assertSame(file, response.getEntity());
	}

	@Test
	void labelsAByteArrayEntity() {
		Response response = CatalogImageResponse.withContentType(Response.ok(PNG).build());

		assertEquals("image/png", response.getMediaType().toString());
	}

	@Test
	void leavesTypedFailedAndOtherResponsesAlone() {
		Response typed = Response.ok(PNG).type("image/gif").build();
		Response failed = Response.status(404).entity(PNG).build();
		Response text = Response.ok("not an image").build();

		assertSame(typed, CatalogImageResponse.withContentType(typed));
		assertSame(failed, CatalogImageResponse.withContentType(failed));
		assertSame(text, CatalogImageResponse.withContentType(text));
	}

	@Test
	void fallsBackToOctetStreamForUnknownBytes() {
		Response response = CatalogImageResponse.withContentType(Response.ok(new byte[0]).build());

		assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getMediaType().toString());
	}

	@Test
	void cachesAFreshImagePrivatelyForFiveMinutes() {
		Response original = Response.ok(PNG).tag(new EntityTag("abc")).build();

		Response response = CatalogImageResponse.withBrowserCache(freshRequest(), original);

		assertEquals(200, response.getStatus());
		assertEquals("image-v2-abc", response.getEntityTag().getValue());
		String cacheControl = response.getHeaderString(HttpHeaders.CACHE_CONTROL);
		assertTrue(cacheControl.contains("private"), cacheControl);
		assertTrue(cacheControl.contains("max-age=300"), cacheControl);
		assertTrue(cacheControl.contains("must-revalidate"), cacheControl);
		assertEquals("Cookie, Authorization", response.getHeaderString(HttpHeaders.VARY));
		assertEquals("image/png", response.getMediaType().toString());
	}

	@Test
	void answersNotModifiedWithTheSameCachePolicy() {
		Request request = mock(Request.class);
		when(request.evaluatePreconditions(new EntityTag("image-v2-abc"))).thenReturn(Response.notModified());
		Response original = Response.ok(PNG).tag(new EntityTag("abc")).build();

		Response response = CatalogImageResponse.withBrowserCache(request, original);

		assertEquals(304, response.getStatus());
		assertNull(response.getEntity());
		assertEquals("image-v2-abc", response.getEntityTag().getValue());
		assertTrue(response.getHeaderString(HttpHeaders.CACHE_CONTROL).contains("max-age=300"));
		assertEquals(List.of("Cookie, Authorization"), response.getHeaders().get(HttpHeaders.VARY));
	}

	@Test
	void passesAFailedPreconditionThrough() {
		Request request = mock(Request.class);
		when(request.evaluatePreconditions(any(EntityTag.class)))
				.thenReturn(Response.status(Response.Status.PRECONDITION_FAILED));
		Response original = Response.ok(PNG).tag(new EntityTag("abc")).build();

		Response response = CatalogImageResponse.withBrowserCache(request, original);

		assertEquals(412, response.getStatus());
		assertNull(response.getHeaderString(HttpHeaders.CACHE_CONTROL));
	}

	@Test
	void doesNotCacheAResponseWithoutAnEntityTag() {
		Response original = Response.ok(PNG).build();
		Response failed = Response.status(400).entity("{}").tag(new EntityTag("abc")).build();

		Response response = CatalogImageResponse.withBrowserCache(freshRequest(), original);

		assertNull(response.getHeaderString(HttpHeaders.CACHE_CONTROL));
		assertEquals("image/png", response.getMediaType().toString());
		assertSame(failed, CatalogImageResponse.withBrowserCache(freshRequest(), failed));
	}
}
