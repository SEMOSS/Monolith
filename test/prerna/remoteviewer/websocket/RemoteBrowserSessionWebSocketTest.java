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
package prerna.remoteviewer.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import prerna.auth.AccessToken;
import prerna.auth.User;
import prerna.remoteviewer.model.RemoteBrowserInputEvent;
import prerna.remoteviewer.service.RemoteBrowserFrameSender;
import prerna.remoteviewer.service.RemoteBrowserSession;
import prerna.remoteviewer.service.RemoteBrowserSessionManager;
import prerna.util.Constants;

class RemoteBrowserSessionWebSocketTest {

	private final RemoteBrowserSessionWebSocket endpoint = new RemoteBrowserSessionWebSocket();
	private RemoteBrowserSessionManager manager;
	private MockedStatic<RemoteBrowserSessionManager> managerStatic;
	private RemoteBrowserSession browser;
	private BlockingQueue<RemoteBrowserInputEvent> queue;
	private Session socket;
	private RemoteEndpoint.Basic remote;

	@BeforeEach
	void prepare() throws Exception {
		manager = mock(RemoteBrowserSessionManager.class);
		managerStatic = mockStatic(RemoteBrowserSessionManager.class);
		managerStatic.when(RemoteBrowserSessionManager::getInstance).thenReturn(manager);
		browser = mock(RemoteBrowserSession.class);
		when(browser.getUserId()).thenReturn("owner");
		when(browser.getViewportWidth()).thenReturn(800);
		when(browser.getViewportHeight()).thenReturn(600);
		queue = new LinkedBlockingQueue<>(1);
		Field eventQueue = RemoteBrowserSession.class.getField("eventQueue");
		eventQueue.setAccessible(true);
		eventQueue.set(browser, queue);
		when(manager.getSession("s1")).thenReturn(Optional.of(browser));
		when(manager.getSession("missing")).thenReturn(Optional.empty());
		remote = mock(RemoteEndpoint.Basic.class);
		socket = mock(Session.class);
		when(socket.getBasicRemote()).thenReturn(remote);
	}

	@AfterEach
	void release() {
		managerStatic.close();
	}

	private static EndpointConfig config(User user) {
		EndpointConfig config = mock(EndpointConfig.class);
		Map<String, Object> properties = new HashMap<>();
		if (user != null) {
			properties.put(Constants.SESSION_USER, user);
		}
		when(config.getUserProperties()).thenReturn(properties);
		return config;
	}

	private static User user(String id) {
		AccessToken token = new AccessToken();
		token.setId(id);
		User user = mock(User.class);
		when(user.getPrimaryLoginToken()).thenReturn(token);
		return user;
	}

	private CloseReason closedWith() throws Exception {
		ArgumentCaptor<CloseReason> reason = ArgumentCaptor.forClass(CloseReason.class);
		verify(socket).close(reason.capture());
		assertEquals(CloseReason.CloseCodes.VIOLATED_POLICY, reason.getValue().getCloseCode());
		return reason.getValue();
	}

	private JSONObject lastSent() throws Exception {
		ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
		verify(remote).sendText(sent.capture());
		return new JSONObject(sent.getValue());
	}

	@Test
	void onOpenClosesAnUnauthenticatedSocket() throws Exception {
		endpoint.onOpen(socket, config(null), "s1");

		assertEquals("Authentication required", closedWith().getReasonPhrase());
		verify(browser, never()).setWsConnected(true);
	}

	@Test
	void onOpenClosesASocketForAnUnknownSession() throws Exception {
		endpoint.onOpen(socket, config(user("owner")), "missing");

		assertEquals("Session not found: missing", closedWith().getReasonPhrase());
	}

	@Test
	void onOpenClosesASocketOfAnotherUser() throws Exception {
		endpoint.onOpen(socket, config(user("intruder")), "s1");

		assertEquals("Access denied", closedWith().getReasonPhrase());
		verify(browser, never()).setRemoteBrowserFrameSender(any());
		verify(browser, never()).setWsConnected(true);
	}

	@Test
	void onOpenBindsTheFrameSenderForTheOwner() throws Exception {
		endpoint.onOpen(socket, config(user("owner")), "s1");

		ArgumentCaptor<RemoteBrowserFrameSender> sender = ArgumentCaptor.forClass(RemoteBrowserFrameSender.class);
		verify(browser).setRemoteBrowserFrameSender(sender.capture());
		verify(browser).setWsConnected(true);
		verify(manager).sendTabState(browser);
		verify(socket, never()).close(any());

		sender.getValue().send("{\"type\":\"frame\"}");
		verify(remote).sendText("{\"type\":\"frame\"}");
	}

	@Test
	void onMessageIgnoresAnUnknownSession() throws Exception {
		endpoint.onMessage("{\"type\":\"reload\"}", socket, "missing");

		verify(remote, never()).sendText(any());
	}

	@Test
	void onMessageAnswersMalformedJsonWithAnError() throws Exception {
		endpoint.onMessage("{not json", socket, "s1");

		JSONObject error = lastSent();
		assertEquals("error", error.getString("type"));
		assertEquals("Invalid event format", error.getString("message"));
		verify(browser).touchActivity();
	}

	@Test
	void onMessageAnswersAnInvalidEventWithTheReason() throws Exception {
		endpoint.onMessage("{\"type\":\"format-disk\"}", socket, "s1");

		assertEquals("Invalid event: Unsupported event type: format-disk", lastSent().getString("message"));
		assertTrue(queue.isEmpty());
	}

	@Test
	void onMessageFinishesTheSessionOnCloseSession() throws Exception {
		endpoint.onMessage("{\"type\":\"close-session\"}", socket, "s1");

		verify(manager).finishSession("s1");
		assertTrue(queue.isEmpty());
	}

	@Test
	void onMessageQueuesValidEventsAndDropsThemWhenTheQueueIsFull() throws Exception {
		endpoint.onMessage("{\"type\":\"reload\"}", socket, "s1");
		endpoint.onMessage("{\"type\":\"navigate-back\"}", socket, "s1");

		assertEquals(1, queue.size());
		assertEquals("reload", queue.peek().getType());
		verify(remote, never()).sendText(any());
	}

	@Test
	void onCloseDisconnectsTheViewerAndDiscardsTheUnsavedRecording() {
		endpoint.onClose(socket, new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "bye"), "s1");
		endpoint.onClose(socket, new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "bye"), "missing");

		verify(browser).setRecordingEnabled(false);
		verify(browser).clearRecordingBuffer();
		verify(browser).setWsConnected(false);
		verify(browser).setRemoteBrowserFrameSender(null);
	}

	@Test
	void onErrorLeavesTheSessionAlone() throws Exception {
		endpoint.onError(socket, new IllegalStateException("boom"), "s1");

		verify(socket, never()).close(any());
		verify(manager, never()).finishSession("s1");
	}
}
