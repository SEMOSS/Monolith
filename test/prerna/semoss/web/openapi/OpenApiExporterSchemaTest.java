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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

class OpenApiExporterSchemaTest {

	private enum Color {
		RED, GREEN
	}

	record Inner(int x) {
	}

	record Point(String label, Inner inner) {
	}

	@SuppressWarnings("unused")
	private static final class Generics {
		List<String> names;
		Map<String, Integer> counts;
		Map<String, Object> anything;
		List<Map<String, Long>> rows;
	}

	@TempDir
	Path temp;

	@Test
	void describesEachJavaTypeAReactorCanDeclare() {
		ObjectNode schemas = JsonNodeFactory.instance.objectNode();

		assertEquals("{\"type\":\"string\"}", schema(String.class, schemas));
		assertEquals("{\"type\":\"integer\",\"format\":\"int32\"}", schema(int.class, schemas));
		assertEquals("{\"type\":\"integer\",\"format\":\"int32\"}", schema(Integer.class, schemas));
		assertEquals("{\"type\":\"integer\",\"format\":\"int64\"}", schema(Long.class, schemas));
		assertEquals("{\"type\":\"number\",\"format\":\"double\"}", schema(double.class, schemas));
		assertEquals("{\"type\":\"boolean\"}", schema(Boolean.class, schemas));
		assertEquals("{\"type\":\"string\",\"enum\":[\"RED\",\"GREEN\"]}", schema(Color.class, schemas));
		assertEquals("{}", schema(Object.class, schemas), "any value");
		assertEquals("{\"type\":\"object\",\"additionalProperties\":true}", schema(Map.class, schemas));
		assertEquals(0, schemas.size(), "none of these needs a component");
	}

	@Test
	void describesGenericListsAndMapsByTheirArguments() throws Exception {
		ObjectNode schemas = JsonNodeFactory.instance.objectNode();

		assertEquals("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}", schema(generic("names"), schemas));
		assertEquals("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"integer\",\"format\":\"int32\"}}",
				schema(generic("counts"), schemas));
		assertEquals("{\"type\":\"object\",\"additionalProperties\":true}", schema(generic("anything"), schemas));
		assertEquals("{\"type\":\"array\",\"items\":{\"type\":\"object\",\"additionalProperties\":"
				+ "{\"type\":\"integer\",\"format\":\"int64\"}}}", schema(generic("rows"), schemas));
	}

	@Test
	void publishesARecordAsAComponentWithTheRecordsItUses() {
		ObjectNode schemas = JsonNodeFactory.instance.objectNode();

		JsonNode reference = OpenApiExporter.schemaForType(Point.class, schemas);

		assertEquals("#/components/schemas/Point", reference.path("$ref").asText());
		assertEquals("string", schemas.path("Point").path("properties").path("label").path("type").asText());
		assertEquals("#/components/schemas/Inner",
				schemas.path("Point").path("properties").path("inner").path("$ref").asText());
		assertEquals("integer", schemas.path("Inner").path("properties").path("x").path("type").asText());
		assertEquals(reference, OpenApiExporter.schemaForType(Point.class, schemas), "the same record twice is fine");
	}

	@Test
	void refusesATypeItCannotDescribe() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> OpenApiExporter.schemaForType(Date.class, JsonNodeFactory.instance.objectNode()));
		assertEquals("No schema for type java.util.Date", error.getMessage());
	}

	@Test
	void sortsKeysAndRequiredListsButKeepsOtherArraysInOrder() throws Exception {
		JsonNode node = OpenApiExporter.read("{\"b\":1,\"a\":{\"required\":[\"z\",\"y\"],\"d\":[3,1,2],\"c\":true}}");

		assertEquals("{\"a\":{\"c\":true,\"d\":[3,1,2],\"required\":[\"y\",\"z\"]},\"b\":1}",
				OpenApiExporter.canonical(node).toString());
	}

	@Test
	void flagsWhatSdkGeneratorsCannotUse() throws Exception {
		JsonNode schema = OpenApiExporter.read("{\"properties\":{\"good\":{\"type\":\"string\",\"description\":\"ok\"},"
				+ "\"linked\":{\"$ref\":\"#/components/schemas/X\"},\"bare\":{\"type\":\"string\"},"
				+ "\"old\":{\"description\":\"d\",\"example\":1,\"exclusiveMinimum\":true},"
				+ "\"bound\":{\"description\":\"d\",\"exclusiveMaximum\":5}}}");
		List<String> problems = new ArrayList<>();

		OpenApiExporter.lint(schema, "#", problems);

		assertEquals(List.of("#.properties.bare: needs a description",
				"#.properties.old: \"example\" is OpenAPI 3.0; use \"examples\"",
				"#.properties.old: a boolean \"exclusiveMinimum\" is OpenAPI 3.0; use a number"), problems);
	}

	@Test
	void joinsAPrefixAndAPathWithSingleSlashes() {
		assertEquals("/api/x/y", OpenApiExporter.joinPath("/api/", "//x//y/"));
		assertEquals("/", OpenApiExporter.joinPath("/", "/"));
		assertEquals("/github/webhook", OpenApiExporter.joinPath("/github", "webhook"));
	}

	@Test
	void mainWritesTheDocumentAndTheSkippedReactorsBesideIt() throws Exception {
		Path output = temp.resolve("generated/openapi.json");

		OpenApiExporter.main(new String[] { output.toString() });

		String json = Files.readString(output, StandardCharsets.UTF_8);
		assertEquals(OpenApiExporter.export(), json);
		List<String> skipped = OpenApiExporter.run(OpenApiExporter.SERVLETS, OpenApiExporter.REACTOR_PACKAGES)
				.skipped();
		String expected = skipped.isEmpty() ? "" : String.join("\n", skipped) + "\n";
		assertEquals(expected, Files.readString(temp.resolve("generated/skipped.txt"), StandardCharsets.UTF_8));
		assertTrue(json.endsWith("}\n"));
	}

	private static String schema(Type type, ObjectNode schemas) {
		return OpenApiExporter.schemaForType(type, schemas).toString();
	}

	private static Type generic(String field) throws NoSuchFieldException {
		return Generics.class.getDeclaredField(field).getGenericType();
	}
}
