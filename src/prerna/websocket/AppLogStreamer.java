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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.logging.AppLogManager;

/**
 * Tails a project's app log file and broadcasts new lines to WS clients
 * watching that project's insight.
 * <p>
 * Caller-side responsibility: {@code InsightWebsocket} must verify the
 * requesting user is a project owner before constructing this class - this
 * class does not re-check authorization.
 */
public class AppLogStreamer implements FileStreamer {

	private static final Logger classLogger = LogManager.getLogger(AppLogStreamer.class);

	/** How often to poll the file for new content (ms). */
	private static final long POLL_INTERVAL_MS = 2000;

	/** Bytes of history sent immediately on watch, before live tailing begins. ~50 KB. */
	private static final long INITIAL_HISTORY_BYTES = 51_200L;

	/**
	 * Max characters broadcast per line. Some reactors log entire response
	 * payloads through EngineLogger as a single line (seen: 150K+ chars) -
	 * without a cap, one such line dominates the whole history window and blows
	 * up a single WS message. The full line stays on disk; only the broadcast
	 * is truncated.
	 */
	private static final int MAX_LINE_CHARS = 8192;

	private final String projectId;
	private final String projectName;
	private final String insightId;
	private volatile boolean running = false;

	public AppLogStreamer(String projectId, String projectName, String insightId) {
		this.projectId = projectId;
		this.projectName = projectName;
		this.insightId = insightId;
	}

	private Path resolveLogFile() {
		return Paths.get(AppLogManager.getLogFilePath(projectId, projectName));
	}

	@Override
	public void start() {
		running = true;

		Path filePath = resolveLogFile();
		if (!Files.exists(filePath)) {
			classLogger.info("App log file for project {} does not exist yet, waiting for it to appear", projectId);
			filePath = waitForFile(filePath);
		}

		if (filePath == null) {
			// stop() was called while we were waiting
			classLogger.info("Stopped waiting for app log file (project={})", projectId);
			return;
		}

		sendInitialHistory(filePath);
		tailFile(filePath);

		classLogger.info("Stopped tailing app logs for project={}", projectId);
	}

	/** Poll until the file appears. Returns null if stop() is called first. */
	private Path waitForFile(Path filePath) {
		while (running) {
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
			if (Files.exists(filePath)) {
				classLogger.info("App log file appeared: {}", filePath);
				return filePath;
			}
		}
		return null;
	}

	/**
	 * Sends the last {@link #INITIAL_HISTORY_BYTES} of the file on watch, so the
	 * console doesn't open on a blank screen. Reuses {@link #readNewLines} to
	 * broadcast - {@link #tailFile} re-stats the file for its own start offset
	 * right after this returns, so nothing here needs to hand back a offset.
	 */
	private void sendInitialHistory(Path filePath) {
		long fileSize;
		try {
			fileSize = Files.size(filePath);
		} catch (IOException e) {
			classLogger.warn("Could not stat {} for initial history: {}", filePath, e.toString());
			return;
		}
		long startPos = Math.max(0L, fileSize - INITIAL_HISTORY_BYTES);
		if (startPos < fileSize) {
			readNewLines(filePath, startPos, fileSize);
		}
	}

	/**
	 * Tail the file by re-stat on each cycle - same approach as
	 * {@link ClaudeCodeHistoryStreamer}, which re-opens rather than holding a
	 * single long-lived handle because CSI/NFS-backed volumes cache attributes
	 * per-open-handle and would otherwise never see another process's appends.
	 */
	private void tailFile(Path filePath) {
		long lastOffset;
		try {
			lastOffset = Files.size(filePath);
		} catch (IOException e) {
			classLogger.warn("Could not stat {} on startup, starting from offset 0", filePath);
			lastOffset = 0;
		}
		classLogger.info("Tailing {} for insightId={} (start offset={})", filePath, insightId, lastOffset);

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
				classLogger.warn("Could not stat {}: {}", filePath, e.toString());
				continue;
			}

			if (currentSize < lastOffset) {
				classLogger.info("File {} truncated or rotated, resetting offset", filePath);
				lastOffset = 0;
			}

			if (currentSize == lastOffset) {
				continue;
			}

			lastOffset = readNewLines(filePath, lastOffset, currentSize);
		}
	}

	/**
	 * Reads bytes from {@code fromOffset} to {@code toOffset}, broadcasts every
	 * complete line, and returns the offset just after the last complete line -
	 * any trailing partial line stays on disk and is re-read next cycle once the
	 * rest of it has been written.
	 */
	private long readNewLines(Path filePath, long fromOffset, long toOffset) {
		int length = (int) (toOffset - fromOffset);
		byte[] buf = new byte[length];

		try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
			raf.seek(fromOffset);
			raf.readFully(buf);
		} catch (IOException e) {
			classLogger.error("Error reading {}", filePath, e);
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
			if (!line.isEmpty()) {
				broadcastLine(line);
			}
		}

		return fromOffset + lastNewline + 1;
	}

	private void broadcastLine(String line) {
		String toSend = line;
		if (line.length() > MAX_LINE_CHARS) {
			int omitted = line.length() - MAX_LINE_CHARS;
			toSend = line.substring(0, MAX_LINE_CHARS) + " ...[truncated, " + omitted + " more chars]";
		}

		JSONObject msg = new JSONObject();
		msg.put("type", "app_logs");
		msg.put("projectId", projectId);
		msg.put("line", toSend);

		SocketSessionHandler handler = SocketSessionHandlerFactory.getHandler(insightId);
		handler.updateRecipe(msg.toString());
	}

	@Override
	public void stop() {
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}
}
