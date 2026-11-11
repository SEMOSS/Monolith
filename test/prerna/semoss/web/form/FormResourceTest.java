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
package prerna.semoss.web.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.invocation.Invocation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.engine.api.IRDBMSEngine;
import prerna.forms.AbstractFormBuilder;
import prerna.forms.FormFactory;
import prerna.masterdatabase.utility.MasterDatabaseUtility;
import prerna.util.Constants;
import prerna.util.QueryExecutionUtility;
import prerna.util.QueryExecutionUtility.RowMapper;
import prerna.util.QueryExecutionUtility.StatementBinder;
import prerna.util.Utility;

class FormResourceTest {

	private static final String CAC_ID = "1234567890";
	private static final String ADMIN_CHECK = "SELECT 1 FROM FORMS_USER_ACCESS WHERE USER_ID = ? AND INSTANCE_NAME = 'ADMIN' LIMIT 1";
	private static final String SYS_ADMIN_CHECK = "SELECT 1 FROM FORMS_USER_ACCESS WHERE USER_ID = ? AND INSTANCE_NAME = ? AND IS_SYS_ADMIN = TRUE LIMIT 1";
	private static final String INSTANCES = "SELECT INSTANCE_NAME, IS_SYS_ADMIN FROM FORMS_USER_ACCESS WHERE USER_ID = ?";

	@Test
	void refusesACallerWithoutACacOrSamlLogin() throws Exception {
		User nativeOnly = mock(User.class);
		when(nativeOnly.getAccessToken(AuthProvider.NATIVE)).thenReturn(token(AuthProvider.NATIVE, "jdoe"));
		try (FormStatics statics = new FormStatics(List.of())) {
			for (HttpServletRequest req : List.of(request(null), request(nativeOnly), request(mock(User.class)))) {
				FormResource resource = new FormResource();
				MultivaluedMap<String, String> form = form(Map.of("addOrRemove", "Add", "userid", "x"));
				for (Response response : List.of(resource.modifyUserAccess(req, form),
						resource.renameInstance(req, form), resource.certifyInstance(req, form),
						resource.getUserInstanceAuth(req))) {
					assertEquals(400, response.getStatus());
					assertEquals("Could not identify user",
							json(response).getAsJsonObject().get(Constants.ERROR_MESSAGE).getAsString());
				}
			}
			assertTrue(statics.statements.isEmpty(), "nothing is asked of the form engine");
			statics.factory.verifyNoInteractions();
		}
	}

	@Test
	void listsTheInstancesGrantedToTheCacUser() throws Exception {
		try (FormStatics statics = new FormStatics(List.of(new Object[] { "SYSTEM_A", true },
				new Object[] { "SYSTEM_B", false }))) {
			Response response = new FormResource().getUserInstanceAuth(request(cacUser(CAC_ID)));

			assertEquals(200, response.getStatus());
			JsonObject body = json(response).getAsJsonObject();
			assertEquals(CAC_ID, body.get("cac_id").getAsString());
			JsonObject instances = body.getAsJsonObject("validInstances");
			assertEquals("true", instances.get("SYSTEM_A").getAsString());
			assertEquals("false", instances.get("SYSTEM_B").getAsString());
			assertEquals(List.of(new Statement(INSTANCES, List.of(CAC_ID))), statics.statements);
			statics.formBuilder.verify(() -> AbstractFormBuilder.generateFormPermissionTable(statics.engine));
		}
	}

	@Test
	void fallsBackToTheSamlLoginWithoutACac() throws Exception {
		User samlUser = mock(User.class);
		when(samlUser.getAccessToken(AuthProvider.SAML)).thenReturn(token(AuthProvider.SAML, "saml-user"));
		try (FormStatics statics = new FormStatics(List.of())) {
			Response response = new FormResource().getUserInstanceAuth(request(samlUser));

			assertEquals("saml-user", json(response).getAsJsonObject().get("cac_id").getAsString());
		}
	}

	@Test
	void bindsTheUserIdInsteadOfWritingItIntoTheSql() throws Exception {
		String hostile = "x' OR '1'='1";
		try (FormStatics statics = new FormStatics(List.of())) {
			Response response = new FormResource().getUserInstanceAuth(request(cacUser(hostile)));

			assertEquals(List.of(new Statement(INSTANCES, List.of(hostile))), statics.statements);
			assertEquals(hostile, json(response).getAsJsonObject().get("cac_id").getAsString());
		}
	}

	@Test
	void modifyUserAccessWritesTheGrantForAnAdmin() throws Exception {
		try (FormStatics statics = new FormStatics(List.<Object[]>of(new Object[] { CAC_ID, "ADMIN" }))) {
			HttpServletRequest req = request(cacUser(CAC_ID));

			Response added = new FormResource().modifyUserAccess(req,
					form(Map.of("addOrRemove", "Add", "userid", "jdoe", "instanceName", "SYSTEM_A", "ownerStatus",
							"true")));
			new FormResource().modifyUserAccess(req,
					form(Map.of("addOrRemove", "Remove", "userid", "jdoe", "instanceName", "SYSTEM_A")));
			new FormResource().modifyUserAccess(req,
					form(Map.of("addOrRemove", "Remove", "userid", "jdoe", "instanceName", "undefined")));
			new FormResource().modifyUserAccess(req, form(Map.of("addOrRemove", "Remove", "userid", "jdoe")));

			assertEquals(200, added.getStatus());
			assertEquals("success", json(added).getAsString());
			assertEquals(List.of(
					new Statement("INSERT INTO FORMS_USER_ACCESS (USER_ID, INSTANCE_NAME, IS_SYS_ADMIN) VALUES (?, ?, ?)",
							List.of("jdoe", "SYSTEM_A", true)),
					new Statement("DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = ? AND INSTANCE_NAME = ?",
							List.of("jdoe", "SYSTEM_A")),
					new Statement("DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = ?", List.of("jdoe")),
					new Statement("DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = ?", List.of("jdoe"))),
					statics.updates);
			assertEquals(new Statement(ADMIN_CHECK, List.of(CAC_ID)), statics.statements.get(0));
		}
	}

	@Test
	void storesTheGrantValuesExactlyAsSent() throws Exception {
		try (FormStatics statics = new FormStatics(List.<Object[]>of(new Object[] { CAC_ID, "ADMIN" }))) {
			new FormResource().modifyUserAccess(request(cacUser(CAC_ID)),
					form(Map.of("addOrRemove", "Add", "userid", "o'neil", "instanceName", "SYSTEM_A")));

			assertEquals(List.of(
					new Statement("INSERT INTO FORMS_USER_ACCESS (USER_ID, INSTANCE_NAME, IS_SYS_ADMIN) VALUES (?, ?, ?)",
							List.of("o'neil", "SYSTEM_A", false))),
					statics.updates);
		}
	}

	@Test
	void modifyUserAccessNeedsAnAddOrARemove() throws Exception {
		try (FormStatics statics = new FormStatics(List.<Object[]>of(new Object[] { CAC_ID, "ADMIN" }))) {
			Response response = new FormResource().modifyUserAccess(request(cacUser(CAC_ID)),
					form(Map.of("addOrRemove", "Replace", "userid", "jdoe", "instanceName", "SYSTEM_A")));

			assertEquals(400, response.getStatus());
			assertEquals("Error: need to specify Add or Remove", json(response).getAsString());
			assertTrue(statics.updates.isEmpty());
		}
	}

	@Test
	void modifyUserAccessReportsAFailedWrite() throws Exception {
		try (FormStatics statics = new FormStatics(List.<Object[]>of(new Object[] { CAC_ID, "ADMIN" }))) {
			statics.query.when(() -> QueryExecutionUtility.executeUpdate(any(IRDBMSEngine.class), anyString(),
					any(StatementBinder.class))).thenThrow(new IllegalStateException("constraint"));

			Response response = new FormResource().modifyUserAccess(request(cacUser(CAC_ID)),
					form(Map.of("addOrRemove", "Add", "userid", "jdoe", "instanceName", "SYSTEM_A")));

			assertEquals(400, response.getStatus());
			assertEquals("An error occurred to update the user's access!", json(response).getAsString());
		}
	}

	@Test
	void renameAndCertifyHandTheChangeToTheDatabasesFormBuilder() throws Exception {
		try (FormStatics statics = new FormStatics(List.<Object[]>of(new Object[] { CAC_ID, "SYSTEM_A" }))) {
			HttpServletRequest req = request(cacUser(CAC_ID));

			Response renamed = new FormResource().renameInstance(req, form(Map.of("dbName", "RDF_DB", "originalUri",
					"http://semoss.org/Concept/A", "newUri", "http://semoss.org/Concept/B", "deleteInstanceBoolean",
					"true")));
			assertEquals(200, renamed.getStatus());
			verify(statics.builder).modifyInstanceValue("http://semoss.org/Concept/A", "http://semoss.org/Concept/B",
					true);

			Response certified = new FormResource().certifyInstance(req,
					form(Map.of("dbName", "RDF_DB", "instanceType", "System", "instanceName", "SYSTEM_A")));
			assertEquals(200, certified.getStatus());
			verify(statics.builder).setUser(CAC_ID);
			verify(statics.builder).certifyInstance("System", "SYSTEM_A");
			statics.master.verify(() -> MasterDatabaseUtility.testDatabaseIdIfAlias("RDF_DB"), times(2));
			assertTrue(statics.statements.contains(new Statement(SYS_ADMIN_CHECK, List.of(CAC_ID, "SYSTEM_A"))),
					statics.statements.toString());
		}
	}

	@Test
	void refusesEveryAdminEndpointToAUserWithoutTheGrant() throws Exception {
		try (FormStatics statics = new FormStatics(List.of())) {
			assertRefusedOnEveryAdminEndpoint(statics);
		}
	}

	@Test
	void refusesEveryAdminEndpointWhenTheAdminCheckFails() throws Exception {
		try (FormStatics statics = new FormStatics(List.of())) {
			statics.query.when(() -> QueryExecutionUtility.queryOne(any(IRDBMSEngine.class), anyString(),
					any(StatementBinder.class), any())).thenThrow(new IllegalStateException("form database is down"));

			assertRefusedOnEveryAdminEndpoint(statics);
		}
	}

	private static void assertRefusedOnEveryAdminEndpoint(FormStatics statics) throws Exception {
		HttpServletRequest req = request(cacUser(CAC_ID));
		Response grant = new FormResource().modifyUserAccess(req, form(Map.of("addOrRemove", "Add", "userid", CAC_ID,
				"instanceName", "ADMIN", "ownerStatus", "true")));
		Response rename = new FormResource().renameInstance(req, form(Map.of("dbName", "RDF_DB", "originalUri",
				"http://semoss.org/Concept/A", "newUri", "http://semoss.org/Concept/B", "deleteInstanceBoolean", "true")));
		Response certify = new FormResource().certifyInstance(req,
				form(Map.of("dbName", "RDF_DB", "instanceType", "System", "instanceName", "SYSTEM_A")));

		for (Response response : List.of(grant, rename, certify)) {
			assertEquals(400, response.getStatus());
			assertEquals("User is not an admin and cannot perform this operation",
					json(response).getAsJsonObject().get(Constants.ERROR_MESSAGE).getAsString());
		}
		assertTrue(statics.updates.isEmpty());
		verify(statics.builder, never()).modifyInstanceValue(anyString(), anyString(), anyBoolean());
		verify(statics.builder, never()).certifyInstance(anyString(), anyString());
	}

	/** A statement sent to the form engine: its SQL and the values bound to it, in order. */
	private record Statement(String sql, List<Object> params) {
	}

	private static Statement statement(String sql, StatementBinder binder) throws Exception {
		PreparedStatement ps = mock(PreparedStatement.class);
		binder.bind(ps);
		List<Object> params = new ArrayList<>();
		for (Invocation invocation : mockingDetails(ps).getInvocations()) {
			params.add(invocation.getArgument(1));
		}
		return new Statement(sql, params);
	}

	private static ResultSet row(Object[] values) throws Exception {
		ResultSet rs = mock(ResultSet.class);
		when(rs.getString(1)).thenReturn(String.valueOf(values[0]));
		when(rs.getBoolean(2)).thenReturn(Boolean.parseBoolean(String.valueOf(values[1])));
		return rs;
	}

	/**
	 * The forms database, the master database alias lookup and the form builder.
	 * Every statement sent to the forms database is recorded with its bound
	 * values; queries are answered with the given rows.
	 */
	private static final class FormStatics implements AutoCloseable {

		private final IRDBMSEngine engine = mock(IRDBMSEngine.class);
		private final AbstractFormBuilder builder = mock(AbstractFormBuilder.class);
		private final List<Statement> statements = new ArrayList<>();
		private final List<Statement> updates = new ArrayList<>();
		private final MockedStatic<Utility> utility;
		private final MockedStatic<AbstractFormBuilder> formBuilder = mockStatic(AbstractFormBuilder.class);
		private final MockedStatic<FormFactory> factory = mockStatic(FormFactory.class);
		private final MockedStatic<MasterDatabaseUtility> master = mockStatic(MasterDatabaseUtility.class);
		private final MockedStatic<QueryExecutionUtility> query = mockStatic(QueryExecutionUtility.class);

		private FormStatics(List<Object[]> rows) throws Exception {
			utility = mockStatic(Utility.class, invocation -> invocation.getMethod().getName().equals("getDatabase")
					? engine
					: invocation.callRealMethod());
			factory.when(() -> FormFactory.getFormBuilder(engine)).thenReturn(builder);
			master.when(() -> MasterDatabaseUtility.testDatabaseIdIfAlias(anyString()))
					.thenAnswer(inv -> inv.getArgument(0));
			query.when(() -> QueryExecutionUtility.queryOne(any(IRDBMSEngine.class), anyString(),
					any(StatementBinder.class), any())).thenAnswer(inv -> {
						statements.add(statement(inv.getArgument(1), inv.getArgument(2)));
						RowMapper<?> mapper = inv.getArgument(3);
						return rows.isEmpty() ? null : mapper.map(row(rows.get(0)));
					});
			query.when(() -> QueryExecutionUtility.queryList(any(IRDBMSEngine.class), anyString(),
					any(StatementBinder.class), any(RowMapper.class))).thenAnswer(inv -> {
						statements.add(statement(inv.getArgument(1), inv.getArgument(2)));
						RowMapper<?> mapper = inv.getArgument(3);
						List<Object> mapped = new ArrayList<>();
						for (Object[] values : rows) {
							mapped.add(mapper.map(row(values)));
						}
						return mapped;
					});
			query.when(() -> QueryExecutionUtility.executeUpdate(any(IRDBMSEngine.class), anyString(),
					any(StatementBinder.class))).thenAnswer(inv -> {
						Statement update = statement(inv.getArgument(1), inv.getArgument(2));
						statements.add(update);
						updates.add(update);
						return 1;
					});
		}

		@Override
		public void close() {
			query.close();
			master.close();
			factory.close();
			formBuilder.close();
			utility.close();
		}
	}

	private static User cacUser(String id) {
		User user = mock(User.class);
		when(user.getAccessToken(AuthProvider.CAC)).thenReturn(token(AuthProvider.CAC, id));
		return user;
	}

	private static AccessToken token(AuthProvider provider, String id) {
		AccessToken token = new AccessToken();
		token.setProvider(provider);
		token.setId(id);
		return token;
	}

	private static MultivaluedMap<String, String> form(Map<String, String> values) {
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		values.forEach(form::add);
		return form;
	}

	private static HttpServletRequest request(User user) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		if (user != null) {
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(req.getSession(false)).thenReturn(session);
		}
		return req;
	}

	private static JsonElement json(Response response) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			((StreamingOutput) response.getEntity()).write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return JsonParser.parseString(out.toString(StandardCharsets.UTF_8));
	}
}
