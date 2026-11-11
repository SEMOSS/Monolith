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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;

class ShareInsightResourceTest {

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
	}

	private HttpServletRequest request() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn(sessionId);
		when(request.getSession(false)).thenReturn(session);
		return request;
	}

	private static String error(Object response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) ((Response) response).getEntity()).write(out);
		return (String) new Gson().fromJson(out.toString(StandardCharsets.UTF_8), Map.class)
				.get(Constants.ERROR_MESSAGE);
	}

	private static Insight insightOf(RunInsight runner) throws Exception {
		Field field = RunInsight.class.getDeclaredField("in");
		field.setAccessible(true);
		return (Insight) field.get(runner);
	}

	@Test
	void rejectsARequestWithoutASessionWith400() throws Exception {
		Object response = new ShareInsightResource().validInsight(mock(HttpServletRequest.class),
				insight.getInsightId());
		assertEquals(400, ((Response) response).getStatus());
		assertEquals("Invalid session to retrieve insight data", error(response));
	}

	@Test
	void rejectsAnUnknownInsightWith400() throws Exception {
		Object response = new ShareInsightResource().validInsight(request(), "no-such-insight");
		assertEquals(400, ((Response) response).getStatus());
		assertEquals("Invalid insight id", error(response));
	}

	@Test
	void rejectsAnInsightThatBelongsToAnotherSession() throws Exception {
		InsightStore.getInstance().addToSessionHash("someone-else", insight.getInsightId());
		try {
			Object response = new ShareInsightResource().validInsight(request(), insight.getInsightId());
			assertEquals(400, ((Response) response).getStatus());
		} finally {
			InsightStore.getInstance().removeFromSessionHash("someone-else", insight.getInsightId());
		}
	}

	@Test
	void rejectsAnInsightWhenTheSessionHasNoInsights() throws Exception {
		Object response = new ShareInsightResource().validInsight(request(), insight.getInsightId());
		assertEquals(400, ((Response) response).getStatus());
	}

	@Test
	void handsTheSessionsOwnInsightToARunner() throws Exception {
		InsightStore.getInstance().addToSessionHash(sessionId, insight.getInsightId());
		Object runner = new ShareInsightResource().validInsight(request(), insight.getInsightId());

		RunInsight runInsight = assertInstanceOf(RunInsight.class, runner);
		assertSame(insight, insightOf(runInsight));
	}
}
