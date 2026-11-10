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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.HttpMethod;
import prerna.semoss.web.openapi.OpenApiExporter.Servlet;

/**
 * Every REST method a servlet serves is either published or left out on purpose,
 * so nothing reaches a client without being in the OpenAPI document.
 */
class OpenApiCoverageTest {

	@Test
	void everyRestMethodIsPublishedOrHidden() throws Exception {
		Set<String> missing = new TreeSet<>();
		Set<Class<?>> visited = new LinkedHashSet<>();
		for (Servlet servlet : OpenApiExporter.SERVLETS) {
			for (Class<?> resource : OpenApiExporter.resourcesOf(servlet)) {
				check(resource, visited, missing);
			}
		}
		assertTrue(missing.isEmpty(), missing.size()
				+ " REST method(s) have neither @Operation nor @Hidden; see docs/openapi.md:\n  "
				+ String.join("\n  ", missing));
	}

	private static void check(Class<?> resource, Set<Class<?>> visited, Set<String> missing) {
		if (!visited.add(resource) || resource.isAnnotationPresent(Hidden.class)) {
			return;
		}
		for (Method method : resource.getMethods()) {
			if (method.isAnnotationPresent(Hidden.class)) {
				continue;
			}
			if (OpenApiExporter.isLocator(method)) {
				check(method.getReturnType(), visited, missing);
			} else if (httpMethod(method) != null && !method.isAnnotationPresent(Operation.class)) {
				missing.add(httpMethod(method) + " " + resource.getName() + "." + method.getName());
			}
		}
	}

	private static String httpMethod(Method method) {
		for (Annotation annotation : method.getAnnotations()) {
			HttpMethod http = annotation.annotationType().getAnnotation(HttpMethod.class);
			if (http != null) {
				return http.value();
			}
		}
		return null;
	}
}
