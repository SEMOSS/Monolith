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
	void writesTheSameBytesAndSkipsTheSameReactorsEveryTime() throws Exception {
		OpenApiExporter.Result first = OpenApiExporter.run(OpenApiExporter.SERVLETS, OpenApiExporter.REACTOR_PACKAGES);
		OpenApiExporter.Result second = OpenApiExporter.run(OpenApiExporter.SERVLETS, OpenApiExporter.REACTOR_PACKAGES);
		assertEquals(first.json(), second.json());
		assertEquals(first.skipped(), second.skipped());
	}

	@Test
	void publishesWhatMonolithAndSemossDefine() throws Exception {
		JsonNode doc = OpenApiExporter.read(OpenApiExporter.export());

		assertEquals("3.1.0", doc.path("openapi").asText());
		JsonNode config = doc.path("paths").path("/api/config").path("get");
		assertEquals("monolith.config", config.path("operationId").asText());
		assertEquals("#/components/schemas/ServerConfig", config.path("responses").path("200").path("content")
				.path("application/json").path("schema").path("$ref").asText());
		assertFalse(config.has("x-internal"));

		JsonNode serverConfig = doc.path("components").path("schemas").path("ServerConfig");
		assertTrue(serverConfig.path("properties").has("file-limit"), "wire names are kept");
		assertTrue(contains(serverConfig.path("required"), "csrf"));
		assertFalse(contains(serverConfig.path("required"), "primaryLogin"), "keys that can be left out are optional");

		JsonNode reactorExtension = doc.path("x-semoss-reactor");
		assertEquals(1, reactorExtension.path("schemaVersion").asInt());
		assertEquals("/api/engine/runPixel", reactorExtension.path("transport").path("path").asText());
		List<String> reactors = names(reactorExtension.path("reactors"));
		assertTrue(reactors.size() > 500,
				"the general reactors that declare metadata are published, got " + reactors.size());
		assertTrue(reactors.containsAll(List.of("Echo", "GetCurrentInsightId", "StopPixelExecution")));

		// the pilot reactors keep the types their annotations pin
		JsonNode schemas = doc.path("components").path("schemas");
		JsonNode stopInput = schemas.path("Reactor.StopPixelExecution.Input");
		assertEquals("string", stopInput.path("properties").path("id").path("type").asText());
		assertTrue(contains(stopInput.path("required"), "id"));
		assertEquals("string", schemas.path("Reactor.StopPixelExecution.Output").path("type").asText());
		assertFalse(schemas.path("Reactor.GetCurrentInsightId.Input").has("properties"));
		assertEquals("string", schemas.path("Reactor.GetCurrentInsightId.Output").path("type").asText());
		JsonNode echoValue = schemas.path("Reactor.Echo.Input").path("properties").path("value");
		assertFalse(echoValue.has("type"), "Echo's value is any value");
		assertFalse(contains(schemas.path("Reactor.Echo.Input").path("required"), "value"));
		assertFalse(schemas.path("Reactor.Echo.Output").has("type"), "an undeclared output is any value");

		List<String> operationIds = new ArrayList<>();
		doc.path("paths").forEach(item -> item.forEach(operation -> operationIds.add(operation.path("operationId").asText())));
		assertEquals(operationIds.size(), new java.util.HashSet<>(operationIds).size(), "operationIds are unique");
		for (String operationId : operationIds) {
			assertTrue(operationId.matches("^[A-Za-z]+(\\.[A-Za-z_][A-Za-z0-9_]*)+$"),
					"published operationIds are qualified by module: " + operationId);
		}

		// every operation is tagged with its module, and each tag is listed once
		List<String> listed = new ArrayList<>();
		doc.path("tags").forEach(tag -> {
			listed.add(tag.path("name").asText());
			assertTrue(tag.path("description").asText().startsWith("Operations under /"), tag.toString());
		});
		assertEquals(listed.stream().distinct().sorted().toList(), listed, "tags are listed once, sorted by name");
		java.util.Set<String> used = new java.util.TreeSet<>();
		doc.path("paths").forEach(item -> item.forEach(operation -> {
			JsonNode operationTags = operation.path("tags");
			assertEquals(1, operationTags.size(), operation.path("operationId").asText() + " has one tag");
			assertEquals(OpenApiExporter.moduleOf(operation.path("operationId").asText()), operationTags.get(0).asText());
			used.add(operationTags.get(0).asText());
		}));
		assertEquals(List.copyOf(used), listed, "the document lists exactly the tags its operations use");
		assertEquals("monolith", doc.path("paths").path("/api/config").path("get").path("tags").get(0).asText());

		JsonNode server = doc.path("servers").get(0);
		assertEquals("{contextPath}", server.path("url").asText());
		assertEquals("/Monolith", server.path("variables").path("contextPath").path("default").asText());

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
	void readsWhatEachReactorDefines() throws Exception {
		OpenApiExporter.Result result = OpenApiExporter.run(FIXTURE_SERVLETS, FIXTURE_REACTORS);
		JsonNode doc = OpenApiExporter.read(result.json());
		JsonNode reactors = doc.path("x-semoss-reactor").path("reactors");
		JsonNode schemas = doc.path("components").path("schemas");

		// undefined, hidden, frame-specific, unreadable and twice-named reactors are
		// left out
		assertEquals(List.of("DefinedFixture", "InferredFixture", "InternalFixture", "NoKeysFixture", "RowsFixture"),
				names(reactors));

		JsonNode inferred = schemas.path("Reactor.InferredFixture.Input");
		assertTrue(reactors.path("InferredFixture").path("x-semoss-inferred").asBoolean());
		assertEquals("string", inferred.path("properties").path("name").path("type").asText());
		assertEquals("A name", inferred.path("properties").path("name").path("description").asText());
		assertEquals("integer", inferred.path("properties").path("limit").path("type").asText());
		assertTrue(contains(inferred.path("required"), "name"));
		assertFalse(contains(inferred.path("required"), "limit"));
		assertEquals("InferredFixture reactor", reactors.path("InferredFixture").path("description").asText());
		assertEquals("Not declared by the reactor",
				schemas.path("Reactor.InferredFixture.Output").path("description").asText());

		JsonNode defined = schemas.path("Reactor.DefinedFixture.Input").path("properties").path("tags");
		assertFalse(reactors.path("DefinedFixture").has("x-semoss-inferred"));
		assertEquals("array", defined.path("type").asText());
		assertEquals("string", defined.path("items").path("type").asText());
		assertEquals("Some tags", defined.path("description").asText());
		assertEquals("integer", schemas.path("Reactor.DefinedFixture.Output").path("type").asText());
		assertEquals("Counts tags", reactors.path("DefinedFixture").path("description").asText());

		assertFalse(reactors.path("NoKeysFixture").has("x-semoss-inferred"), "noKeys() declares the empty input");
		assertFalse(schemas.path("Reactor.NoKeysFixture.Input").has("properties"));
		assertEquals("NoKeysFixture takes no inputs",
				schemas.path("Reactor.NoKeysFixture.Input").path("description").asText());

		JsonNode rows = schemas.path("Reactor.RowsFixture.Output");
		assertEquals("array", rows.path("type").asText(), "a JSON Schema output is published as given");
		assertEquals("Some rows", rows.path("description").asText());
		assertEquals("string", rows.path("items").path("properties").path("id").path("type").asText());

		assertTrue(reactors.path("InternalFixture").path("x-internal").asBoolean());
		assertEquals("Testing only", reactors.path("InternalFixture").path("x-internal-reason").asText());
	}

	@Test
	void reportsTheReactorsItLeavesOut() throws Exception {
		List<String> skipped = OpenApiExporter.run(FIXTURE_SERVLETS, FIXTURE_REACTORS).skipped();

		assertEquals(2, skipped.size(), skipped.toString());
		assertTrue(skipped.get(0).startsWith("BrokenFixture (openapi.fixtures.FixtureReactors$BrokenFixtureReactor): "
				+ "IllegalStateException: needs a running server"), skipped.get(0));
		assertTrue(skipped.get(1).startsWith("Twin (") && skipped.get(1).endsWith("two classes have this Pixel name"),
				skipped.get(1));
		assertFalse(skipped.toString().contains("UndefinedFixture"), "a reactor that declares nothing is not reported");
	}

	@Test
	void failsListingEveryInvalidReactorDefinition() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> OpenApiExporter.run(FIXTURE_SERVLETS, List.of("openapi.badfixtures")));
		String message = error.getMessage();

		assertTrue(message.startsWith("2 reactor definition(s) are wrong"), message);
		assertTrue(message.contains("BadDefinition (openapi.badfixtures.BadDefinitionReactor): "
				+ "openapi.badfixtures.BadDefinitionReactor describes the key \"missing\""), message);
		assertTrue(
				message.contains("BadChild (openapi.badfixtures.BadChildReactor): "
						+ "openapi.badfixtures.BadParentReactor describes the key \"shared\""),
				"a parent's mistake names the parent: " + message);
	}

	@Test
	void qualifiesEachOperationIdByItsModule() throws Exception {
		List<Servlet> servlets = List.of(new Servlet("/nm", "nm", "openapi.fixtures.NamingApplication"));
		JsonNode paths = OpenApiExporter.read(OpenApiExporter.export(servlets, FIXTURE_REACTORS)).path("paths");

		assertEquals("nm.project.getProjects", paths.path("/nm/project/getProjects").path("post").path("operationId").asText());
		assertEquals("nm.project.getProjectsGet", paths.path("/nm/project/getProjects").path("get").path("operationId").asText(),
				"a GET whose path has a POST adds Get");
		assertEquals("nm.admin.project.getProjects",
				paths.path("/nm/admin/project/getProjects").path("post").path("operationId").asText(),
				"the same name in another module is its own operation");
		assertEquals("nm.e.type", paths.path("/nm/e-{engineId}/type").path("get").path("operationId").asText(),
				"a segment keeps its literal part without the parameter");
		assertEquals("nm.e.byName", paths.path("/nm/e-{engineId}/{name}").path("get").path("operationId").asText(),
				"a parameter as the last segment takes the chosen name");
		assertEquals("nm.project.sub.list", paths.path("/nm/project/sub/list").path("get").path("operationId").asText(),
				"operations of a sub-resource are published");
		assertEquals("nm.browserSessions.steps",
				paths.path("/nm/browser-sessions/{id}/steps").path("get").path("operationId").asText(),
				"a kebab-case segment is a lower camel module");
		assertEquals("nm.browserSessions.images.engine.delete",
				paths.path("/nm/browser-sessions/{id}/images/engine/delete").path("post").path("operationId").asText());
		assertEquals("nm.browserSessions.images.project.delete",
				paths.path("/nm/browser-sessions/{id}/images/project/delete").path("post").path("operationId").asText(),
				"one resource may use a name in two modules");

		assertEquals("nm.project", paths.path("/nm/project/getProjects").path("get").path("tags").get(0).asText(),
				"an operation is tagged with its module");
		assertEquals("nm.project.sub", paths.path("/nm/project/sub/list").path("get").path("tags").get(0).asText());
		assertEquals("nm.e", paths.path("/nm/e-{engineId}/{name}").path("get").path("tags").get(0).asText());
	}

	@Test
	void rejectsAnOperationThatDeclaresItsOwnTags() {
		List<Servlet> servlets = List.of(new Servlet("/tg", "tg", "openapi.fixtures.TaggedApplication"));

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> OpenApiExporter.export(servlets, FIXTURE_REACTORS));
		assertTrue(error.getMessage().contains("GET /tg/tagged/list: declares tags"), error.getMessage());
	}

	@Test
	void describesEachTagByThePathsItsOperationsAreUnder() throws Exception {
		List<Servlet> servlets = List.of(new Servlet("/nm", "nm", "openapi.fixtures.NamingApplication"));
		JsonNode tags = OpenApiExporter.read(OpenApiExporter.export(servlets, FIXTURE_REACTORS)).path("tags");

		Map<String, String> descriptions = new TreeMap<>();
		tags.forEach(tag -> descriptions.put(tag.path("name").asText(), tag.path("description").asText()));
		assertEquals("Operations under /nm/project", descriptions.get("nm.project"));
		assertEquals("Operations under /nm/e-{engineId}", descriptions.get("nm.e"));
		assertEquals("Operations under /nm/browser-sessions/{id}", descriptions.get("nm.browserSessions"));
	}

	@Test
	void rejectsTwoOperationsPublishedUnderOneId() {
		List<Servlet> servlets = List.of(new Servlet("/dup", "dup", "openapi.fixtures.DuplicateApplication"));

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> OpenApiExporter.export(servlets, FIXTURE_REACTORS));
		assertTrue(error.getMessage().contains("Two operations are published as \"dup.twice.same\""), error.getMessage());
	}

	@Test
	void rejectsAnOperationIdThatIsNotTheLastPathSegment() {
		List<Servlet> servlets = List.of(new Servlet("/mm", "mm", "openapi.fixtures.MismatchApplication"));

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> OpenApiExporter.export(servlets, FIXTURE_REACTORS));
		assertTrue(error.getMessage().contains("the operationId must be \"list\""), error.getMessage());
	}

	@Test
	void namesEveryMethodButThePlainOneWithASuffix() {
		Servlet servlet = new Servlet("/api", "monolith", "unused");
		assertEquals("Get", OpenApiExporter.methodSuffix("get", java.util.Set.of("get", "post")));
		assertEquals("", OpenApiExporter.methodSuffix("post", java.util.Set.of("get", "post")));
		assertEquals("", OpenApiExporter.methodSuffix("get", java.util.Set.of("get", "delete")),
				"without a POST the GET keeps the plain name");
		assertEquals("Delete", OpenApiExporter.methodSuffix("delete", java.util.Set.of("get", "delete")));
		assertEquals("", OpenApiExporter.methodSuffix("delete", java.util.Set.of("delete")));

		assertEquals("monolith.auth.project.getProjectsGet",
				OpenApiExporter.qualifiedId(servlet, "/api/auth/project/getProjects", "getProjectsGet", "Get", "GET"));
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> OpenApiExporter.qualifiedId(servlet, "/api/auth/project/getProjects", "getProjects", "Get", "GET"));
		assertTrue(error.getMessage().contains("\"getProjectsGet\""), error.getMessage());
	}

	@Test
	void marksWhatNoSdkShouldCall() throws Exception {
		com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		java.util.function.Function<String, com.fasterxml.jackson.databind.node.ObjectNode> operation = json -> {
			try {
				return (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};

		var redirect = operation.apply("{\"responses\":{\"302\":{\"description\":\"Away\"}}}");
		OpenApiExporter.markSdkSkip(redirect, "GET /redirect");
		assertEquals("Browser navigation: it only redirects", redirect.path(OpenApiExporter.SDK_SKIP).asText());

		var page = operation.apply("{\"responses\":{\"200\":{\"content\":{\"text/html\":{}}}}}");
		OpenApiExporter.markSdkSkip(page, "GET /page");
		assertEquals("Browser navigation: it answers with an HTML page", page.path(OpenApiExporter.SDK_SKIP).asText());

		var json = operation.apply("{\"responses\":{\"200\":{\"content\":{\"application/json\":{}}}}}");
		OpenApiExporter.markSdkSkip(json, "GET /json");
		assertFalse(json.has(OpenApiExporter.SDK_SKIP));

		var empty = operation.apply("{\"responses\":{\"204\":{\"description\":\"Done\"}}}");
		OpenApiExporter.markSdkSkip(empty, "POST /empty");
		assertFalse(empty.has(OpenApiExporter.SDK_SKIP), "a 2xx without a body is a call that returns nothing");

		var declared = operation.apply(
				"{\"x-semoss-sdk-skip\":\"Webhook\",\"responses\":{\"200\":{\"content\":{\"application/json\":{}}}}}");
		OpenApiExporter.markSdkSkip(declared, "POST /webhook");
		assertEquals("Webhook", declared.path(OpenApiExporter.SDK_SKIP).asText());

		var blank = operation.apply("{\"x-semoss-sdk-skip\":\" \",\"responses\":{}}");
		assertThrows(IllegalStateException.class, () -> OpenApiExporter.markSdkSkip(blank, "POST /blank"));
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
		assertEquals(new TreeMap<>(mapped), declared,
				"OpenApiExporter.SERVLETS lists every RESTEasy servlet in web.xml");
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

	/**
	 * Each servlet's url-pattern prefix mapped to its jakarta.ws.rs.Application.
	 */
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
