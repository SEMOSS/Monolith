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
package prerna.web.conf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.reactor.scheduler.SchedulerDatabaseUtility;
import prerna.rpa.config.JobConfigKeys;
import prerna.util.Constants;

class SchedulerFilterTest {

	private static HttpServletRequest request(String execId, String jobId, String jobGroup, String userAccess) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getParameter(JobConfigKeys.EXEC_ID)).thenReturn(execId);
		when(request.getParameter(JobConfigKeys.JOB_ID)).thenReturn(jobId);
		when(request.getParameter(JobConfigKeys.JOB_GROUP)).thenReturn(jobGroup);
		when(request.getParameter(JobConfigKeys.USER_ACCESS)).thenReturn(userAccess);
		return request;
	}

	private static void assertRefused(HttpServletRequest request) throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		try (MockedStatic<SchedulerDatabaseUtility> scheduler = mockStatic(SchedulerDatabaseUtility.class)) {
			scheduler.when(() -> SchedulerDatabaseUtility.executionIdExists("e1"))
					.thenReturn(new String[] { "job1", "group1" });
			new SchedulerFilter().doFilter(request, response, chain);
			scheduler.verify(() -> SchedulerDatabaseUtility.removeExecutionId(anyString()), never());
		}
		verify(chain, never()).doFilter(request, response);
		verify(request, never()).getSession(anyBoolean());
	}

	private static User signIn(String userAccess) throws Exception {
		HttpServletRequest request = request("e1", "job1", "group1", userAccess);
		HttpSession session = mock(HttpSession.class);
		when(request.getSession(true)).thenReturn(session);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);
		try (MockedStatic<SchedulerDatabaseUtility> scheduler = mockStatic(SchedulerDatabaseUtility.class)) {
			scheduler.when(() -> SchedulerDatabaseUtility.executionIdExists("e1"))
					.thenReturn(new String[] { "job1", "group1" });
			new SchedulerFilter().doFilter(request, response, chain);
			scheduler.verify(() -> SchedulerDatabaseUtility.removeExecutionId("e1"));
		}
		verify(chain).doFilter(request, response);
		ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
		verify(session).setAttribute(eq(Constants.SESSION_USER), user.capture());
		return user.getValue();
	}

	@Test
	void refusesAnUnknownExecutionId() throws Exception {
		assertRefused(request("unknown", "job1", "group1", "NATIVE:jdoe"));
	}

	@Test
	void refusesAnExecutionIdForAnotherJob() throws Exception {
		assertRefused(request("e1", "job2", "group1", "NATIVE:jdoe"));
		assertRefused(request("e1", "job1", "group2", "NATIVE:jdoe"));
		assertRefused(request("e1", null, "group1", "NATIVE:jdoe"));
	}

	@Test
	void signsInEveryLoginOfTheJobOwnerAndConsumesTheExecutionId() throws Exception {
		User user = signIn("NATIVE:jdoe,MICROSOFT:abc-123");

		assertEquals(List.of(AuthProvider.NATIVE, AuthProvider.MICROSOFT), user.getLogins());
		AccessToken nativeToken = user.getAccessToken(AuthProvider.NATIVE);
		assertEquals("jdoe", nativeToken.getId());
		assertTrue(nativeToken.getName().startsWith("scheduler_"));
		assertEquals("abc-123", user.getAccessToken(AuthProvider.MICROSOFT).getId());
	}

	@Test
	void keepsColonsInsideTheUserId() throws Exception {
		User user = signIn("NATIVE:corp:jdoe");

		assertEquals("corp:jdoe", user.getAccessToken(AuthProvider.NATIVE).getId());
	}
}
