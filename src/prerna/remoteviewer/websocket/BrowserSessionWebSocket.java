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
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.microsoft.playwright.Page;

import prerna.auth.User;
import prerna.remoteviewer.model.BrowserInputEvent;
import prerna.remoteviewer.service.BrowserInputService;
import prerna.remoteviewer.service.BrowserRecordingService;
import prerna.remoteviewer.service.BrowserSession;
import prerna.remoteviewer.service.BrowserSessionManager;
import prerna.remoteviewer.service.FrameSender;
import prerna.remoteviewer.security.InputEventValidator;
import prerna.util.Constants;

/**
 * WebSocket endpoint for a single remote browser session.
 *
 * <p>Path: {@code /browserSocket/{sessionId}}
 *
 * <p>The endpoint drives a dedicated Playwright event-loop thread that:
 * <ol>
 *   <li>Drains the incoming event queue and dispatches each event via {@link BrowserInputService}</li>
 *   <li>Takes a viewport screenshot</li>
 *   <li>Encodes the screenshot as base64 JPEG</li>
 *   <li>Sends a {@code frame} message to the React client over the WebSocket</li>
 * </ol>
 *
 * <p>The screenshot-based streaming loop runs at roughly {@value #TARGET_FPS} fps.
 * This design is modular — replace the screenshot call with a CDP screencast for lower latency.
 */
@ServerEndpoint(value = "/browserSocket/{sessionId}", configurator = BrowserWSConfigurator.class)
public class BrowserSessionWebSocket {

	private static final Logger classLogger = LogManager.getLogger(BrowserSessionWebSocket.class);
	private static final Gson GSON = new Gson();

	/** Target frames per second for screenshot streaming. */
	private static final int TARGET_FPS = 15;
	private static final long FRAME_INTERVAL_MS = 1000L / TARGET_FPS;

	// ---- WebSocket lifecycle ----

	@OnOpen
	public void onOpen(Session wsSession, EndpointConfig config,
			@PathParam("sessionId") String sessionId) {
		User user = (User) config.getUserProperties().get(Constants.SESSION_USER);
		if (user == null) {
			closeWithPolicy(wsSession, "Authentication required");
			return;
		}

		Optional<BrowserSession> opt = BrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			closeWithPolicy(wsSession, "Session not found: " + sessionId);
			return;
		}

		BrowserSession session = opt.get();
		if (!session.getUserId().equals(user.getPrimaryLoginToken().getId())) {
			closeWithPolicy(wsSession, "Access denied");
			return;
		}

		// Bind the FrameSender and start the streaming thread
		session.setFrameSender(json -> {
			try { wsSession.getBasicRemote().sendText(json); }
			catch (IOException e) { classLogger.debug("WS send failed: {}", e.getMessage()); }
		});
		session.setWsConnected(true);

		Thread loopThread = new Thread(() -> runSessionLoop(session), "BrowserLoop-" + sessionId);
		loopThread.setDaemon(true);
		session.setSessionThread(loopThread);
		loopThread.start();

		classLogger.info("WebSocket opened for browser session {} by user {}", sessionId, user.getPrimaryLoginToken().getId());
	}

	@OnMessage
	public void onMessage(String message, Session wsSession,
			@PathParam("sessionId") String sessionId) {
		Optional<BrowserSession> opt = BrowserSessionManager.getInstance().getSession(sessionId);
		if (opt.isEmpty()) {
			return;
		}

		BrowserSession session = opt.get();
		session.touchActivity();

		BrowserInputEvent event;
		try {
			event = GSON.fromJson(message, BrowserInputEvent.class);
		} catch (Exception e) {
			classLogger.warn("Failed to parse WebSocket message: {}", e.getMessage());
			sendErrorDirect(wsSession, "Invalid event format");
			return;
		}

		try {
			InputEventValidator.validate(event, session.getViewportWidth(), session.getViewportHeight());
		} catch (IllegalArgumentException e) {
			sendErrorDirect(wsSession, "Invalid event: " + e.getMessage());
			return;
		}

		// Close-session event — handled directly
		if ("close-session".equals(event.getType())) {
			BrowserSessionManager.getInstance().closeSession(sessionId);
			return;
		}

		// All other events: enqueue for the Playwright thread
		boolean offered = session.eventQueue.offer(event);
		if (!offered) {
			classLogger.warn("Event queue full for session {} — dropping event {}", sessionId, event.getType());
		}
	}

	@OnClose
	public void onClose(Session wsSession, CloseReason reason,
			@PathParam("sessionId") String sessionId) {
		classLogger.info("WebSocket closed for session {}: {}", sessionId, reason.getReasonPhrase());
		BrowserSessionManager.getInstance().getSession(sessionId)
				.ifPresent(s -> { s.setWsConnected(false); s.setFrameSender(null); });
	}

	@OnError
	public void onError(Session wsSession, Throwable error,
			@PathParam("sessionId") String sessionId) {
		classLogger.error("WebSocket error for session {}: {}", sessionId, error.getMessage(), error);
	}

	// ---- Playwright event loop (runs on the session's dedicated thread) ----

	private void runSessionLoop(BrowserSession session) {
		String sessionId = session.getSessionId();
		Page page = session.getPage();

		// Send initial navigation event
		sendNavigated(session, safeUrl(page));

		while (!session.isClosed() && !Thread.currentThread().isInterrupted()) {
			long loopStart = System.currentTimeMillis();

			try {
				// 1. Drain and process all pending input events
				processEventQueue(session);

				// 2. Check if the page is still open
				if (page.isClosed()) {
					break;
				}

				// 3. Take a screenshot and send it as a frame
				sendFrame(session, page);

				// 4. Check for URL change after events were processed
				sendNavigatedIfChanged(session, page);

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				classLogger.warn("Session loop error for {}: {}", sessionId, e.getMessage());
				sendError(session, "Browser error: " + e.getMessage());
				// Brief pause before retrying to avoid tight error loops
				try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
			}

			// Throttle to target FPS
			long elapsed = System.currentTimeMillis() - loopStart;
			long sleep = FRAME_INTERVAL_MS - elapsed;
			if (sleep > 0) {
				try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
			}
		}

		classLogger.info("Session loop ended for {}", sessionId);
	}

	private void processEventQueue(BrowserSession session) throws InterruptedException {
		// Drain all currently queued events (non-blocking after the first poll)
		BrowserInputEvent event;
		while ((event = session.eventQueue.poll()) != null) {
			BrowserInputService.dispatch(session, event);
			BrowserRecordingService.record(session, event);
			session.touchActivity();
		}
	}

	// ---- Frame streaming ----

	private void sendFrame(BrowserSession session, Page page) {
		FrameSender sender = session.getFrameSender();
		if (sender == null || !session.isWsConnected()) {
			return;
		}

		byte[] buf;
		try {
			buf = page.screenshot(new Page.ScreenshotOptions()
					.setFullPage(false)
					.setType(com.microsoft.playwright.options.ScreenshotType.JPEG)
					.setQuality(75));
		} catch (Exception e) {
			classLogger.debug("Screenshot failed for session {}: {}", session.getSessionId(), e.getMessage());
			return;
		}

		String b64 = Base64.getEncoder().encodeToString(buf);

		Map<String, Object> frame = Map.of(
				"type", "frame",
				"data", b64,
				"metadata", Map.of(
						"width", session.getViewportWidth(),
						"height", session.getViewportHeight(),
						"pageScaleFactor", 1));

		sender.send(GSON.toJson(frame));
	}

	private String lastSentUrl = "";

	private void sendNavigatedIfChanged(BrowserSession session, Page page) {
		String currentUrl = safeUrl(page);
		if (!currentUrl.equals(lastSentUrl)) {
			lastSentUrl = currentUrl;
			sendNavigated(session, currentUrl);
		}
	}

	private void sendNavigated(BrowserSession session, String url) {
		FrameSender sender = session.getFrameSender();
		if (sender != null && session.isWsConnected()) {
			sender.send(GSON.toJson(Map.of("type", "navigated", "url", url)));
		}
	}

	// ---- Helpers ----

	private static void sendError(BrowserSession session, String message) {
		FrameSender sender = session.getFrameSender();
		if (sender != null && session.isWsConnected()) {
			sender.send(GSON.toJson(Map.of("type", "error", "message", message)));
		}
	}

	/** Send an error directly over a raw WebSocket Session (used before BrowserSession is bound). */
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

	private static String safeUrl(Page page) {
		try {
			return page.url();
		} catch (Exception e) {
			return "";
		}
	}
}
