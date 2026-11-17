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
package prerna.semoss.web.services.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Vector;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityInsightUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.project.api.IProject;
import prerna.util.AssetUtility;
import prerna.util.Constants;
import prerna.util.Utility;

class ExecuteInsightResourceTest {

	private static User user() {
		User user = new User();
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		user.setAccessToken(token);
		return user;
	}

	private static HttpServletRequest request(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(request.getSession(false)).thenReturn(session);
		return request;
	}

	private static Map<?, ?> json(Object response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) ((Response) response).getEntity()).write(out);
		return new Gson().fromJson(out.toString(StandardCharsets.UTF_8), Map.class);
	}

	private static Object field(RunInsight runner, String name) throws Exception {
		Field field = RunInsight.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(runner);
	}

	private static final class Security implements AutoCloseable {
		final MockedStatic<SecurityInsightUtils> insights = mockStatic(SecurityInsightUtils.class);
		final MockedStatic<Utility> utility = mockStatic(Utility.class);
		final MockedStatic<ClusterUtil> cluster = mockStatic(ClusterUtil.class);
		final MockedStatic<AssetUtility> assets = mockStatic(AssetUtility.class);
		final IProject project = mock(IProject.class);
		final Insight insight = mock(Insight.class);

		Security(User user, boolean canView) {
			insights.when(() -> SecurityInsightUtils.userCanViewInsight(user, "p1", "r1")).thenReturn(canView);
			utility.when(() -> Utility.getProject("p1")).thenReturn(project);
			when(project.getProjectName()).thenReturn("Project One");
			when(insight.getInsightId()).thenReturn("exec-insight");
		}

		@Override
		public void close() {
			InsightStore.getInstance().remove("exec-insight");
			assets.close();
			cluster.close();
			utility.close();
			insights.close();
		}
	}

	@Test
	void rejectsARequestWithoutASessionWith400() throws Exception {
		Object response = new ExecuteInsightResource().generateInsight(mock(HttpServletRequest.class), "p1", "r1");
		assertEquals(400, ((Response) response).getStatus());
		assertEquals("Invalid session to retrieve insight data", json(response).get(Constants.ERROR_MESSAGE));
	}

	@Test
	void rejectsASessionWithoutAUserWith401() throws Exception {
		Object response = new ExecuteInsightResource().generateInsight(request(null), "p1", "r1");
		assertEquals(401, ((Response) response).getStatus());
		assertEquals("User session is invalid", json(response).get("error"));
	}

	@Test
	void rejectsAUserWhoCannotViewTheInsight() throws Exception {
		User user = user();
		try (Security security = new Security(user, false)) {
			Object response = new ExecuteInsightResource().generateInsight(request(user), "p1", "r1");

			assertEquals("User does not have access to this insight", json(response).get("error"));
			security.insights.verify(() -> SecurityInsightUtils.getInsight(anyString(), anyString()), never());
			security.utility.verify(() -> Utility.getProject(anyString()), never());
		}
	}

	@Test
	void refusesAProjectNameInPlaceOfItsId() throws Exception {
		User user = user();
		try (Security security = new Security(user, true)) {
			Object response = new ExecuteInsightResource().generateInsight(request(user), "Project One", "r1");

			assertEquals(401, ((Response) response).getStatus());
			assertEquals("User does not have access to this insight", json(response).get("error"));
			security.utility.verify(() -> Utility.getProject(anyString()), never());
		}
	}

	@Test
	void failsForAProjectThatIsNotLoaded() {
		User user = user();
		try (Security security = new Security(user, true)) {
			security.utility.when(() -> Utility.getProject("p1")).thenReturn(null);
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> new ExecuteInsightResource().generateInsight(request(user), "p1", "r1"));
			assertEquals("Cannot find project = p1", e.getMessage());
		}
	}

	@Test
	void loadsTheSavedInsightRerunsItAndHandsItToADroppingRunner() throws Exception {
		User user = user();
		try (Security security = new Security(user, true)) {
			security.insights.when(() -> SecurityInsightUtils.getInsight("p1", "r1")).thenReturn(security.insight);
			Object runner = new ExecuteInsightResource().generateInsight(request(user), "p1", "r1");

			RunInsight runInsight = assertInstanceOf(RunInsight.class, runner);
			assertSame(security.insight, field(runInsight, "in"));
			assertEquals(true, field(runInsight, "drop"));
			assertSame(security.insight, InsightStore.getInstance().get("exec-insight"));
			verify(security.insight).reRunPixelInsight(false);
			security.cluster.verify(() -> ClusterUtil.pullInsightsDB(anyString()), never());
		}
	}

	@Test
	void pullsTheProjectAndReadsTheInsightFromItWhenTheSecurityLookupFails() throws Exception {
		User user = user();
		try (Security security = new Security(user, true)) {
			security.insights.when(() -> SecurityInsightUtils.getInsight("p1", "r1"))
					.thenThrow(new IllegalStateException("not cataloged"));
			security.assets.when(() -> AssetUtility.getProjectVersionFolder("Project One", "p1"))
					.thenReturn("/projects/p1/version");
			Vector<Insight> found = new Vector<>();
			found.add(security.insight);
			when(security.project.getInsight("r1")).thenReturn(found);

			Object runner = new ExecuteInsightResource().generateInsight(request(user), "p1", "r1");

			assertInstanceOf(RunInsight.class, runner);
			security.cluster.verify(() -> ClusterUtil.pullInsightsDB("p1"));
			security.cluster.verify(() -> ClusterUtil.pullProjectFolder(security.project, "/projects/p1/version"));
			verify(security.insight).reRunPixelInsight(false);
		}
	}

	@Test
	void reportsAnInsightTheProjectDoesNotHave() throws Exception {
		User user = user();
		try (Security security = new Security(user, true)) {
			security.insights.when(() -> SecurityInsightUtils.getInsight(any(), any()))
					.thenThrow(new IllegalStateException("not cataloged"));
			when(security.project.getInsight("r1")).thenReturn(new Vector<>());

			Object response = new ExecuteInsightResource().generateInsight(request(user), "p1", "r1");

			assertTrue(response instanceof Response);
			assertEquals("Insight does not exist", json(response).get(Constants.ERROR_MESSAGE));
		}
	}

	@Test
	void reportsAnInsightTheProjectCannotBuild() throws Exception {
		User user = user();
		try (Security security = new Security(user, true)) {
			security.insights.when(() -> SecurityInsightUtils.getInsight(any(), any()))
					.thenThrow(new IllegalStateException("not cataloged"));
			when(security.project.getInsight("r1")).thenThrow(new IllegalArgumentException("bad recipe"));

			Object response = new ExecuteInsightResource().generateInsight(request(user), "p1", "r1");

			assertTrue(((String) json(response).get(Constants.ERROR_MESSAGE)).endsWith("bad recipe"));
		}
	}
}
