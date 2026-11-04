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

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import jakarta.ws.rs.core.Application;
import prerna.openapi.Internal;
import prerna.reactor.AbstractReactor2;
import prerna.reactor.DynamicOutput;
import prerna.reactor.NoInput;

/**
 * Writes the OpenAPI 3.1 document every SEMOSS SDK is generated from.
 * <p>
 * The backend owns the contract: SDKs (TypeScript today, Python later) only read
 * this document. Nothing is listed by hand: what is annotated is published.
 * <ul>
 * <li>Under {@code paths}: every resource method with a swagger-core
 * {@code @Operation(operationId = ...)}, in the resources each servlet's
 * {@link Application} registers.</li>
 * <li>Under the {@code x-semoss-pixels} extension: every concrete
 * {@link AbstractReactor2}, read from its input record and output type.</li>
 * <li>{@code @Hidden} (swagger-core) on a method, resource or reactor leaves it
 * out. {@link Internal} publishes it with {@code "x-internal": true}, which the
 * SDKs mark as not for general use.</li>
 * <li>{@code x-semoss-servlets} names the module each servlet's operations are
 * published under, so every SDK groups them the same way.</li>
 * </ul>
 * The output is canonical: keys are sorted and nothing depends on the time or
 * the machine, so two runs over the same code write the same bytes. Run it with
 * {@code scripts/generate-api}.
 */
public final class OpenApiExporter {

	/**
	 * One servlet from web.xml: its URL prefix, the SDK module its operations are
	 * published under, and the JAX-RS application that registers its resources.
	 */
	record Servlet(String prefix, String module, String application) {
	}

	/** Every RESTEasy servlet in WebContent/WEB-INF/web.xml, checked by the tests. */
	static final List<Servlet> SERVLETS = List.of(
			new Servlet("/api", "monolith", "prerna.semoss.web.app.MonolithApplication"),
			new Servlet("/health", "health", "prerna.semoss.web.app.HealthApplication"),
			new Servlet("/github", "github", "prerna.semoss.web.app.GitHubApplication"),
			new Servlet("/msgraph", "msgraph", "prerna.semoss.web.app.MicrosoftGraphApplication"),
			new Servlet("/token", "token", "prerna.semoss.web.app.TrustedTokenApplication"),
			new Servlet("/adminconfig", "adminConfig", "prerna.semoss.web.app.AdminApplication"));

	/** The packages scanned for reactors. */
	static final List<String> REACTOR_PACKAGES = List.of("prerna");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
	private static final String PIXEL_TRANSPORT = "/api/engine/runPixel";
	private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "delete", "patch");

	private OpenApiExporter() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("Usage: OpenApiExporter <output file>");
			System.exit(2);
		}
		Path output = Path.of(args[0]);
		Path parent = output.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(output, export(), StandardCharsets.UTF_8);
		System.out.println("Wrote " + output);
	}

	/** Builds the document for Monolith's servlets and the reactors in {@link #REACTOR_PACKAGES}. */
	public static String export() throws Exception {
		return export(SERVLETS, REACTOR_PACKAGES);
	}

	/**
	 * Builds and validates the document.
	 *
	 * @param servlets        the servlets whose annotated operations are published
	 * @param reactorPackages the packages scanned for reactors
	 * @return the document as canonical JSON, ending in a newline
	 * @throws IllegalStateException when something annotated is incomplete,
	 *                               ambiguous or not valid OpenAPI 3.1
	 */
	static String export(List<Servlet> servlets, List<String> reactorPackages) throws Exception {
		ObjectNode schemas = NODES.objectNode();
		ObjectNode paths = readRestOperations(servlets, schemas);
		ObjectNode pixels = readReactors(reactorPackages, schemas);

		ObjectNode root = NODES.objectNode();
		root.put("openapi", "3.1.0");
		root.set("info", NODES.objectNode().put("title", "SEMOSS").put("version", "1"));
		ObjectNode server = NODES.objectNode().put("url", "{module}");
		server.set("variables", NODES.objectNode().set("module",
				NODES.objectNode().put("default", "/Monolith").put("description", "The web app's context path")));
		root.set("servers", NODES.arrayNode().add(server));
		root.set("paths", paths);
		root.set("components", NODES.objectNode().set("schemas", schemas));
		ObjectNode servletModules = NODES.objectNode();
		for (Servlet servlet : servlets) {
			servletModules.put(servlet.prefix(), servlet.module());
		}
		root.set("x-semoss-servlets", servletModules);
		root.set("x-semoss-pixels", pixels);

		JsonNode canonical = canonical(root);
		List<String> problems = new ArrayList<>();
		lint(canonical, "", problems);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("The OpenAPI document is not ready:\n  " + String.join("\n  ", problems));
		}

		String json = writer().writeValueAsString(canonical).replace("\r\n", "\n") + "\n";
		validate(json);
		return json;
	}

	/*
	 * REST
	 */

	/** The resource classes a servlet's application registers, by constructing it. */
	static List<Class<?>> resourcesOf(Servlet servlet) throws Exception {
		Application application = (Application) Class.forName(servlet.application()).getDeclaredConstructor()
				.newInstance();
		List<Class<?>> resources = new ArrayList<>();
		for (Object singleton : application.getSingletons()) {
			resources.add(singleton.getClass());
		}
		for (Class<?> c : application.getClasses()) {
			resources.add(c);
		}
		resources.sort(Comparator.comparing(Class::getName));
		return resources;
	}

	/** A published resource method: its {@code @Operation} and where it is declared. */
	private record AnnotatedOperation(Method method, Class<?> resource) {
	}

	private static ObjectNode readRestOperations(List<Servlet> servlets, ObjectNode schemas) throws Exception {
		ObjectNode paths = NODES.objectNode();
		for (Servlet servlet : servlets) {
			// Only resources with an @Operation reach swagger's Reader, so nothing
			// unannotated is ever introspected.
			Map<String, AnnotatedOperation> operations = new TreeMap<>();
			Set<Class<?>> annotated = new LinkedHashSet<>();
			for (Class<?> resource : resourcesOf(servlet)) {
				if (resource.isAnnotationPresent(Hidden.class)) {
					continue;
				}
				for (Method method : resource.getMethods()) {
					Operation operation = method.getAnnotation(Operation.class);
					if (operation == null || method.isAnnotationPresent(Hidden.class)) {
						continue;
					}
					String operationId = operation.operationId();
					if (operationId.isEmpty()) {
						throw new IllegalStateException(resource.getName() + "." + method.getName()
								+ ": @Operation needs an operationId to be published");
					}
					AnnotatedOperation previous = operations.put(operationId, new AnnotatedOperation(method, resource));
					if (previous != null) {
						throw new IllegalStateException("Two operations in " + servlet.prefix() + " are named \""
								+ operationId + "\": " + previous.resource().getName() + "." + previous.method().getName()
								+ " and " + resource.getName() + "." + method.getName());
					}
					annotated.add(resource);
				}
			}
			if (annotated.isEmpty()) {
				continue;
			}

			SwaggerConfiguration configuration = new SwaggerConfiguration().openAPI31(true)
					.openAPI(new OpenAPI(io.swagger.v3.oas.models.SpecVersion.V31));
			OpenAPI read = new Reader(configuration).read(annotated);
			JsonNode tree = Json31.mapper().valueToTree(read);

			Set<String> found = new LinkedHashSet<>();
			for (Iterator<Map.Entry<String, JsonNode>> it = tree.path("paths").fields(); it.hasNext();) {
				Map.Entry<String, JsonNode> pathEntry = it.next();
				String fullPath = joinPath(servlet.prefix(), pathEntry.getKey());
				for (Iterator<Map.Entry<String, JsonNode>> ops = pathEntry.getValue().fields(); ops.hasNext();) {
					Map.Entry<String, JsonNode> op = ops.next();
					if (!HTTP_METHODS.contains(op.getKey())) {
						continue;
					}
					String operationId = op.getValue().path("operationId").asText("");
					AnnotatedOperation annotation = operations.get(operationId);
					if (annotation == null) {
						// a method without @Operation in an annotated resource
						continue;
					}
					found.add(operationId);
					ObjectNode operation = (ObjectNode) op.getValue();
					markInternal(operation, internalOf(annotation.method(), annotation.resource()));
					ObjectNode pathItem = paths.has(fullPath) ? (ObjectNode) paths.get(fullPath)
							: paths.putObject(fullPath);
					if (pathItem.has(op.getKey())) {
						throw new IllegalStateException(op.getKey().toUpperCase() + " " + fullPath + " is declared twice");
					}
					pathItem.set(op.getKey(), operation);
				}
			}

			for (String operationId : operations.keySet()) {
				if (!found.contains(operationId)) {
					AnnotatedOperation missing = operations.get(operationId);
					throw new IllegalStateException(missing.resource().getName() + "." + missing.method().getName()
							+ " has @Operation(operationId = \"" + operationId
							+ "\") but swagger-core did not read it; check its @GET/@POST and @Path");
				}
			}

			for (Iterator<Map.Entry<String, JsonNode>> it = tree.path("components").path("schemas").fields(); it
					.hasNext();) {
				Map.Entry<String, JsonNode> schema = it.next();
				addSchema(schemas, schema.getKey(), schema.getValue());
			}
		}
		return paths;
	}

	/** The {@link Internal} on a method, else on its class, or null. */
	private static Internal internalOf(Method method, Class<?> declaring) {
		Internal internal = method.getAnnotation(Internal.class);
		return internal != null ? internal : declaring.getAnnotation(Internal.class);
	}

	/** Adds {@code x-internal}, and its reason, to an operation or reactor entry. */
	private static void markInternal(ObjectNode node, Internal internal) {
		if (internal == null) {
			return;
		}
		node.put("x-internal", true);
		if (!internal.value().isBlank()) {
			node.put("x-internal-reason", internal.value());
		}
	}

	/** Joins the servlet prefix and a resource path, without a trailing slash. */
	static String joinPath(String prefix, String path) {
		String joined = (prefix + "/" + path).replaceAll("/+", "/");
		return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
	}

	/*
	 * Pixels
	 */

	/** Every concrete AbstractReactor2 in the packages, sorted by class name. */
	static List<Class<?>> findReactors(List<String> packages) throws ClassNotFoundException {
		List<Class<?>> reactors = new ArrayList<>();
		ClassLoader loader = OpenApiExporter.class.getClassLoader();
		try (ScanResult scan = new ClassGraph().enableClassInfo().acceptPackages(packages.toArray(new String[0]))
				.scan()) {
			for (ClassInfo info : scan.getSubclasses(AbstractReactor2.class.getName())) {
				if (info.isAbstract() || info.isInterface()) {
					continue;
				}
				// Loaded without running static initializers.
				Class<?> reactor = Class.forName(info.getName(), false, loader);
				if (!Modifier.isAbstract(reactor.getModifiers())) {
					reactors.add(reactor);
				}
			}
		}
		reactors.sort(Comparator.comparing(Class::getName));
		return reactors;
	}

	private static ObjectNode readReactors(List<String> packages, ObjectNode schemas) throws Exception {
		ObjectNode reactors = NODES.objectNode();
		Map<String, Class<?>> byName = new TreeMap<>();
		for (Class<?> reactorClass : findReactors(packages)) {
			if (reactorClass.isAnnotationPresent(Hidden.class)) {
				continue;
			}
			String name = pixelName(reactorClass);
			Class<?> previous = byName.put(name, reactorClass);
			if (previous != null) {
				throw new IllegalStateException("Two reactors are called " + name + " in Pixel: "
						+ previous.getName() + " and " + reactorClass.getName());
			}
			String description = reactorDescription(reactorClass);

			Class<? extends Record> input = AbstractReactor2.inputType(reactorClass);
			String inputName = "Pixel." + name + ".Input";
			addSchema(schemas, inputName, inputSchema(name, input, schemas));

			String outputName = "Pixel." + name + ".Output";
			addSchema(schemas, outputName, outputSchema(reactorClass, input, schemas));

			ObjectNode reactor = reactors.putObject(name);
			reactor.put("description", description);
			reactor.set("inputSchema", NODES.objectNode().put("$ref", "#/components/schemas/" + inputName));
			reactor.set("outputSchema", NODES.objectNode().put("$ref", "#/components/schemas/" + outputName));
			markInternal(reactor, reactorClass.getAnnotation(Internal.class));
		}

		ObjectNode pixels = NODES.objectNode();
		pixels.put("schemaVersion", 1);
		pixels.set("transport", NODES.objectNode().put("path", PIXEL_TRANSPORT));
		pixels.set("reactors", reactors);
		return pixels;
	}

	/** The name a reactor is called by in Pixel: its class name without "Reactor". */
	static String pixelName(Class<?> reactorClass) {
		String simple = reactorClass.getSimpleName();
		return simple.endsWith("Reactor") ? simple.substring(0, simple.length() - "Reactor".length()) : simple;
	}

	/**
	 * Reads the reactor's description. This is the one place a reactor is
	 * constructed: {@code getReactorDescription()} is an instance method, and an
	 * {@link AbstractReactor2} constructor only reads its input record.
	 */
	private static String reactorDescription(Class<?> reactorClass) throws Exception {
		AbstractReactor2<?, ?> reactor = (AbstractReactor2<?, ?>) reactorClass.getDeclaredConstructor().newInstance();
		String description = reactor.getReactorDescription();
		if (description == null || description.isBlank()) {
			throw new IllegalStateException(reactorClass.getName() + " needs a getReactorDescription()");
		}
		return description;
	}

	private static ObjectNode inputSchema(String name, Class<? extends Record> input, ObjectNode schemas) {
		ObjectNode schema = NODES.objectNode();
		schema.put("type", "object");
		Schema recordSchema = input.getAnnotation(Schema.class);
		if (input == NoInput.class) {
			schema.put("description", name + " takes no inputs");
			return schema;
		}
		schema.put("description", recordSchema != null && !recordSchema.description().isEmpty()
				? recordSchema.description()
				: "Inputs for " + name);

		ObjectNode properties = schema.putObject("properties");
		ArrayNode required = NODES.arrayNode();
		for (RecordComponent component : input.getRecordComponents()) {
			Schema annotation = AbstractReactor2.schemaOf(component);
			if (annotation == null || annotation.description().isEmpty()) {
				throw new IllegalStateException(input.getName() + "." + component.getName()
						+ " needs @Schema(description = \"...\")");
			}
			ObjectNode property = schemaForType(component.getGenericType(), schemas);
			property.put("description", annotation.description());
			properties.set(component.getName(), property);
			if (AbstractReactor2.isRequired(component)) {
				required.add(component.getName());
			}
		}
		if (!required.isEmpty()) {
			schema.set("required", required);
		}
		return schema;
	}

	private static ObjectNode outputSchema(Class<?> reactorClass, Class<? extends Record> input, ObjectNode schemas) {
		Type output = AbstractReactor2.outputType(reactorClass);
		if (AbstractReactor2.isDynamicOutput(output)) {
			DynamicOutput dynamic = reactorClass.getAnnotation(DynamicOutput.class);
			if (dynamic == null || dynamic.value().isBlank()) {
				throw new IllegalStateException(reactorClass.getName() + " needs @DynamicOutput(\"why\")");
			}
			return NODES.objectNode().put("description", "Any value: " + dynamic.value());
		}
		ObjectNode schema = schemaForType(output, schemas);
		Schema annotation = runAnnotation(reactorClass, input);
		if (annotation != null && !annotation.description().isEmpty()) {
			schema.put("description", annotation.description());
		}
		return schema;
	}

	/** An optional {@code @Schema(description)} on {@code run}, describing the output. */
	private static Schema runAnnotation(Class<?> reactorClass, Class<?> input) {
		try {
			Method run = reactorClass.getDeclaredMethod("run", input);
			return run.getAnnotation(Schema.class);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	/** The JSON Schema of a Java type, the same set {@link AbstractReactor2} accepts. */
	static ObjectNode schemaForType(Type type, ObjectNode schemas) {
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
			Type[] arguments = parameterized.getActualTypeArguments();
			if (List.class.isAssignableFrom(raw)) {
				ObjectNode schema = NODES.objectNode().put("type", "array");
				schema.set("items", schemaForType(arguments[0], schemas));
				return schema;
			}
			if (Map.class.isAssignableFrom(raw)) {
				ObjectNode schema = NODES.objectNode().put("type", "object");
				if (arguments[1] == Object.class) {
					schema.put("additionalProperties", true);
				} else {
					schema.set("additionalProperties", schemaForType(arguments[1], schemas));
				}
				return schema;
			}
		}
		if (type instanceof Class<?> c) {
			if (c == String.class) {
				return NODES.objectNode().put("type", "string");
			} else if (c == Integer.class || c == int.class) {
				return NODES.objectNode().put("type", "integer").put("format", "int32");
			} else if (c == Long.class || c == long.class) {
				return NODES.objectNode().put("type", "integer").put("format", "int64");
			} else if (c == Double.class || c == double.class) {
				return NODES.objectNode().put("type", "number").put("format", "double");
			} else if (c == Boolean.class || c == boolean.class) {
				return NODES.objectNode().put("type", "boolean");
			} else if (c.isEnum()) {
				ObjectNode schema = NODES.objectNode().put("type", "string");
				ArrayNode values = schema.putArray("enum");
				for (Object constant : c.getEnumConstants()) {
					values.add(((Enum<?>) constant).name());
				}
				return schema;
			} else if (c == Object.class) {
				return NODES.objectNode();
			} else if (Map.class.isAssignableFrom(c)) {
				return NODES.objectNode().put("type", "object").put("additionalProperties", true);
			} else if (c.isRecord()) {
				ResolvedSchema resolved = ModelConverters.getInstance(true).readAllAsResolvedSchema(c);
				JsonNode tree = Json31.mapper().valueToTree(resolved.schema);
				addSchema(schemas, c.getSimpleName(), tree);
				if (resolved.referencedSchemas != null) {
					resolved.referencedSchemas.forEach((name, schema) -> {
						if (!name.equals(c.getSimpleName())) {
							addSchema(schemas, name, Json31.mapper().valueToTree(schema));
						}
					});
				}
				return NODES.objectNode().put("$ref", "#/components/schemas/" + c.getSimpleName());
			}
		}
		throw new IllegalStateException("No schema for type " + type.getTypeName());
	}

	private static void addSchema(ObjectNode schemas, String name, JsonNode schema) {
		JsonNode existing = schemas.get(name);
		if (existing != null && !existing.equals(schema)) {
			throw new IllegalStateException("Two different schemas are named " + name);
		}
		schemas.set(name, schema);
	}

	/*
	 * Output and checks
	 */

	private static ObjectMapper writer() {
		return Json31.mapper().copy().enable(SerializationFeature.INDENT_OUTPUT);
	}

	/** Sorts object keys, and {@code required} lists, at every level. */
	static JsonNode canonical(JsonNode node) {
		if (node.isObject()) {
			ObjectNode sorted = NODES.objectNode();
			List<String> names = new ArrayList<>();
			node.fieldNames().forEachRemaining(names::add);
			names.sort(null);
			for (String name : names) {
				JsonNode child = canonical(node.get(name));
				if (name.equals("required") && child.isArray()) {
					List<String> values = new ArrayList<>();
					child.forEach(v -> values.add(v.asText()));
					values.sort(null);
					ArrayNode array = NODES.arrayNode();
					values.forEach(array::add);
					child = array;
				}
				sorted.set(name, child);
			}
			return sorted;
		}
		if (node.isArray()) {
			ArrayNode array = NODES.arrayNode();
			node.forEach(child -> array.add(canonical(child)));
			return array;
		}
		return node;
	}

	/** Rejects OpenAPI 3.0 forms and missing documentation that SDK generators require. */
	static void lint(JsonNode node, String where, List<String> problems) {
		if (node.isObject()) {
			if (node.has("nullable")) {
				problems.add(where + ": \"nullable\" is OpenAPI 3.0; use a type array with \"null\"");
			}
			if (node.has("example")) {
				problems.add(where + ": \"example\" is OpenAPI 3.0; use \"examples\"");
			}
			for (String bound : List.of("exclusiveMinimum", "exclusiveMaximum")) {
				if (node.path(bound).isBoolean()) {
					problems.add(where + ": a boolean \"" + bound + "\" is OpenAPI 3.0; use a number");
				}
			}
			JsonNode properties = node.get("properties");
			if (properties != null && properties.isObject()) {
				properties.fields().forEachRemaining(property -> {
					if (!property.getValue().has("description") && !property.getValue().has("$ref")) {
						problems.add(where + ".properties." + property.getKey() + ": needs a description");
					}
				});
			}
			node.fields().forEachRemaining(child -> lint(child.getValue(), where + "." + child.getKey(), problems));
		} else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) {
				lint(node.get(i), where + "[" + i + "]", problems);
			}
		}
	}

	/** Parses the document with swagger-parser, failing on any message it reports. */
	private static void validate(String json) {
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		SwaggerParseResult result = new OpenAPIV3Parser().readContents(json, null, options);
		List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
		if (result.getOpenAPI() == null || !messages.isEmpty()) {
			throw new IllegalStateException("swagger-parser rejected the document:\n  " + String.join("\n  ", messages));
		}
	}

	/** Reads a written document back, for tests. */
	static JsonNode read(String json) throws IOException {
		return Json31.mapper().readTree(json);
	}
}
