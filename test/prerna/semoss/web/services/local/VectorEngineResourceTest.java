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

class VectorEngineResourceTest {

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

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static void assertError(Response response, int status, String message) throws IOException {
		assertEquals(status, response.getStatus());
		String body = body(response);
		assertTrue(body.contains("\"" + Constants.ERROR_MESSAGE + "\":\"" + message), body);
	}

	@Test
	void runsAVectorQueryWithItsOptionalFields() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			Response response = new VectorEngineResource().query(
					jsonRequest("{\"command\":\"find cats\",\"limit\":5,\"paramValues\":{\"k\":\"v\"}}"), "v1");

			assertEquals(200, response.getStatus());
			assertEquals(
					List.of("VectorDatabaseQuery(engine=\"v1\", command=\"find cats\", limit=5, paramValues={\"k\":\"v\"})"),
					capture.pixels);
		}
	}

	@Test
	void sendsAFormLimitAsAString() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			HttpServletRequest request = mock(HttpServletRequest.class);
			when(request.getParameterMap())
					.thenReturn(Map.of("command", new String[] { "find" }, "limit", new String[] { "5" }));

			new VectorEngineResource().query(request, "v1");

			assertEquals(List.of("VectorDatabaseQuery(engine=\"v1\", command=\"find\", limit=\"5\")"), capture.pixels);
		}
	}

	@Test
	void requiresACommandForAVectorQuery() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			VectorEngineResource resource = new VectorEngineResource();

			assertError(resource.query(jsonRequest("{\"limit\":5}"), "v1"), 400, "Must pass in 'command'");
			assertError(resource.query(jsonRequest("{\"command\":\"\"}"), "v1"), 400, "Must pass in 'command'");
			assertError(resource.query(jsonRequest("{"), "v1"), 400, "Invalid request body");
			assertTrue(capture.pixels.isEmpty());
		}
	}

	@Test
	void listsDocumentsWithOrWithoutParamValues() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			VectorEngineResource resource = new VectorEngineResource();

			resource.listDocuments(jsonRequest(""), "v1");
			resource.listDocuments(jsonRequest("{\"paramValues\":{\"collection\":\"c\"}}"), "v1");

			assertEquals(List.of("ListDocumentsInVectorDatabase(engine=\"v1\")",
					"ListDocumentsInVectorDatabase(engine=\"v1\", paramValues={\"collection\":\"c\"})"), capture.pixels);
		}
	}

	@Test
	void removesTheNamedDocuments() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			new VectorEngineResource().removeDocument(jsonRequest("{\"fileNames\":[\"a.pdf\",\"b.pdf\"]}"), "v1");

			assertEquals(List.of("RemoveDocumentFromVectorDatabase(engine=\"v1\", fileNames=[\"a.pdf\",\"b.pdf\"])"),
					capture.pixels);
		}
	}

	@Test
	void requiresFileNamesToRemove() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			VectorEngineResource resource = new VectorEngineResource();

			assertError(resource.removeDocument(jsonRequest("{}"), "v1"), 400, "Must pass in 'fileNames'");
			assertError(resource.listDocuments(jsonRequest("not json"), "v1"), 400, "Invalid request body");
			assertTrue(capture.pixels.isEmpty());
		}
	}
}
