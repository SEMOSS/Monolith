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
package prerna.semoss.web.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import prerna.semoss.web.github.AdminGitHubService;
import prerna.semoss.web.github.GitHubService;
import prerna.semoss.web.ms.MicrosoftGraphNotificationService;
import prerna.semoss.web.ms.MicrosoftGraphPhotoService;
import prerna.semoss.web.ms.MicrosoftGraphSubscriptionService;
import prerna.semoss.web.services.config.AdminConfigService;
import prerna.semoss.web.services.config.HealthResource;
import prerna.semoss.web.services.config.ServerConfigurationResource;

class ApplicationRegistrationTest {

	private static final List<Application> APPLICATIONS = List.of(new MonolithApplication(), new HealthApplication(),
			new GitHubApplication(), new MicrosoftGraphApplication(), new AdminApplication());

	@Test
	void everyApplicationIsServedUnderItsOwnPath() {
		Map<String, String> paths = new TreeMap<>();
		for (Application application : APPLICATIONS) {
			ApplicationPath path = application.getClass().getAnnotation(ApplicationPath.class);
			assertNotNull(path, application.getClass().getName());
			paths.put(application.getClass().getSimpleName(), path.value());
		}
		assertEquals(Map.of("MonolithApplication", "/api", "HealthApplication", "/health", "GitHubApplication",
				"/github", "MicrosoftGraphApplication", "/msgraph", "AdminApplication", "/adminconfig"), paths);
	}

	@Test
	void theIntegrationsAndProbesLiveOffTheApiServlet() {
		assertEquals(Set.of(GitHubService.class, AdminGitHubService.class), new GitHubApplication().getClasses());
		assertEquals(Set.of(MicrosoftGraphSubscriptionService.class, MicrosoftGraphNotificationService.class,
				MicrosoftGraphPhotoService.class), new MicrosoftGraphApplication().getClasses());
		assertEquals(Set.of(AdminConfigService.class), new AdminApplication().getClasses());
		assertEquals(Set.of(HealthResource.class), new HealthApplication().getClasses());
		assertTrue(new MonolithApplication().getClasses().contains(ServerConfigurationResource.class));
	}

	@Test
	void registersEveryResourceAsAClassBuiltPerRequest() {
		for (Application application : APPLICATIONS) {
			String name = application.getClass().getSimpleName();
			assertTrue(application.getSingletons().isEmpty(), name + " registers no singletons");
			assertTrue(application.getProperties().isEmpty(), name);
			for (Class<?> resource : application.getClasses()) {
				assertTrue(resource.isAnnotationPresent(jakarta.ws.rs.Path.class), resource.getName() + " is a root resource");
				assertTrue(Modifier.isPublic(resource.getModifiers()) && !Modifier.isAbstract(resource.getModifiers()),
						resource.getName() + " can be built");
				assertTrue(Stream.of(resource.getConstructors()).anyMatch(c -> c.getParameterCount() == 0),
						resource.getName() + " has a public no-argument constructor");
			}
		}
	}

	@Test
	void servesEachRootResourceInExactlyOneApplication() throws IOException {
		Map<Class<?>, List<String>> servedBy = new TreeMap<>((a, b) -> a.getName().compareTo(b.getName()));
		for (Application application : APPLICATIONS) {
			for (Class<?> resource : application.getClasses()) {
				servedBy.computeIfAbsent(resource, r -> new ArrayList<>()).add(application.getClass().getSimpleName());
			}
		}
		Set<String> unregistered = new TreeSet<>();
		for (String resource : rootResourcesInSource()) {
			if (servedBy.keySet().stream().noneMatch(c -> c.getName().equals(resource))) {
				unregistered.add(resource);
			}
		}
		assertEquals(Set.of(), unregistered, "every class-level @Path resource is registered");
		servedBy.forEach((resource, applications) -> assertEquals(1, applications.size(),
				resource.getName() + " is served by " + applications));
	}

	/**
	 * The classes under src that carry a class-level {@code @Path}, which is the
	 * only {@code @Path} written at the start of a line.
	 */
	private static Set<String> rootResourcesInSource() throws IOException {
		Path src = Path.of("src");
		Set<String> resources = new TreeSet<>();
		try (Stream<Path> files = Files.walk(src)) {
			for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
				boolean root = Files.readAllLines(file, StandardCharsets.ISO_8859_1).stream()
						.anyMatch(line -> line.startsWith("@Path("));
				if (root) {
					String relative = src.relativize(file).toString();
					resources.add(relative.substring(0, relative.length() - ".java".length()).replace('/', '.')
							.replace('\\', '.'));
				}
			}
		}
		assertTrue(resources.size() > 40, "found " + resources.size());
		return resources;
	}
}
