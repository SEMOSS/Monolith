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

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.util.Constants;

class FunctionEngineResourceTest {

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
	void executesWithTheMapOfInputs() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			Response response = new FunctionEngineResource()
					.execute(jsonRequest("{\"map\":{\"city\":\"Paris\",\"days\":3}}"), "f1");

			assertEquals(200, response.getStatus());
			assertEquals(List.of("ExecuteFunctionEngine(engine=\"f1\", map={\"city\":\"Paris\",\"days\":3})"),
					capture.pixels);
		}
	}

	@Test
	void acceptsInputsAsTheMapAndPrefersMap() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			FunctionEngineResource resource = new FunctionEngineResource();

			resource.execute(jsonRequest("{\"inputs\":{\"a\":1}}"), "f1");
			resource.execute(jsonRequest("{\"map\":{\"a\":1},\"inputs\":{\"b\":2}}"), "f1");

			assertEquals(List.of("ExecuteFunctionEngine(engine=\"f1\", map={\"a\":1})",
					"ExecuteFunctionEngine(engine=\"f1\", map={\"a\":1})"), capture.pixels);
		}
	}

	@Test
	void executesWithoutInputs() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			new FunctionEngineResource().execute(jsonRequest("{}"), "f1");

			assertEquals(List.of("ExecuteFunctionEngine(engine=\"f1\")"), capture.pixels);
		}
	}

	@Test
	void rejectsMalformedJsonWith400() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			assertError(new FunctionEngineResource().execute(jsonRequest("{\"map\":"), "f1"), 400,
					"Invalid request body");
			assertTrue(capture.pixels.isEmpty());
		}
	}

	@Test
	void asksForTheDefinitionOfTheSanitizedEngine() throws IOException {
		try (PixelCapture capture = new PixelCapture()) {
			new FunctionEngineResource().getDefinition(mock(HttpServletRequest.class), "<script>x</script>f1");

			assertEquals(1, capture.pixels.size());
			assertTrue(capture.pixels.get(0).endsWith("(engine=\"f1\")"), capture.pixels.get(0));
		}
	}
}
