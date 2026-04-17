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
import java.io.RandomAccessFile;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.reactor.agent.ClaudeCodeTranscriptLocator;

public class ClaudeCodeHistoryStreamer implements FileStreamer {

	private static final Logger logger = LogManager.getLogger(ClaudeCodeHistoryStreamer.class);

	/** How often to poll for the file/directory to appear (ms) */
	private static final long POLL_INTERVAL_MS = 2000;

	private final String roomId;
	private final String insightId;
	private final Function<JSONObject, JSONObject> transform;
	private volatile boolean running = false;

	/**
	 * @param roomId     the room whose JSONL transcript to tail
	 * @param insightId  the insightId whose WS clients should receive updates
	 * @param transform  a function that reshapes each raw JSON line before it is
	 *                   sent to the client; return {@code null} to skip a line
	 */
	public ClaudeCodeHistoryStreamer(String roomId, String insightId,
			Function<JSONObject, JSONObject> transform) {
		this.roomId = roomId;
		this.insightId = insightId;
		this.transform = transform;
	}

	/**
	 * Search the room folder for the JSONL file.
	 * Returns null if the room dir or file doesn't exist yet.
	 */
	private Path findJsonlFile() {
		return ClaudeCodeTranscriptLocator.findJsonlFile(roomId);
	}

	/**
	 * Begin tailing the file. If the file doesn't exist yet, polls until it
	 * appears (or until {@link #stop()} is called). Blocks the calling thread.
	 */
	public void start() {
		running = true;

		// Phase 1: Wait for the file to appear
		Path resolvedPath = findJsonlFile();
		if (resolvedPath == null) {
			logger.info("JSONL file for room {} does not exist yet, waiting for it to appear", roomId);
			resolvedPath = waitForFile();
		}

		if (resolvedPath == null) {
			// stop() was called while we were waiting
			logger.info("Stopped waiting for JSONL file (room={})", roomId);
			return;
		}

		// Phase 2: Tail the file
		tailFile(resolvedPath);

		logger.info("Stopped tailing room={}", roomId);
	}

	/**
	 * Poll until the JSONL file appears on disk.
	 * Returns null if stop() is called before the file is found.
	 */
	private Path waitForFile() {
		while (running) {
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}

			Path found = findJsonlFile();
			if (found != null) {
				logger.info("JSONL file appeared: {}", found);
				return found;
			}
		}
		return null;
	}

	/**
	 * Tail the JSONL file, reading new lines as they are appended.
	 *
	 * Uses poll(timeout) instead of take() so we always attempt a read
	 * on each cycle. This avoids a race condition on macOS where the
	 * WatchService (which is poll-based, not native) can miss events
	 * that happen before its internal baseline scan completes.
	 */
	private void tailFile(Path filePath) {
		Path dir = filePath.getParent();

		try (WatchService watcher = FileSystems.getDefault().newWatchService();
				RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {

			dir.register(watcher,
					StandardWatchEventKinds.ENTRY_MODIFY,
					StandardWatchEventKinds.ENTRY_CREATE);

			raf.seek(raf.length());
			logger.info("Tailing {} for insightId={}", filePath, insightId);

			String partialLine = "";

			while (running) {
				WatchKey key;
				try {
					key = watcher.poll(POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}

				if (key != null) {
					key.pollEvents();
					if (!key.reset()) {
						logger.warn("Watch key no longer valid for {}", dir);
						break;
					}
				}

				String line;
				while ((line = raf.readLine()) != null) {
					line = partialLine + line;
					partialLine = "";

					line = line.trim();
					if (line.isEmpty()) {
						continue;
					}

					processLine(line);
				}
			}
		} catch (IOException e) {
			logger.error("Error tailing JSONL file {}", filePath, e);
		}
	}

	/**
	 * Parse a single JSONL line, apply the transform, and broadcast to WS clients.
	 */
	private void processLine(String line) {
		try {
			JSONObject raw = new JSONObject(line);
			JSONObject transformed = transform.apply(raw);

			if (transformed == null) {
				return;
			}

			SocketSessionHandler handler = SocketSessionHandlerFactory.getHandler(insightId);
			handler.updateRecipe(transformed.toString());
		} catch (Exception e) {
			logger.warn("Failed to process JSONL line: {}", line, e);
		}
	}

	/** Signal the tailer to stop after the current poll cycle. */
	public void stop() {
		running = false;
	}

	/** Whether the tailer is currently running. */
	public boolean isRunning() {
		return running;
	}
}
