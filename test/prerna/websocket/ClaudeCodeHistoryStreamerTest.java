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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.function.Function;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import prerna.util.Constants;
import prerna.util.DIHelper;

class ClaudeCodeHistoryStreamerTest {

	private static final Function<JSONObject, JSONObject> SEEN = raw -> raw.has("skip") ? null
			: new JSONObject().put("seen", raw.get("n"));

	@TempDir
	Path base;

	private String previousBase;
	private String insightId;
	private String roomId;
	private Session socket;
	private RemoteEndpoint.Basic remote;
	private Thread thread;
	private ClaudeCodeHistoryStreamer streamer;

	@BeforeEach
	void prepare() {
		previousBase = DIHelper.getInstance().getCoreProp().getProperty(Constants.BASE_FOLDER);
		DIHelper.getInstance().getCoreProp().setProperty(Constants.BASE_FOLDER, base.toString());
		insightId = "tail-" + UUID.randomUUID();
		roomId = "room-" + UUID.randomUUID();
		remote = mock(RemoteEndpoint.Basic.class);
		socket = mock(Session.class);
		when(socket.getBasicRemote()).thenReturn(remote);
		SocketSessionHandlerFactory.getHandler(insightId).addSession(socket);
	}

	@AfterEach
	void cleanUp() throws Exception {
		if (streamer != null) {
			streamer.stop();
		}
		if (thread != null) {
			thread.interrupt();
			thread.join(5000);
		}
		SocketSessionHandlerFactory.getHandler(insightId).removeSession(socket);
		if (previousBase == null) {
			DIHelper.getInstance().getCoreProp().remove(Constants.BASE_FOLDER);
		} else {
			DIHelper.getInstance().getCoreProp().setProperty(Constants.BASE_FOLDER, previousBase);
		}
	}

	private Path transcript() throws Exception {
		Path folder = Files.createDirectories(base.resolve("room").resolve(roomId).resolve("projects").resolve("x"));
		return folder.resolve(roomId + ".jsonl");
	}

	private static void append(Path file, String text) throws Exception {
		Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private void startStreaming(String room) throws Exception {
		streamer = new ClaudeCodeHistoryStreamer(room, insightId, SEEN);
		thread = new Thread(streamer::start, "test-tail");
		thread.setDaemon(true);
		thread.start();
		long deadline = System.currentTimeMillis() + 5000;
		while (!streamer.isRunning() && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertTrue(streamer.isRunning());
	}

	@Test
	void isNotRunningBeforeItStarts() {
		assertFalse(new ClaudeCodeHistoryStreamer(roomId, insightId, SEEN).isRunning());
	}

	@Test
	void streamsCompleteLinesWrittenAfterTheWatchStarts() throws Exception {
		Path file = transcript();
		append(file, "{\"n\":\"history\"}\n");
		startStreaming(roomId);
		Thread.sleep(300);

		append(file, "{\"n\":1}\n{\"skip\":true}\nnot json\n{\"n\":2");
		verify(remote, timeout(8000)).sendText("{\"seen\":1}");

		append(file, "}\n");
		verify(remote, timeout(8000)).sendText("{\"seen\":2}");
		verify(remote, never()).sendText(contains("history"));
		verify(remote, never()).sendText(contains("skip"));
	}

	@Test
	void restartsFromTheTopAfterTheTranscriptIsTruncated() throws Exception {
		Path file = transcript();
		append(file, "{\"n\":\"a long line of history that will be truncated away\"}\n");
		startStreaming(roomId);
		Thread.sleep(300);

		Files.writeString(file, "{\"n\":9}\n", StandardCharsets.UTF_8);

		verify(remote, timeout(8000)).sendText("{\"seen\":9}");
	}

	@Test
	void waitsForATranscriptThatDoesNotExistYet() throws Exception {
		startStreaming(roomId);
		Path file = transcript();
		long deadline = System.currentTimeMillis() + 15_000;
		int line = 0;
		while (mockingDetails(remote).getInvocations().isEmpty() && System.currentTimeMillis() < deadline) {
			append(file, "{\"n\":" + line++ + "}\n");
			Thread.sleep(400);
		}

		assertFalse(mockingDetails(remote).getInvocations().isEmpty());
	}

	@Test
	void stopEndsTheWaitWithinOnePollInterval() throws Exception {
		startStreaming("..");

		streamer.stop();
		thread.join(5000);

		assertFalse(thread.isAlive());
		assertFalse(streamer.isRunning());
	}
}
