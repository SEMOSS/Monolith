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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.util.Constants;
import prerna.util.SystemEngineRegistry;
import prerna.util.Utility;
import prerna.web.conf.DBLoader;

class HealthResourceTest {

	private final HealthResource resource = new HealthResource();

	@Test
	void livenessIsUpAndNotCached() {
		Response response = resource.liveness();

		assertEquals(200, response.getStatus());
		assertEquals("UP", json(response).get("status").getAsString());
		assertEquals("private", response.getHeaderString("Cache-Control"));
	}

	@Test
	void readinessFollowsStartup() {
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class)) {
			Response starting = resource.readiness();
			assertEquals(503, starting.getStatus());
			assertEquals("NOT_READY", json(starting).get("status").getAsString());
			assertFalse(json(starting).get("startupComplete").getAsBoolean());

			loader.when(DBLoader::isStartupComplete).thenReturn(true);
			Response ready = resource.readiness();
			assertEquals(200, ready.getStatus());
			assertEquals("READY", json(ready).get("status").getAsString());
			assertEquals("private", ready.getHeaderString("Cache-Control"));
		}
	}

	@Test
	void healthIsUpWhenEveryRequiredAndEnabledDatabaseIsConnected() {
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class);
				MockedStatic<SystemEngineRegistry> registry = mockStatic(SystemEngineRegistry.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class)) {
			loader.when(DBLoader::isStartupComplete).thenReturn(true);
			loader.when(DBLoader::isStartupSuccess).thenReturn(true);
			registry.when(SystemEngineRegistry::isLocalMasterDbLoaded).thenReturn(true);
			registry.when(SystemEngineRegistry::isSecurityDbLoaded).thenReturn(true);
			registry.when(SystemEngineRegistry::isSchedulerDbLoaded).thenReturn(true);

			Response response = resource.health();

			assertEquals(200, response.getStatus());
			JsonObject body = json(response);
			assertEquals("UP", body.get("status").getAsString());
			assertTrue(body.get("startupComplete").getAsBoolean());
			assertTrue(body.get("startupSuccess").getAsBoolean());
			JsonObject resources = body.getAsJsonObject("resources");
			assertEquals("UP", resources.get(Constants.LOCAL_MASTER_DB).getAsString());
			assertEquals("UP", resources.get(Constants.SECURITY_DB).getAsString());
			assertEquals("UP", resources.get(Constants.SCHEDULER_DB).getAsString());
			for (String disabled : new String[] { Constants.USER_TRACKING_DB, Constants.AUDIT_LOGS_DB,
					Constants.MODEL_INFERENCE_LOGS_DB, Constants.NOTIFICATION_DB }) {
				assertEquals("DISABLED", resources.get(disabled).getAsString(), disabled);
			}
		}
	}

	@Test
	void healthIsDownWhenARequiredDatabaseIsMissing() {
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class);
				MockedStatic<SystemEngineRegistry> registry = mockStatic(SystemEngineRegistry.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class)) {
			registry.when(SystemEngineRegistry::isLocalMasterDbLoaded).thenReturn(true);
			utility.when(Utility::schedulerForceDisable).thenReturn(true);

			Response response = resource.health();

			assertEquals(503, response.getStatus());
			JsonObject body = json(response);
			assertEquals("DOWN", body.get("status").getAsString());
			assertEquals("DOWN", body.getAsJsonObject("resources").get(Constants.SECURITY_DB).getAsString());
			assertEquals("DISABLED", body.getAsJsonObject("resources").get(Constants.SCHEDULER_DB).getAsString());
		}
	}

	@Test
	void anEnabledFeatureCountsAgainstHealthOnlyOnceItIsSwitchedOn() {
		try (MockedStatic<DBLoader> loader = mockStatic(DBLoader.class);
				MockedStatic<SystemEngineRegistry> registry = mockStatic(SystemEngineRegistry.class);
				MockedStatic<Utility> utility = mockStatic(Utility.class)) {
			registry.when(SystemEngineRegistry::isLocalMasterDbLoaded).thenReturn(true);
			registry.when(SystemEngineRegistry::isSecurityDbLoaded).thenReturn(true);
			utility.when(Utility::schedulerForceDisable).thenReturn(true);
			assertEquals(200, resource.health().getStatus());

			utility.when(Utility::isNotificationDatabaseEnabled).thenReturn(true);
			Response response = resource.health();
			assertEquals(503, response.getStatus());
			assertEquals("DOWN",
					json(response).getAsJsonObject("resources").get(Constants.NOTIFICATION_DB).getAsString());
		}
	}

	private static JsonObject json(Response response) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			((StreamingOutput) response.getEntity()).write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return JsonParser.parseString(out.toString(StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
