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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

/**
 * Watches a JSONL file for new lines appended to the end and pushes each
 * transformed line to all WebSocket clients subscribed to a given insightId.
 *
 * <p>Usage:
 * <pre>
 *   // 1. Define a transform — receives raw JSON, returns what the client sees
 *   Function&lt;JSONObject, JSONObject&gt; transform = raw -> {
 *       JSONObject out = new JSONObject();
 *       out.put("type", "event");
 *       out.put("content", raw.optString("content", ""));
 *       out.put("timestamp", raw.optLong("ts", System.currentTimeMillis()));
 *       return out;
 *   };
 *
 *   // 2. Start tailing — this blocks the calling thread
 *   JsonlFileTailer tailer = new JsonlFileTailer(
 *       "/opt/semoss/room/abc-123/events.jsonl",
 *       "insight-id-456",
 *       transform
 *   );
 *   new Thread(tailer::start).start();
 *
 *   // 3. Stop when done
 *   tailer.stop();
 * </pre>
 */
public class JsonlFileTailer implements FileStreamer {

	private static final Logger logger = LogManager.getLogger(JsonlFileTailer.class);

	private final Path filePath;
	private final String insightId;
	private final Function<JSONObject, JSONObject> transform;
	private volatile boolean running = false;

	/**
	 * @param filePath   absolute path to the JSONL file to tail
	 * @param insightId  the insightId whose WS clients should receive updates
	 * @param transform  a function that reshapes each raw JSON line before it is
	 *                   sent to the client; return {@code null} to skip a line
	 */
	public JsonlFileTailer(String filePath, String insightId,
			Function<JSONObject, JSONObject> transform) {
		this.filePath = Path.of(filePath);
		this.insightId = insightId;
		this.transform = transform;
	}

	/**
	 * Begin tailing the file. Blocks the calling thread until {@link #stop()}
	 * is called or the thread is interrupted.
	 */
	public void start() {
		if (!Files.exists(filePath)) {
			logger.error("JSONL file does not exist: {}", filePath);
			return;
		}

		running = true;
		Path dir = filePath.getParent();
		Path fileName = filePath.getFileName();

		try (WatchService watcher = FileSystems.getDefault().newWatchService();
				RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {

			dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);

			// Seek to end — only process lines written after we start
			raf.seek(raf.length());
			logger.info("Tailing {} for insightId={}", filePath, insightId);

			String partialLine = "";

			while (running) {
				WatchKey key;
				try {
					key = watcher.take(); // blocks until a change occurs
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}

				for (WatchEvent<?> event : key.pollEvents()) {
					Path changed = (Path) event.context();
					if (!fileName.equals(changed)) {
						continue;
					}

					// Read all new content from where we left off
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

				if (!key.reset()) {
					logger.warn("Watch key no longer valid for {}", dir);
					break;
				}
			}
		} catch (IOException e) {
			logger.error("Error tailing JSONL file {}", filePath, e);
		}

		logger.info("Stopped tailing {}", filePath);
	}

	/**
	 * Parse a single JSONL line, apply the transform, and broadcast to WS clients.
	 */
	private void processLine(String line) {
		try {
			JSONObject raw = new JSONObject(line);
			JSONObject transformed = transform.apply(raw);

			if (transformed == null) {
				// transform returned null — skip this line
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
