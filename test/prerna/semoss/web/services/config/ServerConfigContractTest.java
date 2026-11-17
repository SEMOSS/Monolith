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
package prerna.semoss.web.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Keeps {@link ServerConfig}, which documents {@code GET /api/config} for the
 * SDKs, in step with the map {@link ServerConfigurationResource} actually
 * builds. The map is assembled from many settings at runtime, so this compares
 * the keys the resource puts with the record's wire names.
 */
class ServerConfigContractTest {

	private static final Pattern PUT = Pattern.compile("(?:loadConfig|myConfiguration)\\.put\\(\"([^\"]+)\"");

	@Test
	void documentsEveryKeyTheResourcePuts() throws Exception {
		String source = Files.readString(
				Path.of("src/prerna/semoss/web/services/config/ServerConfigurationResource.java"),
				StandardCharsets.ISO_8859_1);
		Set<String> put = new TreeSet<>();
		for (String line : source.split("\\R")) {
			if (line.trim().startsWith("//")) {
				continue; // a commented-out put is not sent
			}
			Matcher matcher = PUT.matcher(line);
			while (matcher.find()) {
				put.add(matcher.group(1));
			}
		}

		Set<String> documented = new TreeSet<>();
		for (RecordComponent component : ServerConfig.class.getRecordComponents()) {
			JsonProperty property = component.getAccessor().getAnnotation(JsonProperty.class);
			if (property == null) {
				try {
					property = ServerConfig.class.getDeclaredField(component.getName())
							.getAnnotation(JsonProperty.class);
				} catch (NoSuchFieldException e) {
					property = null;
				}
			}
			documented.add(property != null ? property.value() : component.getName());
		}

		assertEquals(put, documented);
	}
}
