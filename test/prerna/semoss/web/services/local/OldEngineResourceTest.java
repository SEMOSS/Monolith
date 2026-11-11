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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.engine.api.IDatabaseEngine;
import prerna.forms.AbstractFormBuilder;
import prerna.forms.FormBuilder;
import prerna.forms.FormFactory;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.reactor.PixelPlanner;
import prerna.reactor.legacy.playsheets.GetPlaysheetParamsReactor;
import prerna.reactor.legacy.playsheets.RunPlaysheetReactor;
import prerna.sablecc2.om.NounStore;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;

class OldEngineResourceTest {

	private final IDatabaseEngine engine = mock(IDatabaseEngine.class);

	@AfterEach
	void clearThreadStore() {
		ThreadStore.remove();
	}

	private OldEngineResource resource() {
		when(this.engine.getEngineId()).thenReturn("db1");
		OldEngineResource resource = new OldEngineResource();
		resource.setEngine(this.engine);
		return resource;
	}

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static HttpServletRequest requestFor(User user) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getId()).thenReturn("session-1");
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(request.getSession(false)).thenReturn(session);
		when(request.getSession(true)).thenReturn(session);
		return request;
	}

	private static User userWith(AuthProvider provider, String id) {
		User user = mock(User.class);
		AccessToken token = mock(AccessToken.class);
		when(token.getId()).thenReturn(id);
		when(user.getAccessToken(provider)).thenReturn(token);
		return user;
	}

	private static MultivaluedMap<String, String> form(String key, String value) {
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		form.add(key, value);
		return form;
	}

	private static Object firstNoun(NounStore store, String key) {
		return store.getNoun(key).get(0);
	}

	@Test
	void asksThePlaysheetReactorForTheInsightParams() throws IOException {
		NounStore store = new NounStore("all");
		try (MockedConstruction<GetPlaysheetParamsReactor> reactors = Mockito.mockConstruction(
				GetPlaysheetParamsReactor.class, (reactor, context) -> {
					when(reactor.getNounStore()).thenReturn(store);
					when(reactor.execute()).thenReturn(new NounMetadata(Map.of("params", List.of("Year")),
							PixelDataType.MAP));
				})) {
			Response response = resource().getInsightParams("<script>x</script>i1");

			assertEquals(200, response.getStatus());
			assertEquals("{\"params\":[\"Year\"]}", body(response));
			assertEquals("db1", firstNoun(store, ReactorKeysEnum.DATABASE.getKey()));
			assertEquals("db1", firstNoun(store, "app"));
			assertEquals("i1", firstNoun(store, ReactorKeysEnum.ID.getKey()));
			verify(reactors.constructed().get(0)).In();
		}
	}

	@Test
	void runsThePlaysheetForTheSessionUser() throws IOException {
		User user = mock(User.class);
		NounStore store = new NounStore("all");
		InsightStore insights = mock(InsightStore.class);
		MultivaluedMap<String, String> form = form("insight", "i1");
		form.add("params", "{\"Year\":[2020,2021]}");
		Object[] threadDuringRun = new Object[2];
		try (MockedConstruction<RunPlaysheetReactor> reactors = Mockito.mockConstruction(RunPlaysheetReactor.class,
				(reactor, context) -> {
					when(reactor.getNounStore()).thenReturn(store);
					when(reactor.execute()).thenAnswer(i -> {
						threadDuringRun[0] = ThreadStore.getUser();
						threadDuringRun[1] = ThreadStore.getSessionId();
						return new NounMetadata("ran", PixelDataType.CONST_STRING);
					});
				});
				MockedConstruction<PixelPlanner> planners = Mockito.mockConstruction(PixelPlanner.class);
				MockedStatic<InsightStore> stores = Mockito.mockStatic(InsightStore.class)) {
			stores.when(InsightStore::getInstance).thenReturn(insights);

			Response response = resource().createOutput(form, requestFor(user));

			assertEquals("\"ran\"", body(response));
			ArgumentCaptor<Insight> insight = ArgumentCaptor.forClass(Insight.class);
			verify(insights).put(insight.capture());
			verify(insights).addToSessionHash("session-1", insight.getValue().getInsightId());
			assertSame(user, insight.getValue().getUser());
			verify(reactors.constructed().get(0)).setInsight(insight.getValue());
			assertEquals("db1", firstNoun(store, ReactorKeysEnum.DATABASE.getKey()));
			assertEquals("i1", firstNoun(store, ReactorKeysEnum.ID.getKey()));
			assertEquals(Map.of("Year", List.of(2020.0, 2021.0)), firstNoun(store, ReactorKeysEnum.PARAM_KEY.getKey()));
			assertSame(user, threadDuringRun[0]);
			assertEquals("session-1", threadDuringRun[1]);
			assertNull(ThreadStore.getUser(), "the thread is cleared once the playsheet has run");
		}
	}

	@Test
	void leavesOutParamsThatAreNotSent() throws IOException {
		NounStore store = new NounStore("all");
		try (MockedConstruction<RunPlaysheetReactor> reactors = Mockito.mockConstruction(RunPlaysheetReactor.class,
				(reactor, context) -> {
					when(reactor.getNounStore()).thenReturn(store);
					when(reactor.execute()).thenReturn(new NounMetadata("ran", PixelDataType.CONST_STRING));
				});
				MockedConstruction<PixelPlanner> planners = Mockito.mockConstruction(PixelPlanner.class);
				MockedStatic<InsightStore> stores = Mockito.mockStatic(InsightStore.class)) {
			stores.when(InsightStore::getInstance).thenReturn(mock(InsightStore.class));
			MultivaluedMap<String, String> form = form("insight", "i1");
			form.add("params", "");

			resource().createOutput(form, requestFor(mock(User.class)));

			assertNull(store.getNoun(ReactorKeysEnum.PARAM_KEY.getKey()));
		}
	}

	@Test
	void refusesFormDataFromAnUnidentifiedUser() throws IOException {
		try (MockedStatic<FormBuilder> forms = Mockito.mockStatic(FormBuilder.class)) {
			Response noSession = resource().commitFormData(mock(HttpServletRequest.class), form("formData", "{}"));
			Response nativeUser = resource().commitFormData(requestFor(userWith(AuthProvider.NATIVE, "jdoe")),
					form("formData", "{}"));

			assertEquals(400, noSession.getStatus());
			assertTrue(body(noSession).contains("Could not identify user"));
			assertEquals(400, nativeUser.getStatus());
			assertTrue(body(nativeUser).contains("Could not identify user"));
			forms.verifyNoInteractions();
		}
	}

	@Test
	void commitsFormDataAsTheCacUserBeforeSaml() throws IOException {
		User user = userWith(AuthProvider.CAC, "cac-id");
		AccessToken saml = mock(AccessToken.class);
		when(saml.getId()).thenReturn("saml-id");
		when(user.getAccessToken(AuthProvider.SAML)).thenReturn(saml);
		try (MockedStatic<FormBuilder> forms = Mockito.mockStatic(FormBuilder.class);
				MockedStatic<FormFactory> factory = Mockito.mockStatic(FormFactory.class)) {
			OldEngineResource resource = resource();

			Response response = resource.commitFormData(requestFor(user), form("formData", "{\"nodes\":[]}"));

			assertEquals(200, response.getStatus());
			assertEquals("\"success\"", body(response));
			forms.verify(() -> FormBuilder.commitFormData(this.engine, Map.of("nodes", List.of()), "cac-id"));
		}
	}

	@Test
	void commitsFormDataThroughTheEnginesFormBuilder() throws IOException {
		AbstractFormBuilder builder = mock(AbstractFormBuilder.class);
		try (MockedStatic<FormBuilder> forms = Mockito.mockStatic(FormBuilder.class);
				MockedStatic<FormFactory> factory = Mockito.mockStatic(FormFactory.class)) {
			OldEngineResource resource = resource();
			factory.when(() -> FormFactory.getFormBuilder(this.engine)).thenReturn(builder);

			Response response = resource.commitFormData(requestFor(userWith(AuthProvider.SAML, "saml-id")),
					form("formData", "{\"a\":1}"));

			assertEquals(200, response.getStatus());
			verify(builder).commitFormData(Map.of("a", 1.0), "saml-id");
			forms.verify(() -> FormBuilder.commitFormData(any(), anyMap(), anyString()), never());
		}
	}

	@Test
	void answers400WhenTheFormDataCannotBeCommitted() throws IOException {
		try (MockedStatic<FormBuilder> forms = Mockito.mockStatic(FormBuilder.class);
				MockedStatic<FormFactory> factory = Mockito.mockStatic(FormFactory.class)) {
			OldEngineResource resource = resource();
			forms.when(() -> FormBuilder.commitFormData(eq(this.engine), anyMap(), eq("cac-id")))
					.thenThrow(new IOException("Engine type cannot be found"));

			Response response = resource.commitFormData(requestFor(userWith(AuthProvider.CAC, "cac-id")),
					form("formData", "{}"));

			assertEquals(400, response.getStatus());
			assertTrue(body(response).contains("Engine type cannot be found"));
		}
	}

	@Test
	void returnsTheEnginesAuditLog() throws IOException {
		try (MockedStatic<FormBuilder> forms = Mockito.mockStatic(FormBuilder.class)) {
			OldEngineResource resource = resource();
			forms.when(() -> FormBuilder.getAuditDataForEngine("db1")).thenReturn(Map.of("headers", List.of("USER")));

			Response response = resource.getAuditLogForEngine(new MultivaluedHashMap<>(), mock(HttpServletRequest.class));

			assertEquals(200, response.getStatus());
			assertTrue(body(response).contains("headers"));
		}
	}

	@Test
	void answers400WhenTheAuditLogCannotBeRead() throws IOException {
		try (MockedStatic<FormBuilder> forms = Mockito.mockStatic(FormBuilder.class)) {
			OldEngineResource resource = resource();
			forms.when(() -> FormBuilder.getAuditDataForEngine("db1"))
					.thenThrow(new IllegalStateException("no audit table"));

			Response response = resource.getAuditLogForEngine(new MultivaluedHashMap<>(), mock(HttpServletRequest.class));

			assertEquals(400, response.getStatus());
			assertTrue(body(response).contains("no audit table"));
		}
	}
}
