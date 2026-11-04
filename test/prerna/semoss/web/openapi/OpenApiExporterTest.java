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
package prerna.semoss.web.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;

import prerna.semoss.web.openapi.OpenApiExporter.Servlet;

class OpenApiExporterTest {

	private static final List<Servlet> FIXTURE_SERVLETS = List
			.of(new Servlet("/fx", "fx", "openapi.fixtures.FixtureApplication"));
	private static final List<String> FIXTURE_REACTORS = List.of("openapi.fixtures");

	@Test
	void writesTheSameBytesEveryTime() throws Exception {
		assertEquals(OpenApiExporter.export(), OpenApiExporter.export());
	}

	@Test
	void publishesWhatMonolithAndSemossAnnotate() throws Exception {
		JsonNode doc = OpenApiExporter.read(OpenApiExporter.export());

		assertEquals("3.1.0", doc.path("openapi").asText());
		assertEquals(List.of("/api/config"), names(doc.path("paths")));
		JsonNode config = doc.path("paths").path("/api/config").path("get");
		assertEquals("config", config.path("operationId").asText());
		assertEquals("#/components/schemas/ServerConfig", config.path("responses").path("200").path("content")
				.path("application/json").path("schema").path("$ref").asText());
		assertFalse(config.has("x-internal"));

		JsonNode serverConfig = doc.path("components").path("schemas").path("ServerConfig");
		assertTrue(serverConfig.path("properties").has("file-limit"), "wire names are kept");
		assertTrue(contains(serverConfig.path("required"), "csrf"));
		assertFalse(contains(serverConfig.path("required"), "primaryLogin"), "keys that can be left out are optional");

		JsonNode pixels = doc.path("x-semoss-pixels");
		assertEquals(1, pixels.path("schemaVersion").asInt());
		assertEquals("/api/engine/runPixel", pixels.path("transport").path("path").asText());
		assertEquals(List.of("Echo", "GetCurrentInsightId", "StopPixelExecution"), names(pixels.path("reactors")));

		JsonNode schemas = doc.path("components").path("schemas");
		JsonNode stopInput = schemas.path("Pixel.StopPixelExecution.Input");
		assertEquals("string", stopInput.path("properties").path("id").path("type").asText());
		assertTrue(contains(stopInput.path("required"), "id"));
		assertFalse(schemas.path("Pixel.GetCurrentInsightId.Input").has("properties"));
		assertFalse(contains(schemas.path("Pixel.Echo.Input").path("required"), "value"));
		assertFalse(schemas.path("Pixel.Echo.Output").has("type"), "a dynamic output is any value");

		Map<String, String> modules = new TreeMap<>();
		doc.path("x-semoss-servlets").fields().forEachRemaining(e -> modules.put(e.getKey(), e.getValue().asText()));
		assertEquals("monolith", modules.get("/api"));
		assertEquals(OpenApiExporter.SERVLETS.size(), modules.size());
	}

	@Test
	void publishesAnnotatedOperationsAndLeavesOutHiddenAndUnannotatedOnes() throws Exception {
		JsonNode doc = OpenApiExporter.read(OpenApiExporter.export(FIXTURE_SERVLETS, FIXTURE_REACTORS));

		assertEquals(List.of("/fx/things/admin", "/fx/things/list"), names(doc.path("paths")));

		JsonNode list = doc.path("paths").path("/fx/things/list").path("get");
		assertFalse(list.has("x-internal"));

		JsonNode admin = doc.path("paths").path("/fx/things/admin").path("get");
		assertTrue(admin.path("x-internal").asBoolean());
		assertEquals("Admin console only", admin.path("x-internal-reason").asText());
	}

	@Test
	void publishesReactorsLeavingOutHiddenOnesAndMarkingInternalOnes() throws Exception {
		JsonNode doc = OpenApiExporter.read(OpenApiExporter.export(FIXTURE_SERVLETS, FIXTURE_REACTORS));
		JsonNode reactors = doc.path("x-semoss-pixels").path("reactors");

		assertEquals(List.of("InternalFixture", "VisibleFixture"), names(reactors));
		assertFalse(reactors.path("VisibleFixture").has("x-internal"));
		assertTrue(reactors.path("InternalFixture").path("x-internal").asBoolean());
		assertEquals("Testing only", reactors.path("InternalFixture").path("x-internal-reason").asText());
	}

	@Test
	void rejectsTwoOperationsWithTheSameIdInOneServlet() {
		List<Servlet> servlets = List.of(new Servlet("/dup", "dup", "openapi.fixtures.DuplicateApplication"));

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> OpenApiExporter.export(servlets, FIXTURE_REACTORS));
		assertTrue(error.getMessage().contains("Two operations in /dup are named \"same\""), error.getMessage());
	}

	@Test
	void constructsEveryServletApplication() throws Exception {
		for (Servlet servlet : OpenApiExporter.SERVLETS) {
			assertFalse(OpenApiExporter.resourcesOf(servlet).isEmpty(), servlet.application() + " registers resources");
		}
	}

	@Test
	void servletsMatchWebXml() throws Exception {
		Map<String, String> mapped = applicationsByPrefix(Path.of("WebContent/WEB-INF/web.xml"));
		Map<String, String> declared = new TreeMap<>();
		for (Servlet servlet : OpenApiExporter.SERVLETS) {
			declared.put(servlet.prefix(), servlet.application());
		}
		assertEquals(new TreeMap<>(mapped), declared, "OpenApiExporter.SERVLETS lists every RESTEasy servlet in web.xml");
	}

	@Test
	void joinsPathsWithoutTrailingSlashes() {
		assertEquals("/api/config", OpenApiExporter.joinPath("/api", "/config/"));
		assertEquals("/health", OpenApiExporter.joinPath("/health", "/"));
	}

	@Test
	void rejectsOpenApi30Forms() throws Exception {
		JsonNode schema = OpenApiExporter.read("{\"properties\":{\"a\":{\"type\":\"string\",\"nullable\":true}}}");
		List<String> problems = new ArrayList<>();
		OpenApiExporter.lint(schema, "", problems);
		assertEquals(2, problems.size(), problems.toString());
	}

	/** Each servlet's url-pattern prefix mapped to its jakarta.ws.rs.Application. */
	private static Map<String, String> applicationsByPrefix(Path webXml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		Document document = factory.newDocumentBuilder().parse(Files.newInputStream(webXml));

		Map<String, String> applicationByServlet = new HashMap<>();
		NodeList servlets = document.getElementsByTagName("servlet");
		for (int i = 0; i < servlets.getLength(); i++) {
			Element servlet = (Element) servlets.item(i);
			String name = text(servlet, "servlet-name");
			NodeList params = servlet.getElementsByTagName("init-param");
			for (int j = 0; j < params.getLength(); j++) {
				Element param = (Element) params.item(j);
				if ("jakarta.ws.rs.Application".equals(text(param, "param-name"))) {
					applicationByServlet.put(name, text(param, "param-value"));
				}
			}
		}

		Map<String, String> byPrefix = new HashMap<>();
		NodeList mappings = document.getElementsByTagName("servlet-mapping");
		for (int i = 0; i < mappings.getLength(); i++) {
			Element mapping = (Element) mappings.item(i);
			String application = applicationByServlet.get(text(mapping, "servlet-name"));
			String pattern = text(mapping, "url-pattern");
			if (application != null && pattern.endsWith("/*")) {
				byPrefix.put(pattern.substring(0, pattern.length() - 2), application);
			}
		}
		return byPrefix;
	}

	private static String text(Element element, String tag) {
		NodeList nodes = element.getElementsByTagName(tag);
		return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
	}

	private static boolean contains(JsonNode array, String value) {
		for (JsonNode item : array) {
			if (item.asText().equals(value)) {
				return true;
			}
		}
		return false;
	}

	private static List<String> names(JsonNode object) {
		List<String> names = new ArrayList<>();
		object.fieldNames().forEachRemaining(names::add);
		return names;
	}
}
