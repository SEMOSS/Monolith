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
package prerna.semoss.web.services.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.util.Constants;

class StorageEngineResourceTest {

	/**
	 * Records the pixel each endpoint hands to {@link ResourceUtility#runPixel}
	 * while the rest of {@link ResourceUtility} runs for real.
	 */
	private static final class PixelCapture implements AutoCloseable {

		private final List<String> pixels = new ArrayList<>();
		private final MockedStatic<ResourceUtility> mock;

		private PixelCapture() {
			this.mock = Mockito.mockStatic(ResourceUtility.class, invocation -> {
				if (invocation.getMethod().getName().equals("runPixel")) {
					this.pixels.add(invocation.getArgument(1));
					return Response.ok().build();
				}
				return invocation.callRealMethod();
			});
		}

		@Override
		public void close() {
			this.mock.close();
		}
	}

	private static HttpServletRequest jsonRequest(String json) throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getContentType()).thenReturn("application/json");
		when(request.getReader()).thenReturn(new BufferedReader(new StringReader(json)));
		return request;
	}

	private static HttpServletRequest formRequest(Map<String, String[]> params) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
		when(request.getParameterMap()).thenReturn(params);
		return request;
	}

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static void assertError(Response response, int status, String message) throws IOException {
		assertEquals(status, response.getStatus());
		assertTrue(body(response).contains("\"" + Constants.ERROR_MESSAGE + "\":\"" + message), body(response));
	}

	@Test
	void listsAStoragePathFromAJsonBody() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			Response response = new StorageEngineResource().list(jsonRequest("{\"storagePath\":\"/docs\"}"), "s1");

			assertEquals(200, response.getStatus());
			assertEquals(List.of("ListStoragePath(storage=\"s1\", storagePath=\"/docs\")"), capture.pixels);
		}
	}

	@Test
	void listsPathDetailsFromAFormBody() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			new StorageEngineResource().listDetails(formRequest(Map.of("storagePath", new String[] { "/a b" })),
					"s1");

			assertEquals(List.of("ListStoragePathDetails(storage=\"s1\", storagePath=\"/a b\")"), capture.pixels);
		}
	}

	@Test
	void deletesAStoragePath() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			new StorageEngineResource().delete(jsonRequest("{\"storagePath\":\"/tmp/x.txt\"}"), "s1");

			assertEquals(List.of("DeleteFromStorage(storage=\"s1\", storagePath=\"/tmp/x.txt\")"), capture.pixels);
		}
	}

	@Test
	void keepsQuotesInsideTheStoragePathStringLiteral() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			new StorageEngineResource().list(jsonRequest("{\"storagePath\":\"a\\\") | Evil(\\\"\"}"), "s1");

			assertEquals(List.of("ListStoragePath(storage=\"s1\", storagePath=\"a\\\") | Evil(\\\"\")"),
					capture.pixels);
		}
	}

	@Test
	void requiresAStoragePath() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			StorageEngineResource resource = new StorageEngineResource();

			assertError(resource.list(jsonRequest("{}"), "s1"), 400, "Must pass in 'storagePath' to list");
			assertError(resource.listDetails(jsonRequest("{\"storagePath\":\"  \"}"), "s1"), 400,
					"Must pass in 'storagePath' to list");
			assertError(resource.delete(formRequest(Map.of()), "s1"), 400, "Must pass in 'storagePath' to delete");
			assertTrue(capture.pixels.isEmpty());
		}
	}

	@Test
	void rejectsMalformedJsonWith400() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			StorageEngineResource resource = new StorageEngineResource();

			assertError(resource.list(jsonRequest("{not json"), "s1"), 400, "Invalid request body");
			assertError(resource.listDetails(jsonRequest("[1,2]"), "s1"), 400, "Invalid request body");
			assertError(resource.delete(jsonRequest("{\"storagePath\":"), "s1"), 400, "Invalid request body");
			assertTrue(capture.pixels.isEmpty());
		}
	}
}
