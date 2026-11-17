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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.engine.api.IRDBMSEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.query.querystruct.SelectQueryStruct;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;
import prerna.util.SystemEngineRegistry;

class AdminConfigServiceTest {

	private static final String LOGIN_REDIRECT = "https://semoss.example.com/SemossWeb/#!/login";

	private final AdminConfigService service = new AdminConfigService();

	@Test
	void refusesOnceAnyUserExists() throws Exception {
		try (Statics statics = new Statics(true)) {
			Response response = service.setInitialAdmins(request("[\"intruder\"]"), mock(HttpServletResponse.class));

			assertEquals(400, response.getStatus());
			assertEquals("Initial admin has already been set", json(response).get(Constants.ERROR_MESSAGE).getAsString());
			statics.update.verifyNoInteractions();
			verify(statics.wrapper).close();
		}
	}

	@Test
	void refusesWhenItCannotTellWhetherAUserExists() throws Exception {
		try (Statics statics = new Statics(false)) {
			when(statics.manager.getRawWrapper(any(IRDBMSEngine.class), any(SelectQueryStruct.class)))
					.thenThrow(new IllegalStateException("security db is down"));

			Response response = service.setInitialAdmins(request("[\"intruder\"]"), mock(HttpServletResponse.class));

			assertEquals(400, response.getStatus());
			assertTrue(json(response).get(Constants.ERROR_MESSAGE).getAsString().startsWith("Error occurred"));
			statics.update.verifyNoInteractions();
		}
	}

	@Test
	void makesEveryGivenIdAnAdminOnAFreshInstance() throws Exception {
		try (Statics statics = new Statics(false)) {
			Response response = service.setInitialAdmins(request("[\"alice\", \"bob\"]"),
					mock(HttpServletResponse.class));

			assertEquals(200, response.getStatus());
			JsonObject body = json(response);
			assertTrue(body.get("success").getAsBoolean());
			assertEquals(LOGIN_REDIRECT, body.get("redirectUrl").getAsString());
			for (String id : new String[] { "alice", "bob" }) {
				statics.update.verify(() -> SecurityUpdateUtils.registerUser(id, null, null, null, null, null, null,
						null, true, true, true, null));
			}
			verify(statics.wrapper).close();
		}
	}

	@Test
	void registersNobodyWithoutIds() throws Exception {
		try (Statics statics = new Statics(false)) {
			Response response = service.setInitialAdmins(request(""), mock(HttpServletResponse.class));

			assertEquals("Need to send valid ids", json(response).get(Constants.ERROR_MESSAGE).getAsString());
			statics.update.verify(() -> SecurityUpdateUtils.registerUser(anyString(), any(), any(), any(), any(),
					any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any()), never());
		}
	}

	/**
	 * The security database answering whether a user exists, the admin
	 * registration and the login redirect, opened together and closed together.
	 */
	private static final class Statics implements AutoCloseable {

		private final MockedStatic<SystemEngineRegistry> registry = mockStatic(SystemEngineRegistry.class);
		private final MockedStatic<WrapperManager> wrappers = mockStatic(WrapperManager.class);
		private final MockedStatic<SecurityUpdateUtils> update = mockStatic(SecurityUpdateUtils.class);
		private final MockedStatic<SocialPropertiesUtil> social = mockStatic(SocialPropertiesUtil.class);
		private final WrapperManager manager = mock(WrapperManager.class);
		private final IRawSelectWrapper wrapper = mock(IRawSelectWrapper.class);

		private Statics(boolean hasUser) throws Exception {
			IRDBMSEngine securityDb = mock(IRDBMSEngine.class);
			registry.when(SystemEngineRegistry::getSecurityDb).thenReturn(securityDb);
			wrappers.when(WrapperManager::getInstance).thenReturn(manager);
			when(manager.getRawWrapper(any(IRDBMSEngine.class), any(SelectQueryStruct.class))).thenReturn(wrapper);
			when(wrapper.hasNext()).thenReturn(hasUser);
			SocialPropertiesUtil properties = mock(SocialPropertiesUtil.class);
			when(properties.getLoginRedirect()).thenReturn(LOGIN_REDIRECT);
			social.when(SocialPropertiesUtil::getInstance).thenReturn(properties);
		}

		@Override
		public void close() {
			social.close();
			update.close();
			wrappers.close();
			registry.close();
		}
	}

	private static HttpServletRequest request(String ids) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getParameter("ids")).thenReturn(ids);
		return req;
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
