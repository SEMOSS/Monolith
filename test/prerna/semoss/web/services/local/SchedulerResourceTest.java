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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import prerna.rpa.config.JobConfigKeys;
import prerna.sablecc2.PixelStreamUtility;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.util.Constants;

class SchedulerResourceTest {

	private final String sessionId = UUID.randomUUID().toString();
	private final ArgumentCaptor<Insight> insight = ArgumentCaptor.forClass(Insight.class);
	private final HttpSession session = mock(HttpSession.class);

	@AfterEach
	void cleanUp() {
		for (Insight in : insight.getAllValues()) {
			InsightStore.getInstance().remove(in.getInsightId());
			InsightStore.getInstance().removeFromSessionHash(sessionId, in.getInsightId());
		}
		ThreadStore.remove();
	}

	private static User schedulerUser() {
		User user = new User();
		AccessToken token = new AccessToken();
		token.setProvider(AuthProvider.NATIVE);
		token.setId("jdoe");
		user.setAccessToken(token);
		return user;
	}

	private HttpServletRequest request(User user, String pixel) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(session.getId()).thenReturn(sessionId);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(request.getSession(false)).thenReturn(session);
		when(request.getSession()).thenReturn(session);
		when(request.getParameter(JobConfigKeys.PIXEL)).thenReturn(pixel);
		return request;
	}

	private final class Jobs implements AutoCloseable {
		final MockedStatic<PixelJobManager> managerStatic = mockStatic(PixelJobManager.class);
		final MockedStatic<PixelStreamUtility> streamStatic = mockStatic(PixelStreamUtility.class);
		final PixelJobManager manager = mock(PixelJobManager.class);
		final PixelJobRunner jobRunner = mock(PixelJobRunner.class);
		final StreamingOutput stream = out -> {
		};

		Jobs() {
			managerStatic.when(PixelJobManager::getManager).thenReturn(manager);
			when(manager.makeJob(any(), insight.capture(), any(), any())).thenReturn(jobRunner);
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
	void runsTheTrimmedPixelInSchedulerModeAndEndsTheSession() {
		User user = schedulerUser();
		try (Jobs jobs = new Jobs()) {
			Response response = new SchedulerResource().executePixel(request(user, "  RunJob()  "));

			assertEquals(200, response.getStatus());
			assertSame(jobs.stream, response.getEntity());
			verify(jobs.jobRunner).addPixel("RunJob();");
			assertTrue(insight.getValue().isSchedulerMode());
			assertSame(user, insight.getValue().getUser());
			verify(session).invalidate();
		}
	}

	@Test
	void keepsAPixelThatAlreadyEndsWithASemicolon() {
		try (Jobs jobs = new Jobs()) {
			new SchedulerResource().executePixel(request(schedulerUser(), "A();B();"));
			verify(jobs.jobRunner).addPixel("A();B();");
		}
	}

	@Test
	void endsTheSessionEvenWhenThePixelCannotRun() {
		try (Jobs jobs = new Jobs()) {
			Response response = new SchedulerResource().executePixel(request(null, "RunJob()"));

			assertEquals(401, response.getStatus());
			verify(session).invalidate();
			jobs.managerStatic.verifyNoInteractions();
		}
	}
}
