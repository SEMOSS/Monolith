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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import prerna.semoss.web.services.local.SemossContextExtractor.ExtractionResult;

class SemossContextExtractorTest {

	private static Map<String, Object> textBlock(String text) {
		Map<String, Object> block = new HashMap<>();
		block.put("type", "text");
		block.put("text", text);
		return block;
	}

	@Test
	void extractsTheInsightAndRoomIds() {
		ExtractionResult result = SemossContextExtractor
				.extract("[[SEMOSS_CONTEXT:insightId=abc-123, roomId= room-9 ]] hello");

		assertEquals("abc-123", result.getInsightId());
		assertEquals("room-9", result.getRoomId());
		assertTrue(result.hasInsightId());
		assertTrue(result.hasRoomId());
	}

	@Test
	void findsTheTagAnywhereInTheText() {
		ExtractionResult result = SemossContextExtractor.extract("before [[SEMOSS_CONTEXT:roomId=r1]] after");

		assertNull(result.getInsightId());
		assertFalse(result.hasInsightId());
		assertEquals("r1", result.getRoomId());
	}

	@Test
	void returnsNoIdsForTextWithoutATag() {
		for (String text : new String[] { null, "", "just a prompt", "[[OTHER:insightId=a]]" }) {
			ExtractionResult result = SemossContextExtractor.extract(text);
			assertFalse(result.hasInsightId(), String.valueOf(text));
			assertFalse(result.hasRoomId(), String.valueOf(text));
		}
	}

	@Test
	void readsOnlyTheFirstTag() {
		ExtractionResult result = SemossContextExtractor
				.extract("[[SEMOSS_CONTEXT:insightId=first]] [[SEMOSS_CONTEXT:insightId=second]]");

		assertEquals("first", result.getInsightId());
	}

	@Test
	void stripsEveryTagAndTheWhitespaceAfterIt() {
		assertEquals("hello world", SemossContextExtractor
				.strip("[[SEMOSS_CONTEXT:insightId=a]]   hello [[SEMOSS_CONTEXT:roomId=b]] world  "));
		assertEquals("no tag here", SemossContextExtractor.strip("no tag here"));
		assertNull(SemossContextExtractor.strip(null));
		assertEquals("", SemossContextExtractor.strip(""));
	}

	@Test
	void stripsTheTagFromStringContentInPlace() {
		Map<String, Object> message = new HashMap<>();
		message.put("role", "user");
		message.put("content", "[[SEMOSS_CONTEXT:insightId=i1,roomId=r1]] what is up");

		ExtractionResult result = SemossContextExtractor.extractAndStripFromMessage(message);

		assertEquals("i1", result.getInsightId());
		assertEquals("r1", result.getRoomId());
		assertEquals("what is up", message.get("content"));
	}

	@Test
	void stripsTheFirstTaggedTextBlockOfStructuredContent() {
		List<Object> blocks = new ArrayList<>();
		blocks.add(Map.of("type", "image", "source", "data"));
		blocks.add(textBlock("no tag"));
		blocks.add(textBlock("[[SEMOSS_CONTEXT:roomId=r2]] tagged"));
		Map<String, Object> message = new HashMap<>();
		message.put("content", blocks);

		ExtractionResult result = SemossContextExtractor.extractAndStripFromMessage(message);

		assertEquals("r2", result.getRoomId());
		assertEquals("no tag", ((Map<?, ?>) blocks.get(1)).get("text"));
		assertEquals("tagged", ((Map<?, ?>) blocks.get(2)).get("text"));
	}

	@Test
	void leavesAMessageWithoutIdsUntouched() {
		Map<String, Object> message = new HashMap<>();
		message.put("content", "plain prompt");

		ExtractionResult result = SemossContextExtractor.extractAndStripFromMessage(message);

		assertFalse(result.hasInsightId() || result.hasRoomId());
		assertEquals("plain prompt", message.get("content"));
		assertFalse(SemossContextExtractor.extractAndStripFromMessage(null).hasRoomId());
		Map<String, Object> numeric = new HashMap<>();
		numeric.put("content", 42);
		assertFalse(SemossContextExtractor.extractAndStripFromMessage(numeric).hasRoomId());
	}

	@Test
	void extractsAndStripsTheParentRoomTag() {
		String prompt = "You are helpful. [[PARENT_ROOM_ID= parent-1 ]] Be brief.";

		assertEquals("parent-1", SemossContextExtractor.extractParentRoomId(prompt));
		assertEquals("You are helpful. Be brief.", SemossContextExtractor.stripParentRoomTag(prompt));
		assertNull(SemossContextExtractor.extractParentRoomId("no tag"));
		assertNull(SemossContextExtractor.extractParentRoomId(null));
		assertNull(SemossContextExtractor.stripParentRoomTag(null));
	}
}
