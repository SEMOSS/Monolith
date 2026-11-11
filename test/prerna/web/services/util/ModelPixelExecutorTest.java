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
package prerna.web.services.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.ModelPixelInvoker;
import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.usage.ModelUsageGuard;
import prerna.engine.impl.model.usage.ModelUsageLimitException;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.util.Constants;
import prerna.util.Utility;

class ModelPixelExecutorTest {

	private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

	private static ModelUsageLimitException rateLimit(String resetsAt) {
		return new ModelUsageLimitException("slow down", null, true, resetsAt);
	}

	private static ModelUsageLimitException dailyLimit() {
		return new ModelUsageLimitException("come back tomorrow", null, false, "2026-11-11T00:00:00Z");
	}

	private static String body(Response response) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString();
	}

	@Test
	void namesWhyAStreamedJobStoppedWithoutFinishing() {
		assertEquals("The model request failed", ModelPixelExecutor.streamFailureMessage(PixelJobStatus.ERROR));
		assertEquals("The model request was canceled", ModelPixelExecutor.streamFailureMessage(PixelJobStatus.CANCELED));
		assertEquals("The model request is no longer running",
				ModelPixelExecutor.streamFailureMessage(PixelJobStatus.UNKNOWN_JOB));
		for (PixelJobStatus running : List.of(PixelJobStatus.CREATED, PixelJobStatus.SUBMITTED,
				PixelJobStatus.IN_PROGRESS, PixelJobStatus.STREAMING, PixelJobStatus.PAUSED,
				PixelJobStatus.PROGRESS_COMPLETE, PixelJobStatus.COMPLETE)) {
			assertNull(ModelPixelExecutor.streamFailureMessage(running), running.name());
		}
		assertNull(ModelPixelExecutor.streamFailureMessage(null));
	}

	@Test
	void roundsRetryAfterUpToWholeSeconds() {
		assertEquals(1L, ModelPixelExecutor.retryAfterSeconds(rateLimit("2026-11-10T12:00:00.001Z"), NOW));
		assertEquals(60L, ModelPixelExecutor.retryAfterSeconds(rateLimit("2026-11-10T12:01:00Z"), NOW));
		assertEquals(61L, ModelPixelExecutor.retryAfterSeconds(rateLimit("2026-11-10T12:01:00.500Z"), NOW));
	}

	@Test
	void neverAsksForARetryInThePast() {
		assertEquals(1L, ModelPixelExecutor.retryAfterSeconds(rateLimit("2026-11-10T11:00:00Z"), NOW));
	}

	@Test
	void givesNoRetryAfterForLongRulesOrUnknownResets() {
		assertNull(ModelPixelExecutor.retryAfterSeconds(dailyLimit(), NOW));
		assertNull(ModelPixelExecutor.retryAfterSeconds(rateLimit(null), NOW));
		assertNull(ModelPixelExecutor.retryAfterSeconds(rateLimit("next tuesday"), NOW));
	}

	@Test
	void mapsEachRefusalToItsErrorCode() {
		assertEquals(ModelPixelExecutor.RATE_LIMIT_CODE, ModelPixelExecutor.usageLimitCode(rateLimit(null)));
		assertEquals(ModelUsageLimitException.ERROR_CODE, ModelPixelExecutor.usageLimitCode(dailyLimit()));
		assertEquals(ModelPixelExecutor.UNAVAILABLE_CODE,
				ModelPixelExecutor.usageLimitCode(ModelUsageLimitException.unavailable()));
	}

	@Test
	void answersARateLimitWith429AndRetryAfter() throws Exception {
		String resetsAt = Instant.now().plusSeconds(120).toString();
		Map<String, Object> body = Map.of("error", Map.of("code", ModelPixelExecutor.RATE_LIMIT_CODE));

		Response response = ModelPixelExecutor.usageLimitResponse(rateLimit(resetsAt), body);

		assertEquals(429, response.getStatus());
		long retryAfter = Long.parseLong(response.getHeaderString("Retry-After"));
		assertTrue(retryAfter > 100 && retryAfter <= 120, "Retry-After was " + retryAfter);
		assertEquals("{\"error\":{\"code\":\"rate_limit_exceeded\"}}", body(response));
	}

	@Test
	void answersADailyLimitWith429AndNoRetryAfter() {
		Response response = ModelPixelExecutor.usageLimitResponse(dailyLimit(), Map.of("error", "x"));

		assertEquals(429, response.getStatus());
		assertNull(response.getHeaderString("Retry-After"));
	}

	@Test
	void answersUncheckableLimitsWith503() {
		Response response = ModelPixelExecutor.usageLimitResponse(ModelUsageLimitException.unavailable(),
				Map.of("error", "x"));

		assertEquals(503, response.getStatus());
		assertNull(response.getHeaderString("Retry-After"));
	}

	@Test
	void findsARefusalWrappedInOtherErrors() {
		ModelUsageLimitException refusal = dailyLimit();
		RuntimeException wrapped = new RuntimeException("outer", new IllegalStateException("inner", refusal));

		assertSame(refusal, ModelPixelExecutor.findUsageLimitRefusal(wrapped));
		assertNull(ModelPixelExecutor.findUsageLimitRefusal(new RuntimeException("plain")));
		assertNull(ModelPixelExecutor.findUsageLimitRefusal(null));
	}

	@Test
	void stopsAtACauseCycle() {
		RuntimeException first = new RuntimeException("first");
		RuntimeException second = new RuntimeException("second", first);
		first.initCause(second);

		assertNull(ModelPixelExecutor.findUsageLimitRefusal(first));
	}

	@Test
	void returnsTheModelResponseOfASuccessfulCall() {
		AskModelEngineResponse<?> answer = mock(AskModelEngineResponse.class);
		try (MockedStatic<ModelPixelInvoker> invoker = Mockito.mockStatic(ModelPixelInvoker.class)) {
			invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), any())).thenAnswer(i -> answer);

			assertSame(answer, ModelPixelExecutor.askModelSync(mock(IModelEngine.class), null, null, Map.of()));
		}
	}

	@Test
	void rethrowsTheRefusalAPixelErrorStandsFor() {
		try (MockedStatic<ModelPixelInvoker> invoker = Mockito.mockStatic(ModelPixelInvoker.class)) {
			ModelUsageLimitException[] refusal = new ModelUsageLimitException[1];
			invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), any())).thenAnswer(i -> {
				refusal[0] = dailyLimit();
				throw new IllegalArgumentException("the pixel reported: come back tomorrow");
			});

			ModelUsageLimitException thrown = assertThrows(ModelUsageLimitException.class,
					() -> ModelPixelExecutor.askModelSync(mock(IModelEngine.class), null, null, Map.of()));
			assertSame(refusal[0], thrown);
		}
	}

	@Test
	void ignoresARefusalLeftOnTheThreadBeforeTheCall() {
		dailyLimit();
		IllegalStateException failure = new IllegalStateException("model is down");
		try (MockedStatic<ModelPixelInvoker> invoker = Mockito.mockStatic(ModelPixelInvoker.class)) {
			invoker.when(() -> ModelPixelInvoker.askModelSync(any(), any(), any(), any())).thenThrow(failure);

			IllegalStateException thrown = assertThrows(IllegalStateException.class,
					() -> ModelPixelExecutor.askModelSync(mock(IModelEngine.class), null, null, Map.of()));
			assertSame(failure, thrown);
		}
	}

	@Test
	void reportsARefusalBeforeAStreamStarts() {
		User user = mock(User.class);
		IModelEngine engine = mock(IModelEngine.class);
		ModelUsageLimitException refusal = dailyLimit();
		try (MockedStatic<ModelUsageGuard> guard = Mockito.mockStatic(ModelUsageGuard.class)) {
			guard.when(() -> ModelUsageGuard.check(user, engine)).thenThrow(refusal);

			assertSame(refusal, ModelPixelExecutor.checkUsageLimitsBeforeStream(user, engine));
		}
	}

	@Test
	void letsAStreamStartWhenTheCheckPassesOrCannotRun() {
		User user = mock(User.class);
		IModelEngine engine = mock(IModelEngine.class);
		try (MockedStatic<ModelUsageGuard> guard = Mockito.mockStatic(ModelUsageGuard.class)) {
			assertNull(ModelPixelExecutor.checkUsageLimitsBeforeStream(user, engine));

			guard.when(() -> ModelUsageGuard.check(user, engine)).thenThrow(new IllegalStateException("no db"));
			assertNull(ModelPixelExecutor.checkUsageLimitsBeforeStream(user, engine));
		}
	}

	@Test
	void readsTheUserFromTheSession() {
		User user = mock(User.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);

		assertSame(user, ModelPixelExecutor.getSessionUser(session));
		assertNull(ModelPixelExecutor.getSessionUser(mock(HttpSession.class)));
		assertNull(ModelPixelExecutor.getSessionUser(null));
	}

	@Test
	void invalidatesALiveSessionAndAnswers401() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.isRequestedSessionIdValid()).thenReturn(true);
		HttpSession session = mock(HttpSession.class);

		Response response = ModelPixelExecutor.invalidSessionResponse(request, session);

		assertEquals(401, response.getStatus());
		assertEquals("{\"" + Constants.ERROR_MESSAGE + "\":\"User session is invalid\"}", body(response));
		verify(session).invalidate();
	}

	@Test
	void leavesAStaleSessionAloneWhenAnswering401() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);

		assertEquals(401, ModelPixelExecutor.invalidSessionResponse(request, session).getStatus());
		assertEquals(401, ModelPixelExecutor.invalidSessionResponse(request, null).getStatus());
		verify(session, never()).invalidate();
	}

	@Test
	void appliesAValidTimezoneFromTheRequest() {
		User user = mock(User.class);
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getParameter("tz")).thenReturn(" America/New_York ");

		ModelPixelExecutor.applyUserTimezone(user, request);

		verify(user).setZoneId(ZoneId.of("America/New_York"));
	}

	@Test
	void fallsBackToTheApplicationZoneForAMissingOrBadTimezone() {
		try (MockedStatic<Utility> utility = Mockito.mockStatic(Utility.class)) {
			utility.when(Utility::getApplicationZoneId).thenReturn("Europe/Paris");
			for (String tz : new String[] { null, "  ", "Not/AZone" }) {
				User user = mock(User.class);
				HttpServletRequest request = mock(HttpServletRequest.class);
				when(request.getParameter("tz")).thenReturn(tz);

				ModelPixelExecutor.applyUserTimezone(user, request);

				verify(user).setZoneId(ZoneId.of("Europe/Paris"));
			}
		}
	}

	@Test
	void buildsTheErrorEnvelope() throws Exception {
		Response response = ModelPixelExecutor.errorResponse(403, "Model m1 inaccessible.");

		assertEquals(403, response.getStatus());
		assertEquals("{\"" + Constants.ERROR_MESSAGE + "\":\"Model m1 inaccessible.\"}", body(response));
	}
}
