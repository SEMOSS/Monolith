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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.websocket.Session;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SocketSessionHandler {

	private static final Logger classLogger = LogManager.getLogger(SocketSessionHandler.class);

	private final List<Session> sessions = new CopyOnWriteArrayList<>();

	/**
	 * Active streamers keyed by a unique key (e.g. "claude_code:roomId" or a file
	 * path)
	 */
	private final Map<String, FileStreamer> streamers = new ConcurrentHashMap<>();
	/** Threads running the streamers */
	private final Map<String, Thread> streamerThreads = new ConcurrentHashMap<>();

	public void addSession(Session session) {
		sessions.add(session);
	}

	public void removeSession(Session session) {
		sessions.remove(session);

		// If no clients remain, stop all streamers for this handler
		if (sessions.isEmpty()) {
			stopAllStreamers();
		}
	}

	/**
	 * Start a streamer if one isn't already running for the given key.
	 *
	 * @param key      unique identifier for this streamer (e.g.
	 *                 "claude_code:abc-123")
	 * @param streamer the FileStreamer instance to run
	 */
	public void startStreamer(String key, FileStreamer streamer) {
		if (streamers.containsKey(key)) {
			classLogger.info("Streamer already running for key={}", key);
			return;
		}

		streamers.put(key, streamer);

		Thread thread = new Thread(streamer::start, "streamer-" + key);
		thread.setDaemon(true);
		thread.start();
		streamerThreads.put(key, thread);

		classLogger.info("Started streamer for key={}", key);
	}

	/** Stop a specific streamer by key. */
	public void stopStreamer(String key) {
		FileStreamer streamer = streamers.remove(key);
		if (streamer != null) {
			streamer.stop();
		}
		Thread thread = streamerThreads.remove(key);
		if (thread != null) {
			thread.interrupt();
		}
		classLogger.info("Stopped streamer for key={}", key);
	}

	/** Stop all active streamers (called when last client disconnects). */
	public void stopAllStreamers() {
		// Copy keys to avoid ConcurrentModificationException
		for (String key : streamers.keySet().toArray(new String[0])) {
			stopStreamer(key);
		}
	}

	public boolean isEmpty() {
		return sessions.isEmpty();
	}

	private void sendReturnData(String message) {
		for (Session session : sessions) {
			try {
				session.getBasicRemote().sendText(message);
			} catch (IOException e) {
				removeSession(session);
			}
		}
	}

	public void updateRecipe(String message) {
		sendReturnData(message);
	}

}
