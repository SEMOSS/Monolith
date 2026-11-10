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
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import jakarta.ws.rs.core.Application;
import prerna.engine.api.IEngine;
import prerna.openapi.Internal;
import prerna.openapi.ReactorContract;
import prerna.reactor.IReactor;

/**
 * Writes the OpenAPI 3.1 document every SEMOSS SDK is generated from.
 * <p>
 * The backend owns the contract: SDKs (TypeScript today, Python later) only
 * read this document. Nothing is listed by hand: what is annotated is
 * published.
 * <ul>
 * <li>Under {@code paths}: every resource method with a swagger-core
 * {@code @Operation(operationId = ...)}, in the resources each servlet's
 * {@link Application} registers and the sub-resources their locators return,
 * each under a qualified, document-wide unique {@code operationId} (see
 * {@link #readRestOperations}).</li>
 * <li>Under the {@code x-semoss-reactor} extension: every general reactor that
 * declares something about itself ({@link ReactorContract#isDefined}), with the
 * contract it declares ({@link ReactorContract}). A reactor that declares
 * nothing is left out; one that cannot be read is left out and reported.</li>
 * <li>{@code @Hidden} (swagger-core) on a method, resource or reactor leaves it
 * out. {@link Internal} publishes it with {@code "x-internal": true}, which the
 * SDKs mark as not for general use.</li>
 * <li>Each operation is tagged with its module, the {@code operationId} without
 * its last part, so every tool and SDK groups operations the same way
 * ({@link #tagOperations}).</li>
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

	/**
	 * Every RESTEasy servlet in WebContent/WEB-INF/web.xml, checked by the tests.
	 */
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
		Result result = run(SERVLETS, REACTOR_PACKAGES);
		Files.writeString(output, result.json(), StandardCharsets.UTF_8);
		System.out.println("Wrote " + output);

		Path skipped = output.resolveSibling("skipped.txt");
		Files.writeString(skipped, String.join("\n", result.skipped()) + (result.skipped().isEmpty() ? "" : "\n"),
				StandardCharsets.UTF_8);
		System.out.println("Left out " + result.skipped().size() + " reactor(s) that cannot be read; see " + skipped);
	}

	/**
	 * The document and the reactors left out of it.
	 *
	 * @param skipped one {@code "Name (class): reason"} per reactor, sorted
	 */
	record Result(String json, List<String> skipped) {
	}

	/**
	 * Builds the document for Monolith's servlets and the reactors in
	 * {@link #REACTOR_PACKAGES}.
	 */
	public static String export() throws Exception {
		return run(SERVLETS, REACTOR_PACKAGES).json();
	}

	/** Builds the document for the given servlets and reactor packages. */
	static String export(List<Servlet> servlets, List<String> reactorPackages) throws Exception {
		return run(servlets, reactorPackages).json();
	}

	/**
	 * Builds and validates the document.
	 *
	 * @param servlets        the servlets whose annotated operations are published
	 * @param reactorPackages the packages scanned for reactors
	 * @return the document as canonical JSON, ending in a newline, and the reactors
	 *         left out
	 * @throws IllegalStateException when something annotated is incomplete,
	 *                               ambiguous or not valid OpenAPI 3.1
	 */
	static Result run(List<Servlet> servlets, List<String> reactorPackages) throws Exception {
		ObjectNode schemas = NODES.objectNode();
		ObjectNode paths = readRestOperations(servlets, schemas);
		ArrayNode tags = tagOperations(paths);
		List<String> skipped = new ArrayList<>();
		ObjectNode reactorExtension = readReactors(reactorPackages, schemas, skipped);
		skipped.sort(null);

		ObjectNode root = NODES.objectNode();
		root.put("openapi", "3.1.0");
		root.set("info", NODES.objectNode().put("title", "SEMOSS").put("version", "1"));
		ObjectNode server = NODES.objectNode().put("url", "{contextPath}");
		server.set("variables", NODES.objectNode().set("contextPath",
				NODES.objectNode().put("default", "/Monolith").put("description", "The web app's context path")));
		root.set("servers", NODES.arrayNode().add(server));
		root.set("tags", tags);
		root.set("paths", paths);
		root.set("components", NODES.objectNode().set("schemas", schemas));
		ObjectNode servletModules = NODES.objectNode();
		for (Servlet servlet : servlets) {
			servletModules.put(servlet.prefix(), servlet.module());
		}
		root.set("x-semoss-servlets", servletModules);
		root.set("x-semoss-reactor", reactorExtension);

		JsonNode canonical = canonical(root);
		List<String> problems = new ArrayList<>();
		lint(canonical, "", problems);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("The OpenAPI document is not ready:\n  " + String.join("\n  ", problems));
		}

		String json = writer().writeValueAsString(canonical).replace("\r\n", "\n") + "\n";
		validate(json);
		return new Result(json, List.copyOf(skipped));
	}

	/*
	 * REST
	 */

	/** The module of a qualified {@code operationId}: all of it but its last part. */
	static String moduleOf(String operationId) {
		return operationId.substring(0, operationId.lastIndexOf('.'));
	}

	/**
	 * Tags every operation with its module, the {@code operationId} without its
	 * last part ({@code monolith.auth.project.getProjects} is tagged
	 * {@code monolith.auth.project}), which is how standard tools and every SDK
	 * group operations.
	 *
	 * @return the document's {@code tags}, sorted by name, each describing the
	 *         paths its operations are under
	 * @throws IllegalStateException when an annotation declares its own tags; they
	 *                               come from the path
	 */
	static ArrayNode tagOperations(ObjectNode paths) {
		Map<String, Set<String>> parents = new TreeMap<>();
		for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> path = it.next();
			String parent = path.getKey().substring(0, Math.max(1, path.getKey().lastIndexOf('/')));
			for (Iterator<Map.Entry<String, JsonNode>> ops = path.getValue().fields(); ops.hasNext();) {
				Map.Entry<String, JsonNode> op = ops.next();
				ObjectNode operation = (ObjectNode) op.getValue();
				if (operation.has("tags")) {
					throw new IllegalStateException(op.getKey().toUpperCase() + " " + path.getKey()
							+ ": declares tags, but an operation's tag is its module, from its path");
				}
				String module = moduleOf(operation.path("operationId").asText());
				operation.set("tags", NODES.arrayNode().add(module));
				parents.computeIfAbsent(module, m -> new java.util.TreeSet<>()).add(parent);
			}
		}
		ArrayNode tags = NODES.arrayNode();
		parents.forEach((module, under) -> tags.add(NODES.objectNode().put("name", module)
				.put("description", "Operations under " + String.join(", ", under))));
		return tags;
	}

	/**
	 * The resource classes a servlet's application registers, by constructing it.
	 */
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

	/**
	 * A published resource method: its {@code @Operation} and where it is declared.
	 */
	private record AnnotatedOperation(Method method, Class<?> resource) {
	}

	/** An SDK function or module name. */
	private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

	/** A path parameter, as in {@code e-{engineId}}. */
	private static final java.util.regex.Pattern PATH_PARAMETER = java.util.regex.Pattern.compile("\\{[^}]*\\}");

	/**
	 * The method of a path that keeps the plain name when the path has several: the
	 * POST, else the GET. Every other method adds its own name ({@code Get},
	 * {@code Delete}, {@code Put}, {@code Patch}).
	 */
	static String plainMethod(Set<String> methods) {
		return methods.contains("post") ? "post" : methods.contains("get") ? "get" : null;
	}

	/** The suffix a method's name takes when another method of its path keeps the plain name. */
	static String methodSuffix(String httpMethod, Set<String> methods) {
		if (methods.size() < 2 || httpMethod.equals(plainMethod(methods))) {
			return "";
		}
		return Character.toUpperCase(httpMethod.charAt(0)) + httpMethod.substring(1).toLowerCase();
	}

	/**
	 * Reads every annotated operation, and publishes each under a qualified,
	 * document-wide unique {@code operationId}:
	 * {@code {servlet module}.{module segments}.{name}}, such as
	 * {@code monolith.auth.project.getProjects}.
	 * <ul>
	 * <li>The name is the {@code operationId} the method declares: the SDK function
	 * name. It must be the last path segment when that segment is a name, plus
	 * {@link #methodSuffix} when its path has other methods (a GET beside a POST is
	 * {@code getProjectsGet}). When the last
	 * segment is not a name (a path parameter, or the resource root), the method
	 * chooses one.</li>
	 * <li>The module segments are the path's other segments, each without its path
	 * parameters ({@code e-{engineId}} is {@code e}) and in lower camel case
	 * ({@code browser-sessions} is {@code browserSessions}); a segment that is only a
	 * parameter is left out.</li>
	 * </ul>
	 * Each resource is read on its own, since swagger-core renames an
	 * {@code operationId} it has seen before.
	 */
	private static ObjectNode readRestOperations(List<Servlet> servlets, ObjectNode schemas) throws Exception {
		ObjectNode paths = NODES.objectNode();
		Map<String, String> published = new TreeMap<>();
		for (Servlet servlet : servlets) {
			for (Class<?> resource : resourcesOf(servlet)) {
				if (resource.isAnnotationPresent(Hidden.class)) {
					continue;
				}
				// Only resources with an @Operation reach swagger's Reader, so nothing
				// unannotated is ever introspected.
				Map<String, List<AnnotatedOperation>> operations = new TreeMap<>();
				collectOperations(resource, operations, new LinkedHashSet<>());
				if (operations.isEmpty()) {
					continue;
				}
				readResource(servlet, resource, operations, paths, schemas, published);
			}
		}
		return paths;
	}

	/**
	 * The {@code @Operation} methods of a resource and of the sub-resources its
	 * locators return (a method with {@code @Path} and no HTTP method), which
	 * swagger-core reads as part of the resource.
	 */
	private static void collectOperations(Class<?> resource, Map<String, List<AnnotatedOperation>> operations,
			Set<Class<?>> visited) {
		if (!visited.add(resource) || resource.isAnnotationPresent(Hidden.class)) {
			return;
		}
		for (Method method : resource.getMethods()) {
			if (method.isAnnotationPresent(Hidden.class)) {
				continue;
			}
			Operation operation = method.getAnnotation(Operation.class);
			if (operation == null) {
				if (isLocator(method)) {
					collectOperations(method.getReturnType(), operations, visited);
				}
				continue;
			}
			String name = operation.operationId();
			if (name.isEmpty()) {
				throw new IllegalStateException(resource.getName() + "." + method.getName()
						+ ": @Operation needs an operationId to be published");
			}
			// One name may be used in several modules of a resource; the published,
			// qualified ids are checked for uniqueness instead.
			operations.computeIfAbsent(name, n -> new ArrayList<>()).add(new AnnotatedOperation(method, resource));
		}
	}

	/**
	 * The annotated method swagger-core read as one operation. swagger-core renames
	 * an operationId it has already seen ({@code delete_1}), so a renamed one is
	 * matched by its HTTP method and the end of its path.
	 */
	private static AnnotatedOperation matchOperation(Map<String, List<AnnotatedOperation>> operations, String readId,
			String httpMethod, String path) {
		List<AnnotatedOperation> candidates = operations.get(readId);
		if (candidates == null) {
			String base = readId.replaceFirst("_\\d+$", "");
			candidates = base.equals(readId) ? null : operations.get(base);
		}
		if (candidates == null) {
			return null;
		}
		if (candidates.size() == 1) {
			return candidates.get(0);
		}
		// The candidate whose method path is the longest suffix of the read path:
		// both /models and /v1/models end /model/openai/v1/models.
		String trimmed = path.replaceAll("/+$", "");
		List<AnnotatedOperation> matches = new ArrayList<>();
		int longest = -1;
		for (AnnotatedOperation candidate : candidates) {
			jakarta.ws.rs.Path methodPath = candidate.method().getAnnotation(jakarta.ws.rs.Path.class);
			String suffix = methodPath == null ? "" : ("/" + methodPath.value()).replaceAll("/+", "/").replaceAll("/+$", "");
			if (!httpMethod.equalsIgnoreCase(httpMethodOf(candidate.method())) || !trimmed.endsWith(suffix)) {
				continue;
			}
			if (suffix.length() > longest) {
				matches.clear();
				longest = suffix.length();
			}
			if (suffix.length() == longest) {
				matches.add(candidate);
			}
		}
		if (matches.size() != 1) {
			throw new IllegalStateException(httpMethod.toUpperCase() + " " + path
					+ ": cannot tell which method swagger-core read as \"" + readId + "\"");
		}
		return matches.get(0);
	}

	/** The HTTP method of a resource method, or null. */
	private static String httpMethodOf(Method method) {
		for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
			jakarta.ws.rs.HttpMethod http = annotation.annotationType().getAnnotation(jakarta.ws.rs.HttpMethod.class);
			if (http != null) {
				return http.value();
			}
		}
		return null;
	}

	/** A kebab-case path segment as a module name: {@code browser-sessions} is {@code browserSessions}. */
	static String lowerCamel(String segment) {
		StringBuilder name = new StringBuilder();
		boolean upper = false;
		for (char c : segment.toCharArray()) {
			if (c == '-' || c == '.') {
				upper = name.length() > 0;
			} else {
				name.append(upper ? Character.toUpperCase(c) : c);
				upper = false;
			}
		}
		return name.toString();
	}

	/** Whether a method is a JAX-RS sub-resource locator: {@code @Path} and no HTTP method. */
	static boolean isLocator(Method method) {
		if (!method.isAnnotationPresent(jakarta.ws.rs.Path.class)) {
			return false;
		}
		for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
			if (annotation.annotationType().isAnnotationPresent(jakarta.ws.rs.HttpMethod.class)) {
				return false;
			}
		}
		return true;
	}

	private static void readResource(Servlet servlet, Class<?> resource, Map<String, List<AnnotatedOperation>> operations,
			ObjectNode paths, ObjectNode schemas, Map<String, String> published) {
		SwaggerConfiguration configuration = new SwaggerConfiguration().openAPI31(true)
				.openAPI(new OpenAPI(io.swagger.v3.oas.models.SpecVersion.V31));
		OpenAPI read = new Reader(configuration).read(Set.of(resource));
		JsonNode tree = Json31.mapper().valueToTree(read);

		Set<Method> found = new LinkedHashSet<>();
		for (Iterator<Map.Entry<String, JsonNode>> it = tree.path("paths").fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> pathEntry = it.next();
			String fullPath = joinPath(servlet.prefix(), pathEntry.getKey());
			for (Iterator<Map.Entry<String, JsonNode>> ops = pathEntry.getValue().fields(); ops.hasNext();) {
				Map.Entry<String, JsonNode> op = ops.next();
				if (!HTTP_METHODS.contains(op.getKey())) {
					continue;
				}
				String readId = op.getValue().path("operationId").asText("");
				AnnotatedOperation annotation = matchOperation(operations, readId, op.getKey(), pathEntry.getKey());
				if (annotation == null) {
					// a method without @Operation in this resource
					continue;
				}
				if (!found.add(annotation.method())) {
					throw new IllegalStateException(resource.getName() + "." + annotation.method().getName()
							+ " was read as two operations");
				}
				String name = annotation.method().getAnnotation(Operation.class).operationId();
				String where = op.getKey().toUpperCase() + " " + fullPath + " (" + resource.getName() + "."
						+ annotation.method().getName() + ")";
				Set<String> methods = new java.util.TreeSet<>();
				pathEntry.getValue().fieldNames().forEachRemaining(m -> {
					if (HTTP_METHODS.contains(m)) {
						methods.add(m);
					}
				});
				String qualified = qualifiedId(servlet, fullPath, name, methodSuffix(op.getKey(), methods), where);
				String previous = published.put(qualified, where);
				if (previous != null) {
					throw new IllegalStateException(
							"Two operations are published as \"" + qualified + "\": " + previous + " and " + where);
				}

				ObjectNode operation = (ObjectNode) op.getValue();
				operation.put("operationId", qualified);
				markInternal(operation, internalOf(annotation.method(), annotation.resource()));
				markSdkSkip(operation, where);
				ObjectNode pathItem = paths.has(fullPath) ? (ObjectNode) paths.get(fullPath)
						: paths.putObject(fullPath);
				if (pathItem.has(op.getKey())) {
					throw new IllegalStateException(op.getKey().toUpperCase() + " " + fullPath + " is declared twice");
				}
				pathItem.set(op.getKey(), operation);
			}
		}

		for (Map.Entry<String, List<AnnotatedOperation>> entry : operations.entrySet()) {
			for (AnnotatedOperation missing : entry.getValue()) {
				if (!found.contains(missing.method())) {
					throw new IllegalStateException(missing.resource().getName() + "." + missing.method().getName()
							+ " has @Operation(operationId = \"" + entry.getKey()
							+ "\") but swagger-core did not read it; check its @GET/@POST and @Path");
				}
			}
		}

		for (Iterator<Map.Entry<String, JsonNode>> it = tree.path("components").path("schemas").fields(); it
				.hasNext();) {
			Map.Entry<String, JsonNode> schema = it.next();
			addSchema(schemas, schema.getKey(), schema.getValue());
		}
	}

	/**
	 * The published {@code operationId} of an operation, after checking its name
	 * against its path; see {@link #readRestOperations}.
	 *
	 * @param suffix what the name adds to the last path segment, from
	 *               {@link #methodSuffix}
	 */
	static String qualifiedId(Servlet servlet, String fullPath, String name, String suffix, String where) {
		if (!NAME.matcher(name).matches()) {
			throw new IllegalStateException(where + ": \"" + name + "\" cannot be an operationId; use lower camel case");
		}
		List<String> segments = new ArrayList<>();
		for (String segment : fullPath.substring(servlet.prefix().length()).split("/")) {
			if (!segment.isEmpty()) {
				segments.add(segment);
			}
		}
		String last = segments.isEmpty() ? "" : segments.get(segments.size() - 1);
		if (NAME.matcher(last).matches()) {
			String expected = last + suffix;
			if (!name.equals(expected)) {
				throw new IllegalStateException(where + ": the operationId must be \"" + expected + "\""
						+ (suffix.isEmpty() ? " (the last path segment)"
								: " (the last path segment plus \"" + suffix
										+ "\", since another method of the path keeps the plain name)")
						+ ", got \"" + name + "\"");
			}
		}

		StringBuilder qualified = new StringBuilder(servlet.module());
		for (String segment : segments.subList(0, Math.max(0, segments.size() - 1))) {
			String module = lowerCamel(PATH_PARAMETER.matcher(segment).replaceAll("").replaceAll("^[-_.]+|[-_.]+$", ""));
			if (module.isEmpty()) {
				continue;
			}
			if (!NAME.matcher(module).matches()) {
				throw new IllegalStateException(
						where + ": the path segment \"" + segment + "\" cannot be a module name");
			}
			qualified.append('.').append(module);
		}
		return qualified.append('.').append(name).toString();
	}

	/** The {@link Internal} on a method, else on its class, or null. */
	private static Internal internalOf(Method method, Class<?> declaring) {
		Internal internal = method.getAnnotation(Internal.class);
		return internal != null ? internal : declaring.getAnnotation(Internal.class);
	}

	/**
	 * Adds {@code x-internal}, and its reason, to an operation or reactor entry.
	 */
	private static void markInternal(ObjectNode node, Internal internal) {
		if (internal == null) {
			return;
		}
		node.put("x-internal", true);
		if (!internal.value().isBlank()) {
			node.put("x-internal-reason", internal.value());
		}
	}

	/** Tells SDKs not to generate a call for an operation, with the reason. */
	static final String SDK_SKIP = "x-semoss-sdk-skip";

	/**
	 * Adds {@link #SDK_SKIP} to an operation no SDK should generate a call for, so
	 * every SDK skips the same ones for the same reason. A method declares it for
	 * an endpoint other servers call (a webhook) with
	 * {@code @Operation(extensions = @Extension(properties = @ExtensionProperty(name = "x-semoss-sdk-skip", value = "why")))}.
	 * A browser navigation gets it without declaring it: an operation with no 2xx
	 * response (it only redirects) or whose 2xx responses are all
	 * {@code text/html} pages.
	 */
	static void markSdkSkip(ObjectNode operation, String where) {
		JsonNode declared = operation.get(SDK_SKIP);
		if (declared != null) {
			if (!declared.isTextual() || declared.asText().isBlank()) {
				throw new IllegalStateException(where + ": " + SDK_SKIP + " must be a reason");
			}
			return;
		}
		boolean success = false;
		boolean page = true;
		for (Iterator<Map.Entry<String, JsonNode>> it = operation.path("responses").fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> response = it.next();
			if (!response.getKey().matches("2\\d\\d")) {
				continue;
			}
			success = true;
			JsonNode content = response.getValue().path("content");
			boolean html = content.size() > 0;
			for (Iterator<String> types = content.fieldNames(); types.hasNext();) {
				html &= types.next().startsWith("text/html");
			}
			page &= html;
		}
		if (!success) {
			operation.put(SDK_SKIP, "Browser navigation: it only redirects");
		} else if (page) {
			operation.put(SDK_SKIP, "Browser navigation: it answers with an HTML page");
		}
	}

	/** Joins the servlet prefix and a resource path, without a trailing slash. */
	static String joinPath(String prefix, String path) {
		String joined = (prefix + "/" + path).replaceAll("/+", "/");
		return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
	}

	/*
	 * Reactors
	 */

	/** Every concrete, general reactor in the packages, sorted by class name. */
	static List<Class<?>> findReactors(List<String> packages) throws ClassNotFoundException {
		List<Class<?>> reactors = new ArrayList<>();
		ClassLoader loader = OpenApiExporter.class.getClassLoader();
		try (ScanResult scan = new ClassGraph().enableClassInfo().acceptPackages(packages.toArray(new String[0]))
				.scan()) {
			for (ClassInfo info : scan.getClassesImplementing(IReactor.class.getName())) {
				if (info.isAbstract() || info.isInterface()) {
					continue;
				}
				// Loaded without running static initializers.
				Class<?> reactor = Class.forName(info.getName(), false, loader);
				if (Modifier.isAbstract(reactor.getModifiers()) || IEngine.class.isAssignableFrom(reactor)) {
					continue;
				}
				// Frame-specific versions share a general reactor's name; the frame
				// picks one at runtime, so only the general one is published.
				if (ReactorContract.isGeneral(reactor)) {
					reactors.add(reactor);
				}
			}
		}
		reactors.sort(Comparator.comparing(Class::getName));
		return reactors;
	}

	private static final java.util.regex.Pattern PIXEL_IDENTIFIER = java.util.regex.Pattern
			.compile("^[A-Za-z_][A-Za-z0-9_]*$");

	/**
	 * Reads the contract of every general reactor that declares something about
	 * itself and is not {@code @Hidden}.
	 *
	 * @param skipped collects each reactor left out because it cannot be read, as
	 *                {@code "Name (class): reason"}
	 * @throws IllegalStateException when any reactor's definition is wrong, listing
	 *                               every one with the class whose code to fix
	 */
	private static ObjectNode readReactors(List<String> packages, ObjectNode schemas, List<String> skipped)
			throws Exception {
		// Grouped by Pixel name first, so a name claimed twice is left out even when
		// one claimant declares nothing.
		Map<String, List<Class<?>>> byName = new TreeMap<>();
		for (Class<?> reactorClass : findReactors(packages)) {
			if (!reactorClass.isAnnotationPresent(Hidden.class)) {
				byName.computeIfAbsent(ReactorContract.pixelName(reactorClass), n -> new ArrayList<>())
						.add(reactorClass);
			}
		}

		ObjectNode reactors = NODES.objectNode();
		List<String> invalid = new ArrayList<>();
		for (Map.Entry<String, List<Class<?>>> entry : byName.entrySet()) {
			String name = entry.getKey();
			List<Class<?>> claimants = entry.getValue();
			boolean defined = false;
			for (Class<?> claimant : claimants) {
				try {
					defined |= ReactorContract.isDefined(claimant);
				} catch (LinkageError e) {
					skipped.add(name + " (" + claimant.getName() + "): " + describe(e));
				}
			}
			if (!defined) {
				continue;
			}
			if (claimants.size() > 1) {
				List<String> names = claimants.stream().map(Class::getName).sorted().toList();
				skipped.add(name + " (" + String.join(", ", names) + "): two classes have this Pixel name");
				continue;
			}

			Class<?> reactorClass = claimants.get(0);
			ReactorContract contract;
			try {
				contract = ReactorContract.of(reactorClass);
			} catch (IllegalStateException e) {
				invalid.add(name + " (" + reactorClass.getName() + "): " + e.getMessage());
				continue;
			} catch (Exception | LinkageError e) {
				Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
						? ite.getCause()
						: e;
				skipped.add(name + " (" + reactorClass.getName() + "): " + describe(cause));
				continue;
			}
			String problem = identifierProblem(contract);
			if (problem != null) {
				skipped.add(
						name + " (" + reactorClass.getName() + "): " + describe(new IllegalArgumentException(problem)));
				continue;
			}

			String inputName = "Reactor." + name + ".Input";
			addSchema(schemas, inputName, inputSchema(contract, schemas));
			String outputName = "Reactor." + name + ".Output";
			addSchema(schemas, outputName, outputSchema(contract, schemas));

			ObjectNode reactor = reactors.putObject(name);
			reactor.put("description", contract.description());
			reactor.set("inputSchema", NODES.objectNode().put("$ref", "#/components/schemas/" + inputName));
			reactor.set("outputSchema", NODES.objectNode().put("$ref", "#/components/schemas/" + outputName));
			if (contract.inferred()) {
				reactor.put("x-semoss-inferred", true);
			}
			markInternal(reactor, contract.internal());
		}

		if (!invalid.isEmpty()) {
			throw new IllegalStateException(
					invalid.size() + " reactor definition(s) are wrong; each names the class whose code to fix:\n  "
							+ String.join("\n  ", invalid));
		}

		ObjectNode extension = NODES.objectNode();
		extension.put("schemaVersion", 1);
		extension.set("transport", NODES.objectNode().put("path", PIXEL_TRANSPORT));
		extension.set("reactors", reactors);
		return extension;
	}

	/**
	 * Why a contract cannot be published as is, or null. Names and keys are written
	 * into Pixel unescaped.
	 */
	private static String identifierProblem(ReactorContract contract) {
		if (!PIXEL_IDENTIFIER.matcher(contract.name()).matches()) {
			return "\"" + contract.name() + "\" is not an identifier";
		}
		for (ReactorContract.Input input : contract.inputs()) {
			if (!PIXEL_IDENTIFIER.matcher(input.name()).matches()) {
				return "its key \"" + input.name() + "\" is not an identifier";
			}
		}
		return null;
	}

	/** A short, stable reason for a skipped reactor. */
	private static String describe(Throwable cause) {
		String message = cause.getMessage();
		String first = message == null ? "" : message.lines().findFirst().orElse("");
		return cause.getClass().getSimpleName() + (first.isBlank() ? "" : ": " + first);
	}

	private static ObjectNode inputSchema(ReactorContract contract, ObjectNode schemas) {
		ObjectNode schema = NODES.objectNode();
		schema.put("type", "object");
		if (contract.inputs().isEmpty()) {
			schema.put("description", contract.name() + (contract.noKeys() ? " takes no inputs" : " declares no keys"));
			return schema;
		}
		schema.put("description", "Inputs for " + contract.name());
		ObjectNode properties = schema.putObject("properties");
		ArrayNode required = NODES.arrayNode();
		for (ReactorContract.Input input : contract.inputs()) {
			ObjectNode property = schemaFor(input.type(), input.items(), schemas);
			property.put("description", input.description());
			properties.set(input.name(), property);
			if (input.required()) {
				required.add(input.name());
			}
		}
		if (!required.isEmpty()) {
			schema.set("required", required);
		}
		return schema;
	}

	private static ObjectNode outputSchema(ReactorContract contract, ObjectNode schemas) throws IOException {
		ReactorContract.Output output = contract.output();
		if (output == null) {
			return NODES.objectNode().put("description", "Not declared by the reactor");
		}
		if (output.schema() != null) {
			return (ObjectNode) Json31.mapper().readTree(output.schema());
		}
		ObjectNode schema = schemaFor(output.type(), output.items(), schemas);
		if (!output.description().isBlank()) {
			schema.put("description", output.description());
		}
		return schema;
	}

	/**
	 * The JSON Schema of a declared or inferred type; a {@code List} uses its item
	 * type.
	 */
	private static ObjectNode schemaFor(Class<?> type, Class<?> items, ObjectNode schemas) {
		if (List.class.isAssignableFrom(type)) {
			ObjectNode schema = NODES.objectNode().put("type", "array");
			if (items != Void.class) {
				schema.set("items", schemaForType(items, schemas));
			}
			return schema;
		}
		return schemaForType(type, schemas);
	}

	/** The JSON Schema of a Java type. */
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

	/**
	 * Rejects OpenAPI 3.0 forms and missing documentation that SDK generators
	 * require.
	 */
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

	/**
	 * Parses the document with swagger-parser, failing on any message it reports.
	 */
	private static void validate(String json) {
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		SwaggerParseResult result = new OpenAPIV3Parser().readContents(json, null, options);
		List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
		if (result.getOpenAPI() == null || !messages.isEmpty()) {
			throw new IllegalStateException(
					"swagger-parser rejected the document:\n  " + String.join("\n  ", messages));
		}
	}

	/** Reads a written document back, for tests. */
	static JsonNode read(String json) throws IOException {
		return Json31.mapper().readTree(json);
	}
}
