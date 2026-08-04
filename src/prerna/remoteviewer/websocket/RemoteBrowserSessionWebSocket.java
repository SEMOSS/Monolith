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

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;

import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import prerna.auth.User;
import prerna.remoteviewer.model.RemoteBrowserInputEvent;
import prerna.remoteviewer.security.RemoteBrowserInputEventValidator;
import prerna.remoteviewer.service.RemoteBrowserRecordingService;
import prerna.remoteviewer.service.RemoteBrowserSession;
import prerna.remoteviewer.service.RemoteBrowserSessionManager;
import prerna.util.Constants;
import prerna.websocket.WSConfigurator;

/**
 * WebSocket endpoint for a single remote browser session.
 *
 * <p>
 * Path: {@code /browserSocket/{sessionId}}
 *
 * <p>
 * This endpoint is purely the transport for one browser session:
 * <ol>
 * <li>On open, it binds a {@code RemoteBrowserFrameSender} that writes JSON to
 * this socket and flags the viewer as connected.</li>
 * <li>Incoming input events are validated and enqueued on the session's event
 * queue.</li>
 * </ol>
 *
 * <p>
 * The frame-producing loop (drain events, screenshot, push
 * {@code frame}/{@code navigated} messages) lives in
 * {@link RemoteBrowserSessionManager}, which starts it at session creation and
 * only streams once the {@code wsConnected} flag flips true. This endpoint does
 * not run its own loop.
 */
@ServerEndpoint(value = "/browserSocket/{sessionId}", configurator = WSConfigurator.class)
public class RemoteBrowserSessionWebSocket {

	private static final Logger classLogger = LogManager.getLogger(RemoteBrowserSessionWebSocket.class);
	private static final Gson GSON = new Gson();

	@OnOpen
	public void onOpen(Session wsSession, EndpointConfig config, @PathParam("sessionId") String sessionId) {
		User user = (User) config.getUserProperties().get(Constants.SESSION_USER);
		if (user == null) {
			closeWithPolicy(wsSession, "Authentication required");
			return;
		}

		Optional<RemoteBrowserSession> opt = RemoteBrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			closeWithPolicy(wsSession, "Session not found: " + sessionId);
			return;
		}

		RemoteBrowserSession session = opt.get();
		if (!session.getUserId().equals(user.getPrimaryLoginToken().getId())) {
			closeWithPolicy(wsSession, "Access denied");
			return;
		}

		// Bind the RemoteBrowserFrameSender and flag the viewer as connected. The
		// session's event loop (started by RemoteBrowserSessionManager at creation
		// time) is already running and reads these two volatile fields each tick - it
		// will begin streaming frames as soon as they are set.
		session.setRemoteBrowserFrameSender(json -> {
			try {
				wsSession.getBasicRemote().sendText(json);
			} catch (IOException e) {
				classLogger.debug("WS send failed: {}", e.getMessage());
			}
		});
		session.setWsConnected(true);
		RemoteBrowserSessionManager.getInstance().sendTabState(session);

		classLogger.info("WebSocket opened for browser session {} by user {}", sessionId,
				user.getPrimaryLoginToken().getId());
	}

	@OnMessage
	public void onMessage(String message, Session wsSession, @PathParam("sessionId") String sessionId) {
		Optional<RemoteBrowserSession> opt = RemoteBrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			return;
		}

		RemoteBrowserSession session = opt.get();
		session.touchActivity();

		RemoteBrowserInputEvent event;
		try {
			event = GSON.fromJson(message, RemoteBrowserInputEvent.class);
		} catch (Exception e) {
			classLogger.warn("Failed to parse WebSocket message: {}", e.getMessage());
			sendErrorDirect(wsSession, "Invalid event format");
			return;
		}

		try {
			RemoteBrowserInputEventValidator.validate(event, session.getViewportWidth(), session.getViewportHeight());
		} catch (IllegalArgumentException e) {
			sendErrorDirect(wsSession, "Invalid event: " + e.getMessage());
			return;
		}

		// Close-session event - handled directly
		if ("close-session".equals(event.getType())) {
			RemoteBrowserSessionManager.getInstance().finishSession(sessionId);
			return;
		}

		// All other events: enqueue for the Playwright thread
		boolean offered = session.eventQueue.offer(event);
		if (!offered) {
			classLogger.warn("Event queue full for session {} - dropping event {}", sessionId, event.getType());
		}
	}

	@OnClose
	public void onClose(Session wsSession, CloseReason reason, @PathParam("sessionId") String sessionId) {
		classLogger.info("WebSocket closed for session {}: {}", sessionId, reason.getReasonPhrase());
		RemoteBrowserSessionManager.getInstance().getSession(sessionId).ifPresent(s -> {
			RemoteBrowserRecordingService.discardRecording(s);
			s.setWsConnected(false);
			s.setRemoteBrowserFrameSender(null);
		});
	}

	@OnError
	public void onError(Session wsSession, Throwable error, @PathParam("sessionId") String sessionId) {
		classLogger.error("WebSocket error for session {}: {}", sessionId, error.getMessage(), error);
	}

	/**
	 * Send an error directly over a raw WebSocket Session (used before
	 * RemoteBrowserSession is bound).
	 */
	private static void sendErrorDirect(Session ws, String message) {
		try {
			ws.getBasicRemote().sendText(GSON.toJson(Map.of("type", "error", "message", message)));
		} catch (IOException e) {
			classLogger.debug("Failed to send error to WebSocket: {}", e.getMessage());
		}
	}

	private static void closeWithPolicy(Session ws, String reason) {
		try {
			ws.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
		} catch (IOException e) {
			classLogger.debug("Failed to close WebSocket: {}", e.getMessage());
		}
	}
}
