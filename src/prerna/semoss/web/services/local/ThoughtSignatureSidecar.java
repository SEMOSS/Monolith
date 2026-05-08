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
/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ******************************************************************************/
package prerna.semoss.web.services.local;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Persists Vertex/Gemini {@code thought_signature} bytes per tool_use_id in a
 * sidecar JSONL file alongside Claude Code's session transcript.
 *
 * <p>Background: Vertex requires every prior {@code function_call} Part to
 * carry its original {@code thought_signature} on subsequent turns when
 * extended thinking is enabled. The Anthropic wire protocol the Claude Code
 * SDK speaks has no field on {@code tool_use} blocks for this value, so it
 * gets dropped at the SSE boundary and never makes it into the SDK's local
 * JSONL transcript. This sidecar smuggles the signature around the protocol
 * gap.
 *
 * <p>File: {@code <roomFolder>/thought_signatures.jsonl}
 *
 * <p>Format: append-only, one JSON object per line:
 * <pre>{"id":"toolu_01...","signature":"&lt;base64&gt;"}</pre>
 *
 * <p>Concurrency: writes within a single room are sequential because Claude
 * Code only opens one streaming session per room at a time. No locking is
 * required across rooms because each has its own folder.
 */
public class ThoughtSignatureSidecar {

	private static final Logger classLogger = LogManager.getLogger(ThoughtSignatureSidecar.class);
	private static final String FILENAME = "thought_signatures.jsonl";
	private static final Gson GSON = new Gson();

	private ThoughtSignatureSidecar() {
		// static utility
	}

	private static Path filePath(String roomFolderPath) {
		if (roomFolderPath == null || roomFolderPath.isEmpty()) {
			return null;
		}
		return Paths.get(roomFolderPath, FILENAME);
	}

	/**
	 * Append a (toolUseId, signature) entry to the room's sidecar. Creates the
	 * file (and any missing parent folder) if it does not exist. No-ops on null
	 * inputs or on I/O failure (best-effort: never breaks the SSE stream).
	 */
	public static void append(String roomFolderPath, String toolUseId, String signature) {
		if (toolUseId == null || toolUseId.isEmpty() || signature == null || signature.isEmpty()) {
			return;
		}
		Path path = filePath(roomFolderPath);
		if (path == null) {
			return;
		}
		try {
			File parent = path.getParent().toFile();
			if (!parent.exists()) {
				parent.mkdirs();
			}
			JsonObject obj = new JsonObject();
			obj.addProperty("id", toolUseId);
			obj.addProperty("signature", signature);
			String line = GSON.toJson(obj) + System.lineSeparator();
			Files.write(path, line.getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			classLogger.warn("Failed to append thought_signature to sidecar at {}: {}", path, e.getMessage());
		}
	}

	/**
	 * Load all (toolUseId → signature) entries from the room's sidecar.
	 * Returns an empty map when the file does not exist (e.g. first turn, or
	 * non-thinking model paths). Later entries with the same id win, which
	 * matches "most recent assignment" semantics in case anything ever
	 * re-emits.
	 */
	public static Map<String, String> load(String roomFolderPath) {
		Path path = filePath(roomFolderPath);
		if (path == null || !Files.exists(path)) {
			return Collections.emptyMap();
		}
		Map<String, String> result = new HashMap<>();
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				try {
					JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
					String id = obj.has("id") ? obj.get("id").getAsString() : null;
					String sig = obj.has("signature") ? obj.get("signature").getAsString() : null;
					if (id != null && sig != null) {
						result.put(id, sig);
					}
				} catch (Exception parseEx) {
					classLogger.debug("Skipping malformed sidecar line in {}: {}", path, parseEx.getMessage());
				}
			}
		} catch (IOException e) {
			classLogger.warn("Failed to load thought_signature sidecar at {}: {}", path, e.getMessage());
			return Collections.emptyMap();
		}
		return result;
	}
}
