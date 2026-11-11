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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import jakarta.websocket.CloseReason;
import jakarta.websocket.CloseReason.CloseCodes;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.engine.impl.model.RoomUtils;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.reactor.agent.ClaudeCodeTranscriptParser;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.util.Constants;

@ServerEndpoint(value = "/insightSocket", configurator = WSConfigurator.class)
public class InsightWebsocket {

	private static final Logger classLogger = LogManager.getLogger(InsightWebsocket.class);
	private static final String INSIGHT_ID = "INSIGHT_ID";

	/**
	 * Binds a socket to an insight of the HTTP session that opened it. The insight
	 * must already belong to that session, or be {@code new}, which opens a fresh
	 * insight for the session. Any other socket is closed, so one user can never
	 * run pixels in, or receive the output of, another session's insight.
	 *
	 * @param session the socket
	 * @param config  the handshake properties {@link WSConfigurator} set
	 */
	@OnOpen
	public void onOpen(Session session, EndpointConfig config) {
		classLogger.info("Creating new socket session");
		User user = (User) config.getUserProperties().get(Constants.SESSION_USER);
		String httpSessionId = (String) config.getUserProperties().get(WSConfigurator.HTTP_SESSION_ID);
		if (user == null || user.getLogins().isEmpty() || httpSessionId == null) {
			refuse(session, "User session is invalid");
			return;
		}

		Map<String, List<String>> params = session.getRequestParameterMap();
		List<String> id = params.get("insightId");
		String insightId = id == null || id.isEmpty() || id.get(0) == null ? "" : id.get(0).trim();
		if (insightId.isEmpty()) {
			refuse(session, "Must pass in insightId");
			return;
		}
		if (insightId.equalsIgnoreCase("new")) {
			Insight in = new Insight();
			InsightStore.getInstance().put(in);
			InsightStore.getInstance().addToSessionHash(httpSessionId, in.getInsightId());
			insightId = in.getInsightId();
		} else {
			Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(httpSessionId);
			if (sessionInsights == null || !sessionInsights.contains(insightId)
					|| InsightStore.getInstance().get(insightId) == null) {
				refuse(session, "Insight does not exist or does not belong to this session");
				return;
			}
		}
		session.getUserProperties().put(Constants.SESSION_USER, user);
		session.getUserProperties().put(INSIGHT_ID, insightId);

		SocketSessionHandlerFactory.getHandler(insightId).addSession(session);
	}

	@OnClose
	public void close(Session session) {
		classLogger.info("Closing socket session");
		String insightId = (String) session.getUserProperties().get(INSIGHT_ID);
		if (insightId != null) {
			SocketSessionHandlerFactory.getHandler(insightId).removeSession(session);
		}
	}

	private static void refuse(Session session, String reason) {
		classLogger.warn("Refusing insight socket {}: {}", session.getId(), reason);
		try {
			session.close(new CloseReason(CloseCodes.VIOLATED_POLICY, reason));
		} catch (IOException e) {
			classLogger.error("Failed to close refused insight socket {}", session.getId(), e);
		}
	}

	@OnError
	public void onError(Session session, Throwable error) {
		classLogger.error("WebSocket error for session {}", session.getId(), error);
	}

	@OnMessage
	public void handleMessage(String message, Session session) {
		if (session.getUserProperties().get(INSIGHT_ID) == null) {
			sendError(session, "Socket is not bound to an insight");
			return;
		}
		JSONObject json = new JSONObject(message);
		String action = json.optString("action", "pixel");

		switch (action) {
		case "watch":
			handleWatch(json, session);
			break;
		case "unwatch":
			handleUnwatch(json, session);
			break;
		case "pixel":
		default:
			handlePixel(json, session);
			break;
		}
	}

	/**
	 * Start a streamer based on the "type" field in the message.
	 *
	 * Message format: { "action": "watch", "type": "claude_code", "roomId":
	 * "abc-123" }
	 */
	private void handleWatch(JSONObject json, Session session) {
		String insightId = (String) session.getUserProperties().get(INSIGHT_ID);
		String type = json.optString("type", "");
		String roomId = json.optString("roomId", "");

		if (type.isEmpty()) {
			sendError(session, "watch requires a 'type' field");
			return;
		}

		if (type.equals("claude_code")) {
			if (roomId.isEmpty()) {
				sendError(session, "watch of claude_code requires a 'roomId' field");
				return;
			}
			if (!canAccessRoom(session, roomId)) {
				sendError(session, "Room does not exist or you do not have access to it");
				return;
			}
		}
		FileStreamer streamer = createStreamer(type, json, insightId);
		if (streamer == null) {
			sendError(session, "Unknown streamer type: " + type);
			return;
		}

		String streamerKey = type + ":" + roomId;
		SocketSessionHandler handler = SocketSessionHandlerFactory.getHandler(insightId);
		handler.startStreamer(streamerKey, streamer);

		// Acknowledge the watch start
		JSONObject ack = new JSONObject();
		ack.put("action", "watch_started");
		ack.put("type", type);
		if (!roomId.isEmpty()) {
			ack.put("roomId", roomId);
		}
		try {
			session.getBasicRemote().sendText(ack.toString());
		} catch (IOException e) {
			classLogger.error("Failed to send watch ack", e);
		}
	}

	/**
	 * Create the appropriate FileStreamer for the given type. Add new streamer
	 * types here as simple cases.
	 */
	private FileStreamer createStreamer(String type, JSONObject json, String insightId) {
		switch (type) {
		case "claude_code": {
			String roomId = json.getString("roomId");
			return new ClaudeCodeHistoryStreamer(roomId, insightId, ClaudeCodeTranscriptParser::parse);
		}
		default:
			return null;
		}
	}

	/**
	 * Checks that the socket's user may read a room, the same way the room's
	 * pixels do, before its transcript is streamed.
	 *
	 * @param session the socket
	 * @param roomId  the room to watch
	 * @return {@code true} when the room exists and belongs to the user
	 */
	private static boolean canAccessRoom(Session session, String roomId) {
		User user = (User) session.getUserProperties().get(Constants.SESSION_USER);
		Insight in = InsightStore.getInstance().get((String) session.getUserProperties().get(INSIGHT_ID));
		if (user == null || in == null) {
			return false;
		}
		in.setUser(user);
		try {
			RoomUtils.getOrLoadRoom(roomId, in);
			return true;
		} catch (RuntimeException e) {
			classLogger.warn("Refusing to stream room {} to socket {}", roomId, session.getId(), e);
			return false;
		}
	}

	/**
	 * Stop a streamer by type and roomId.
	 *
	 * Message format: { "action": "unwatch", "type": "claude_code", "roomId":
	 * "abc-123" }
	 */
	private void handleUnwatch(JSONObject json, Session session) {
		String insightId = (String) session.getUserProperties().get(INSIGHT_ID);
		String type = json.optString("type", "");
		String roomId = json.optString("roomId", "");
		String streamerKey = type + ":" + roomId;

		SocketSessionHandler handler = SocketSessionHandlerFactory.getHandler(insightId);
		handler.stopStreamer(streamerKey);
	}

	/**
	 * Run a pixel expression (original behavior).
	 *
	 * Message format: { "action": "pixel", (optional - this is the default)
	 * "insightId": "...", "pixel": "GetSystemConfig();" }
	 */
	private void handlePixel(JSONObject json, Session session) {
		User user = (User) session.getUserProperties().get(Constants.SESSION_USER);
		String insightId = (String) session.getUserProperties().get(INSIGHT_ID);

		String pixelString = json.optString("pixel", "").trim();
		if (pixelString.isEmpty()) {
			sendError(session, "pixel requires a 'pixel' field");
			return;
		}
		if (!pixelString.endsWith(";")) {
			pixelString = pixelString + ";";
		}

		Insight in = InsightStore.getInstance().get(insightId);
		if (in == null) {
			sendError(session, "Insight " + insightId + " no longer exists");
			return;
		}
		// set the user
		in.setUser(user);

		PixelRunner runner = in.runPixel(pixelString);
		StreamingOutput streamingOutput = PixelStreamUtility.collectPixelData(runner, null);
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			streamingOutput.write(baos);
		} catch (WebApplicationException e) {
			classLogger.error("Failed to write the pixel stream output for insight {}", insightId, e);
		} catch (IOException e) {
			classLogger.error("Failed to write the pixel stream output for insight {}", insightId, e);
		}
		String returnData = new String(baos.toByteArray());
		SocketSessionHandler handler = SocketSessionHandlerFactory.getHandler(insightId);
		handler.updateRecipe(returnData);
	}

	private void sendError(Session session, String errorMessage) {
		try {
			JSONObject error = new JSONObject();
			error.put("action", "error");
			error.put("message", errorMessage);
			session.getBasicRemote().sendText(error.toString());
		} catch (IOException e) {
			classLogger.error("Failed to send error to client", e);
		}
	}
}
