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
package prerna.semoss.web.services.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import prerna.util.Utility;

class ThoughtSignatureSidecarTest {

	@Test
	void interactionsTextOnlyTurnSurvivesReloadAndIsBoundToTheConversation() throws Exception {
		String room = rooms.resolve("interactions").toString();
		Map<String, Object> user = Map.of("role", "user", "content", "Question");
		Map<String, Object> assistant = new HashMap<>(Map.of("role", "assistant", "content", "Answer"));
		Map<String, Object> state = Map.of("version", 1, "model", "gemini-4-carbon", "steps",
				List.of(Map.of("type", "thought", "signature", "opaque")));
		ThoughtSignatureSidecar.appendInteraction(room, List.of(user), assistant, state);
		List<Map<String, Object>> history = new ArrayList<>(List.of(new HashMap<>(user), assistant));
		ThoughtSignatureSidecar.restoreInteractions(room, history);
		assertEquals("opaque", ((Map<?, ?>) ((List<?>) ((Map<?, ?>) assistant.get("google_interactions"))
				.get("steps")).getFirst()).get("signature"));
		assertTrue(ThoughtSignatureSidecar.load(room).isEmpty());
		assistant.remove("google_interactions");
		history.set(0, new HashMap<>(Map.of("role", "user", "content", "Other question")));
		assertThrows(IllegalArgumentException.class, () -> ThoughtSignatureSidecar.restoreInteractions(room, history));
		assertFalse(assistant.containsKey("google_interactions"));
		ThoughtSignatureSidecar.restoreInteractions(rooms.resolve("another-room").toString(), history);
		assertFalse(assistant.containsKey("google_interactions"));
	}

	@Test
	void interactionsKeysIgnoreMapOrderAndJsonArgumentEncodingButIncludeContext() {
		Map<String, Object> a = Map.of("role", "assistant", "content", "", "tool_calls", List.of(
				Map.of("id", "a", "type", "function", "function", Map.of("name", "lookup", "arguments", "{\"b\":2,\"a\":1}"))));
		Map<String, Object> b = Map.of("role", "assistant", "tool_calls", List.of(
				Map.of("type", "function", "id", "a", "function", Map.of("arguments", Map.of("a", 1.0, "b", 2.0), "name", "lookup"))));
		assertEquals(ThoughtSignatureSidecar.interactionKey(List.of(a)), ThoughtSignatureSidecar.interactionKey(List.of(b)));
	}

	@Test
	void interactionsRefuseStorageOutsideTheRoomRoot() {
		assertThrows(java.io.IOException.class, () -> ThoughtSignatureSidecar.appendInteraction(base.toString(), List.of(),
				Map.of("role", "assistant", "content", "Answer"), Map.of("version", 1, "model", "gemini",
						"steps", List.of(Map.of("type", "thought", "signature", "opaque")))));
	}

	private static final String FILENAME = "thought_signatures.jsonl";

	@TempDir
	Path base;

	private MockedStatic<Utility> utility;
	private Path rooms;

	@BeforeEach
	void pointTheBaseFolderAtATempDir() throws Exception {
		rooms = Files.createDirectories(base.resolve("room"));
		utility = mockStatic(Utility.class);
		utility.when(Utility::getBaseFolder).thenReturn(base.toString());
	}

	@AfterEach
	void closeTheStaticMock() {
		utility.close();
	}

	@Test
	void readsBackWhatWasAppendedForARoom() {
		String room = rooms.resolve("room-1").toString();
		ThoughtSignatureSidecar.append(room, "toolu_1", "sig-a");
		ThoughtSignatureSidecar.append(room, "toolu_2", "sig-b");

		assertEquals(Map.of("toolu_1", "sig-a", "toolu_2", "sig-b"), ThoughtSignatureSidecar.load(room));
		assertTrue(Files.exists(rooms.resolve("room-1").resolve(FILENAME)), "the missing room folder is created");
	}

	@Test
	void letsTheLatestSignatureForAToolUseIdWin() {
		String room = rooms.resolve("room-2").toString();
		ThoughtSignatureSidecar.append(room, "toolu_1", "old");
		ThoughtSignatureSidecar.append(room, "toolu_1", "new");

		assertEquals(Map.of("toolu_1", "new"), ThoughtSignatureSidecar.load(room));
	}

	@Test
	void writesOneJsonObjectPerLine() throws Exception {
		String room = rooms.resolve("room-3").toString();
		ThoughtSignatureSidecar.append(room, "toolu_1", "c2lnPQ==");
		ThoughtSignatureSidecar.append(room, "toolu_2", "c2ln");

		List<String> lines = Files.readAllLines(rooms.resolve("room-3").resolve(FILENAME), StandardCharsets.UTF_8);
		assertEquals(2, lines.size());
		JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
		assertEquals("toolu_1", first.get("id").getAsString());
		assertEquals("c2lnPQ==", first.get("signature").getAsString());
		assertEquals("{\"id\":\"toolu_2\",\"signature\":\"c2ln\"}", lines.get(1));
	}

	@Test
	void skipsBlankAndMalformedLines() throws Exception {
		Path roomDir = Files.createDirectories(rooms.resolve("room-4"));
		Files.writeString(roomDir.resolve(FILENAME),
				"\n{not json\n[1,2]\n{\"id\":\"only-id\"}\n{\"signature\":\"only-sig\"}\n{\"id\":\"toolu_1\",\"signature\":\"ok\"}\n",
				StandardCharsets.UTF_8);

		assertEquals(Map.of("toolu_1", "ok"), ThoughtSignatureSidecar.load(roomDir.toString()));
	}

	@Test
	void ignoresAppendsMissingAnIdOrASignature() {
		String room = rooms.resolve("room-5").toString();
		ThoughtSignatureSidecar.append(room, null, "sig");
		ThoughtSignatureSidecar.append(room, "", "sig");
		ThoughtSignatureSidecar.append(room, "toolu_1", null);
		ThoughtSignatureSidecar.append(room, "toolu_1", "");

		assertFalse(Files.exists(rooms.resolve("room-5")));
		assertTrue(ThoughtSignatureSidecar.load(room).isEmpty());
	}

	@Test
	void returnsAnEmptyMapForARoomWithoutASidecar() {
		assertTrue(ThoughtSignatureSidecar.load(rooms.resolve("never-written").toString()).isEmpty());
		assertTrue(ThoughtSignatureSidecar.load(null).isEmpty());
		assertTrue(ThoughtSignatureSidecar.load("").isEmpty());
	}

	@Test
	void refusesFoldersOutsideTheRoomsRoot() throws Exception {
		Path outside = Files.createDirectories(base.resolve("elsewhere"));
		ThoughtSignatureSidecar.append(outside.toString(), "toolu_1", "sig");
		assertFalse(Files.exists(outside.resolve(FILENAME)));

		String traversal = rooms.toString() + "/../elsewhere";
		ThoughtSignatureSidecar.append(traversal, "toolu_1", "sig");
		assertFalse(Files.exists(outside.resolve(FILENAME)));

		Files.writeString(outside.resolve(FILENAME), "{\"id\":\"toolu_1\",\"signature\":\"planted\"}\n");
		assertTrue(ThoughtSignatureSidecar.load(outside.toString()).isEmpty());
		assertTrue(ThoughtSignatureSidecar.load(traversal).isEmpty());
	}

	@Test
	void refusesASymlinkedRoomThatPointsOutOfTheRoomsRoot(@TempDir Path other) throws Exception {
		Path linked = rooms.resolve("linked");
		Files.createSymbolicLink(linked, other);

		ThoughtSignatureSidecar.append(linked.toString(), "toolu_1", "sig");

		assertFalse(Files.exists(other.resolve(FILENAME)), "a symlinked room cannot redirect the write");
	}
}
