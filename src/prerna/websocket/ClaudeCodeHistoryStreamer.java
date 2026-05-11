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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.util.Utility;

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
	 *
	 * Inlined (not delegating to ClaudeCodeTranscriptLocator in Semoss) so this
	 * Monolith class doesn't depend on a Semoss-side class that may not yet be
	 * present in the published Semoss JAR at CI build time.
	 */
	private Path findJsonlFile() {
		if (roomId == null || roomId.isEmpty()) {
			return null;
		}

		String roomFolderPath = Utility.getBaseFolder() + File.separator + "room" + File.separator + roomId;
		Path rootDir = Paths.get(roomFolderPath);

		if (!Files.isDirectory(rootDir)) {
			return null;
		}

		String targetFileName = roomId + ".jsonl";

		try (Stream<Path> walk = Files.walk(rootDir)) {
			return walk
					.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().equals(targetFileName))
					.findFirst()
					.orElse(null);
		} catch (IOException e) {
			return null;
		}
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
	 * Tail the JSONL file by re-stat + re-open on each cycle.
	 *
	 * A single long-lived RandomAccessFile handle does not see appends
	 * from another process on CSI/NFS-backed volumes (common on Kubernetes):
	 * the NFS client caches file attributes per-open-handle and readLine
	 * returns null forever against the stale EOF. Re-stat via Files.size
	 * + fresh open each cycle bypasses that cache and works on every
	 * filesystem we target.
	 */
	private void tailFile(Path filePath) {
		long lastOffset;
		try {
			lastOffset = Files.size(filePath);
		} catch (IOException e) {
			logger.warn("Could not stat {} on startup, starting from offset 0", filePath);
			lastOffset = 0;
		}
		logger.info("Tailing {} for insightId={} (start offset={})", filePath, insightId, lastOffset);

		while (running) {
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}

			long currentSize;
			try {
				currentSize = Files.size(filePath);
			} catch (IOException e) {
				logger.warn("Could not stat {}: {}", filePath, e.toString());
				continue;
			}

			if (currentSize < lastOffset) {
				logger.info("File {} truncated or rotated, resetting offset", filePath);
				lastOffset = 0;
			}

			if (currentSize == lastOffset) {
				continue;
			}

			lastOffset = readNewLines(filePath, lastOffset, currentSize);
		}
	}

	/**
	 * Read bytes from {@code fromOffset} up to {@code toOffset}, process every
	 * complete line (terminated by {@code \n}), and return the offset just
	 * after the last complete line. Any trailing bytes past the final newline
	 * stay on disk and are re-read on the next cycle once more data arrives.
	 */
	private long readNewLines(Path filePath, long fromOffset, long toOffset) {
		int length = (int) (toOffset - fromOffset);
		byte[] buf = new byte[length];

		try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
			raf.seek(fromOffset);
			raf.readFully(buf);
		} catch (IOException e) {
			logger.error("Error reading {}", filePath, e);
			return fromOffset;
		}

		int lastNewline = -1;
		for (int i = length - 1; i >= 0; i--) {
			if (buf[i] == '\n') {
				lastNewline = i;
				break;
			}
		}

		if (lastNewline < 0) {
			return fromOffset;
		}

		String complete = new String(buf, 0, lastNewline, StandardCharsets.UTF_8);
		for (String line : complete.split("\n")) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				processLine(trimmed);
			}
		}

		return fromOffset + lastNewline + 1;
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
