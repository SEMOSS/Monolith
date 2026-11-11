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
package prerna.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.util.Constants;
import prerna.util.DIHelper;

class InsightWebsocketTest {

	private static final String NOT_THIS_SESSIONS = "Insight does not exist or does not belong to this session";

	private final InsightWebsocket endpoint = new InsightWebsocket();
	private final List<String> createdInsights = new ArrayList<>();
	private final List<String> createdSessions = new ArrayList<>();
	private String httpSession;
	private String insightId;
	private Insight insight;

	@BeforeEach
	void openInsight() {
		httpSession = newHttpSession();
		insightId = "socket-" + UUID.randomUUID();
		insight = mock(Insight.class);
		when(insight.getInsightId()).thenReturn(insightId);
		InsightStore.getInstance().put(insight);
		InsightStore.getInstance().addToSessionHash(httpSession, insightId);
	}

	@AfterEach
	void closeInsight() {
		InsightStore.getInstance().remove(insightId);
		createdInsights.forEach(InsightStore.getInstance()::remove);
		createdSessions.forEach(InsightStore.getInstance()::clearSession);
	}

	private String newHttpSession() {
		String id = "http-" + UUID.randomUUID();
		createdSessions.add(id);
		return id;
	}

	private static User signedIn() {
		User user = mock(User.class);
		when(user.getLogins()).thenReturn(List.of(AuthProvider.NATIVE));
		return user;
	}

	private static EndpointConfig config(User user, String httpSessionId) {
		EndpointConfig config = mock(EndpointConfig.class);
		Map<String, Object> properties = new HashMap<>();
		if (user != null) {
			properties.put(Constants.SESSION_USER, user);
		}
		if (httpSessionId != null) {
			properties.put(WSConfigurator.HTTP_SESSION_ID, httpSessionId);
		}
		when(config.getUserProperties()).thenReturn(properties);
		return config;
	}

	private static Session socket(String insightId, RemoteEndpoint.Basic remote) {
		Session session = mock(Session.class);
		Map<String, Object> properties = new HashMap<>();
		when(session.getUserProperties()).thenReturn(properties);
		when(session.getRequestParameterMap())
				.thenReturn(insightId == null ? Map.of() : Map.of("insightId", List.of(insightId)));
		when(session.getBasicRemote()).thenReturn(remote);
		when(session.getId()).thenReturn("ws-" + UUID.randomUUID());
		return session;
	}

	private Session open(User user, RemoteEndpoint.Basic remote) {
		Session session = socket(insightId, remote);
		endpoint.onOpen(session, config(user, httpSession));
		return session;
	}

	private static void assertRefused(Session session, String reason) throws IOException {
		verify(session).close(argThat(close -> close.getCloseCode() == CloseCodes.VIOLATED_POLICY
				&& reason.equals(close.getReasonPhrase())));
		assertNull(session.getUserProperties().get("INSIGHT_ID"));
	}

	private static String lastText(RemoteEndpoint.Basic remote) throws IOException {
		ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
		verify(remote).sendText(sent.capture());
		return sent.getValue();
	}

	private static MockedStatic<PixelStreamUtility> pixelReturns(PixelRunner runner, String json) {
		MockedStatic<PixelStreamUtility> stream = mockStatic(PixelStreamUtility.class);
		StreamingOutput output = out -> out.write(json.getBytes(StandardCharsets.UTF_8));
		stream.when(() -> PixelStreamUtility.collectPixelData(runner, null)).thenReturn(output);
		return stream;
	}

	@Test
	void refusesAHandshakeWithoutASignedInUserOrHttpSession() throws Exception {
		User noLogins = mock(User.class);
		for (EndpointConfig config : List.of(config(null, httpSession), config(noLogins, httpSession),
				config(signedIn(), null))) {
			Session session = socket(insightId, mock(RemoteEndpoint.Basic.class));

			endpoint.onOpen(session, config);

			assertRefused(session, "User session is invalid");
		}
		assertTrue(SocketSessionHandlerFactory.getHandler(insightId).isEmpty());
	}

	@Test
	void refusesASocketWithoutAnInsightId() throws Exception {
		Session session = socket(null, mock(RemoteEndpoint.Basic.class));

		endpoint.onOpen(session, config(signedIn(), httpSession));

		assertRefused(session, "Must pass in insightId");
	}

	@Test
	void bindsTheUserAndAnInsightOfItsOwnSession() {
		User user = signedIn();

		Session session = open(user, mock(RemoteEndpoint.Basic.class));

		assertSame(user, session.getUserProperties().get(Constants.SESSION_USER));
		assertEquals(insightId, session.getUserProperties().get("INSIGHT_ID"));
		assertFalse(SocketSessionHandlerFactory.getHandler(insightId).isEmpty());
		assertSame(insight, InsightStore.getInstance().get(insightId));
	}

	@Test
	void refusesAnotherSessionsInsightSoItNeverReceivesItsResults() throws Exception {
		User owner = signedIn();
		RemoteEndpoint.Basic ownerRemote = mock(RemoteEndpoint.Basic.class);
		RemoteEndpoint.Basic strangerRemote = mock(RemoteEndpoint.Basic.class);
		Session ownerSocket = open(owner, ownerRemote);
		Session strangerSocket = socket(insightId, strangerRemote);

		endpoint.onOpen(strangerSocket, config(signedIn(), newHttpSession()));

		assertRefused(strangerSocket, NOT_THIS_SESSIONS);
		PixelRunner runner = mock(PixelRunner.class);
		when(insight.runPixel("Secret();")).thenReturn(runner);
		try (MockedStatic<PixelStreamUtility> stream = pixelReturns(runner, "{\"secret\":true}")) {
			endpoint.handleMessage("{\"pixel\":\"Secret();\"}", ownerSocket);
		}
		verify(ownerRemote).sendText("{\"secret\":true}");
		verify(strangerRemote, never()).sendText(anyString());
	}

	@Test
	void refusesAnInsightIdThatDoesNotExist() throws Exception {
		String madeUp = "made-up-" + UUID.randomUUID();
		Session session = socket(madeUp, mock(RemoteEndpoint.Basic.class));

		endpoint.onOpen(session, config(signedIn(), httpSession));

		assertRefused(session, NOT_THIS_SESSIONS);
		assertNull(InsightStore.getInstance().get(madeUp));
	}

	@Test
	void opensAFreshInsightOfItsOwnSessionForNew() {
		String otherHttpSession = newHttpSession();
		Session first = socket("new", mock(RemoteEndpoint.Basic.class));
		Session second = socket("new", mock(RemoteEndpoint.Basic.class));

		endpoint.onOpen(first, config(signedIn(), httpSession));
		endpoint.onOpen(second, config(signedIn(), otherHttpSession));

		String firstId = (String) first.getUserProperties().get("INSIGHT_ID");
		String secondId = (String) second.getUserProperties().get("INSIGHT_ID");
		createdInsights.add(firstId);
		createdInsights.add(secondId);
		assertNotEquals("new", firstId);
		assertNotEquals(firstId, secondId);
		assertTrue(InsightStore.getInstance().getInsightIDsForSession(httpSession).contains(firstId));
		assertTrue(InsightStore.getInstance().getInsightIDsForSession(otherHttpSession).contains(secondId));
		assertFalse(InsightStore.getInstance().getInsightIDsForSession(httpSession).contains(secondId));
	}

	@Test
	void closeRemovesTheSocketFromItsInsight() {
		Session session = open(signedIn(), mock(RemoteEndpoint.Basic.class));

		endpoint.close(session);

		assertTrue(SocketSessionHandlerFactory.getHandler(insightId).isEmpty());
	}

	@Test
	void closeIgnoresARefusedSocket() {
		Session session = socket(insightId, mock(RemoteEndpoint.Basic.class));
		endpoint.onOpen(session, config(null, httpSession));

		endpoint.close(session);

		assertTrue(SocketSessionHandlerFactory.getHandler(insightId).isEmpty());
	}

	@Test
	void watchRequiresAType() throws Exception {
		RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
		Session session = open(signedIn(), remote);

		endpoint.handleMessage("{\"action\":\"watch\"}", session);

		JSONObject error = new JSONObject(lastText(remote));
		assertEquals("error", error.getString("action"));
		assertEquals("watch requires a 'type' field", error.getString("message"));
	}

	@Test
	void watchRejectsAnUnknownStreamerType() throws Exception {
		RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
		Session session = open(signedIn(), remote);

		endpoint.handleMessage("{\"action\":\"watch\",\"type\":\"tail_logs\"}", session);

		assertEquals("Unknown streamer type: tail_logs", new JSONObject(lastText(remote)).getString("message"));
	}

	@Test
	void watchOfAClaudeCodeTranscriptRequiresARoomId() throws Exception {
		RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
		Session session = open(signedIn(), remote);

		endpoint.handleMessage("{\"action\":\"watch\",\"type\":\"claude_code\"}", session);

		assertEquals("watch of claude_code requires a 'roomId' field",
				new JSONObject(lastText(remote)).getString("message"));
	}

	@Test
	void watchRefusesARoomTheUserCannotRead() throws Exception {
		RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
		Session session = open(signedIn(), remote);
		String roomId = "room-" + UUID.randomUUID();
		try (MockedStatic<RoomUtils> rooms = mockStatic(RoomUtils.class)) {
			rooms.when(() -> RoomUtils.getOrLoadRoom(roomId, insight))
					.thenThrow(new IllegalArgumentException("Room is not valid for this user"));

			endpoint.handleMessage("{\"action\":\"watch\",\"type\":\"claude_code\",\"roomId\":\"" + roomId + "\"}",
					session);
		}

		assertEquals("Room does not exist or you do not have access to it",
				new JSONObject(lastText(remote)).getString("message"));
		assertFalse(streamerThreadAlive("streamer-claude_code:" + roomId));
	}

	@Test
	void watchStartsAClaudeCodeStreamerAndUnwatchStopsIt(@TempDir Path base) throws Exception {
		String previous = DIHelper.getInstance().getCoreProp().getProperty(Constants.BASE_FOLDER);
		DIHelper.getInstance().getCoreProp().setProperty(Constants.BASE_FOLDER, base.toString());
		try (MockedStatic<RoomUtils> rooms = mockStatic(RoomUtils.class)) {
			rooms.when(() -> RoomUtils.getOrLoadRoom(anyString(), any())).thenReturn(mock(Room.class));
			watchAndUnwatch(rooms);
		} finally {
			if (previous == null) {
				DIHelper.getInstance().getCoreProp().remove(Constants.BASE_FOLDER);
			} else {
				DIHelper.getInstance().getCoreProp().setProperty(Constants.BASE_FOLDER, previous);
			}
		}
	}

	private void watchAndUnwatch(MockedStatic<RoomUtils> rooms) throws Exception {
		User user = signedIn();
		RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
		Session session = open(user, remote);
		String roomId = "room-" + UUID.randomUUID();

		endpoint.handleMessage("{\"action\":\"watch\",\"type\":\"claude_code\",\"roomId\":\"" + roomId + "\"}", session);

		rooms.verify(() -> RoomUtils.getOrLoadRoom(roomId, insight));
		verify(insight).setUser(user);
		JSONObject ack = new JSONObject(lastText(remote));
		assertEquals("watch_started", ack.getString("action"));
		assertEquals("claude_code", ack.getString("type"));
		assertEquals(roomId, ack.getString("roomId"));
		assertTrue(streamerThreadAlive("streamer-claude_code:" + roomId));

		endpoint.handleMessage("{\"action\":\"unwatch\",\"type\":\"claude_code\",\"roomId\":\"" + roomId + "\"}", session);

		long deadline = System.currentTimeMillis() + 10_000;
		while (streamerThreadAlive("streamer-claude_code:" + roomId) && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertFalse(streamerThreadAlive("streamer-claude_code:" + roomId));
	}

	private static boolean streamerThreadAlive(String name) {
		return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> name.equals(t.getName()) && t.isAlive());
	}

	@Test
	void pixelRunsInTheInsightAsTheSocketUserAndBroadcastsTheResult() throws Exception {
		User user = signedIn();
		RemoteEndpoint.Basic mine = mock(RemoteEndpoint.Basic.class);
		RemoteEndpoint.Basic other = mock(RemoteEndpoint.Basic.class);
		Session session = open(user, mine);
		open(user, other);
		PixelRunner runner = mock(PixelRunner.class);
		when(insight.runPixel("1+1;")).thenReturn(runner);
		try (MockedStatic<PixelStreamUtility> stream = pixelReturns(runner, "{\"pixelReturn\":[2]}")) {
			endpoint.handleMessage("{\"pixel\":\"  1+1  \"}", session);
		}

		verify(insight).setUser(user);
		verify(mine).sendText("{\"pixelReturn\":[2]}");
		verify(other).sendText("{\"pixelReturn\":[2]}");
	}

	@Test
	void pixelKeepsAnExpressionThatAlreadyEndsTheStatement() throws Exception {
		Session session = open(signedIn(), mock(RemoteEndpoint.Basic.class));
		PixelRunner runner = mock(PixelRunner.class);
		when(insight.runPixel(anyString())).thenReturn(runner);
		try (MockedStatic<PixelStreamUtility> stream = mockStatic(PixelStreamUtility.class)) {
			StreamingOutput empty = out -> {
			};
			stream.when(() -> PixelStreamUtility.collectPixelData(any(), any())).thenReturn(empty);

			endpoint.handleMessage("{\"action\":\"pixel\",\"pixel\":\"GetSystemConfig();\"}", session);
		}

		verify(insight).runPixel("GetSystemConfig();");
		verify(insight, never()).runPixel("GetSystemConfig();;");
	}

	@Test
	void pixelRequiresAPixelField() throws Exception {
		RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
		Session session = open(signedIn(), remote);

		endpoint.handleMessage("{}", session);

		assertEquals("pixel requires a 'pixel' field", new JSONObject(lastText(remote)).getString("message"));
		verify(insight, never()).runPixel(anyString());
	}

	@Test
	void onErrorLeavesTheSocketOpen() throws Exception {
		Session session = socket(insightId, mock(RemoteEndpoint.Basic.class));

		endpoint.onError(session, new IllegalStateException("boom"));

		verify(session, never()).close();
	}
}
