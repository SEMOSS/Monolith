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

import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.StreamingOutput;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.auth.User;
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

	@OnOpen
	public void onOpen(Session session, EndpointConfig config) {
		classLogger.info("Creating new socket session");
		User user = (User) config.getUserProperties().get(Constants.SESSION_USER);
		if (user == null) {
			throw new IllegalAccessError("User session is invalid");
		}

		Map<String, List<String>> params = session.getRequestParameterMap();
		List<String> id = params.get("insightId");
		if (id == null || id.isEmpty()) {
			throw new IllegalAccessError("Must pass in insightId");
		}
		String insightId = id.get(0);
		{
			Insight in = InsightStore.getInstance().get(insightId);
			if (in == null) {
				in = new Insight();
				in.setInsightId(insightId);
				InsightStore.getInstance().put(in);
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
		SocketSessionHandlerFactory.getHandler(insightId).removeSession(session);
	}

	@OnError
	public void onError(Session session, Throwable error) {
		classLogger.error("WebSocket error for session {}", session.getId(), error);
	}

	@OnMessage
	public void handleMessage(String message, Session session) {
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

		String pixelString = json.getString("pixel").trim();
		if (!pixelString.endsWith(";")) {
			pixelString = pixelString + ";";
		}

		Insight in = null;
		if (insightId == null || (insightId = insightId.trim()).isEmpty() || insightId.equalsIgnoreCase("new")) {
			in = new Insight();
			InsightStore.getInstance().put(in);
		} else {
			in = InsightStore.getInstance().get(insightId);
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
