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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.util.Constants;
import prerna.util.insight.InsightUtility;

class RunInsightTest {

	private static final String TABLE_PIXEL = "QueryAll()|Collect(-1);FrameHeaders();";

	private final String sessionId = UUID.randomUUID().toString();
	private Insight insight;

	@BeforeEach
	void storeAnInsight() {
		insight = new Insight();
		InsightStore.getInstance().put(insight);
	}

	@AfterEach
	void removeTheInsight() {
		InsightStore.getInstance().remove(insight.getInsightId());
		InsightStore.getInstance().removeFromSessionHash(sessionId, insight.getInsightId());
		ThreadStore.remove();
	}

	private static User user() {
		User user = new User();
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		user.setAccessToken(token);
		return user;
	}

	private HttpServletRequest request(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(sessionId);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(request.getSession(false)).thenReturn(session);
		when(request.getParameter("expression")).thenReturn("Ignored()");
		when(request.getParameter("insightId")).thenReturn("ignored-insight");
		return request;
	}

	private static final class Jobs implements AutoCloseable {
		final MockedStatic<PixelJobManager> managerStatic = mockStatic(PixelJobManager.class);
		final MockedStatic<PixelStreamUtility> streamStatic = mockStatic(PixelStreamUtility.class);
		final PixelJobManager manager = mock(PixelJobManager.class);
		final PixelJobRunner jobRunner = mock(PixelJobRunner.class);
		final StreamingOutput stream = out -> {
		};

		Jobs() {
			managerStatic.when(PixelJobManager::getManager).thenReturn(manager);
			when(manager.makeJob(any(), any(), any(), any())).thenReturn(jobRunner);
			when(jobRunner.getJobId()).thenReturn("job-1");
			streamStatic.when(() -> PixelStreamUtility.collectPixelData(any(), eq(jobRunner))).thenReturn(stream);
		}

		@Override
		public void close() {
			streamStatic.close();
			managerStatic.close();
		}
	}

	@Test
	void getInsightDataRunsTheTablePixelOnItsOwnInsight() {
		User user = user();
		try (Jobs jobs = new Jobs()) {
			Response response = new RunInsight(insight).getInsightData(request(user));

			assertEquals(200, response.getStatus());
			assertSame(jobs.stream, response.getEntity());
			verify(jobs.manager).makeJob(eq(insight.getInsightId()), eq(insight), eq(sessionId), isNull());
			verify(jobs.jobRunner).addPixel(TABLE_PIXEL);
			assertSame(user, insight.getUser());
		}
	}

	@Test
	void getInsightDataDropsTheInsightAfterwardsWhenAskedTo() {
		try (Jobs jobs = new Jobs()) {
			RunInsight runner = new RunInsight(insight);
			runner.dropInsight(true);
			runner.getInsightData(request(user()));

			verify(jobs.jobRunner).addPixel(TABLE_PIXEL + "DropInsight();");
		}
	}

	@Test
	void getInsightDataStillNeedsASignedInUser() {
		try (Jobs jobs = new Jobs()) {
			Response response = new RunInsight(insight).getInsightData(request(null));
			assertEquals(401, response.getStatus());
			jobs.managerStatic.verifyNoInteractions();
		}
	}

	@Test
	void recreateInsightStateStreamsTheRebuiltStateWithoutCaching() {
		PixelRunner runner = mock(PixelRunner.class);
		StreamingOutput stream = out -> {
		};
		try (MockedStatic<InsightUtility> insightUtility = mockStatic(InsightUtility.class);
				MockedStatic<PixelStreamUtility> streamStatic = mockStatic(PixelStreamUtility.class)) {
			insightUtility.when(() -> InsightUtility.recreateInsightState(insight)).thenReturn(runner);
			streamStatic.when(() -> PixelStreamUtility.collectPixelData(runner, null)).thenReturn(stream);

			Response response = new RunInsight(insight).recreateInsightState(request(user()));

			assertEquals(200, response.getStatus());
			assertSame(stream, response.getEntity());
			assertEquals("no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0",
					response.getHeaderString("Cache-Control"));
			assertEquals("no-cache", response.getHeaderString("Pragma"));
		}
	}
}
