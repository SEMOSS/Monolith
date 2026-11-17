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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.model.responses.AskStringModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse;

class AnthropicMessagesHelperTest {

	@Test
	void writesEachSseEventAsAnEventLineAndADataLineEndedByABlankLine() throws Exception {
		StringWriter writer = new StringWriter();
		AnthropicMessagesHelper.writeSSEEvent("ping", Map.of("type", "ping"), writer);

		assertEquals("event: ping\ndata: {\"type\":\"ping\"}\n\n", writer.toString());
	}

	@Test
	void keepsHtmlCharactersUnescapedInEventData() throws Exception {
		StringWriter writer = new StringWriter();
		AnthropicMessagesHelper.writeTextDelta(0, "<b>a & b</b>", writer);

		assertTrue(writer.toString().contains("\"text\":\"<b>a & b</b>\""), writer.toString());
	}

	@Test
	void opensTheStreamWithAnEmptyAssistantMessage() throws Exception {
		StringWriter writer = new StringWriter();
		AnthropicMessagesHelper.writeMessageStart("msg_1", "engine-1", 12, writer);

		List<Event> events = events(writer.toString());
		assertEquals(1, events.size());
		assertEquals("message_start", events.get(0).name);
		JsonObject message = events.get(0).data.getAsJsonObject("message");
		assertEquals("message_start", events.get(0).data.get("type").getAsString());
		assertEquals("msg_1", message.get("id").getAsString());
		assertEquals("message", message.get("type").getAsString());
		assertEquals("assistant", message.get("role").getAsString());
		assertEquals("engine-1", message.get("model").getAsString());
		assertEquals(0, message.getAsJsonArray("content").size());
		assertEquals(12, message.getAsJsonObject("usage").get("input_tokens").getAsInt());
		assertEquals(1, message.getAsJsonObject("usage").get("output_tokens").getAsInt());
	}

	@Test
	void reportsTheStopReasonAndEveryKnownUsageCountInMessageDelta() throws Exception {
		StringWriter writer = new StringWriter();
		AnthropicMessagesHelper.writeMessageDelta("tool_use", 100, 20, 30, 40, writer);

		Event event = events(writer.toString()).get(0);
		assertEquals("message_delta", event.name);
		assertEquals("tool_use", event.data.getAsJsonObject("delta").get("stop_reason").getAsString());
		JsonObject usage = event.data.getAsJsonObject("usage");
		assertEquals(100, usage.get("input_tokens").getAsInt());
		assertEquals(20, usage.get("output_tokens").getAsInt());
		assertEquals(30, usage.get("cache_read_input_tokens").getAsInt());
		assertEquals(40, usage.get("cache_creation_input_tokens").getAsInt());
	}

	@Test
	void defaultsMissingOutputTokensToZeroAndLeavesOutUnknownCounts() throws Exception {
		StringWriter writer = new StringWriter();
		AnthropicMessagesHelper.writeMessageDelta("end_turn", null, writer);

		JsonObject usage = events(writer.toString()).get(0).data.getAsJsonObject("usage");
		assertEquals(0, usage.get("output_tokens").getAsInt());
		assertFalse(usage.has("input_tokens"));
		assertFalse(usage.has("cache_read_input_tokens"));
		assertFalse(usage.has("cache_creation_input_tokens"));
	}

	@Test
	void startsEachContentBlockTypeAtItsIndexWithAnEmptyBody() throws Exception {
		StringWriter writer = new StringWriter();
		AnthropicMessagesHelper.writeThinkingContentBlockStart(0, writer);
		AnthropicMessagesHelper.writeTextContentBlockStart(1, writer);
		AnthropicMessagesHelper.writeToolUseContentBlockStart(2, "toolu_1", "get_weather", writer);
		AnthropicMessagesHelper.writeContentBlockStop(2, writer);

		List<Event> events = events(writer.toString());
		assertEquals(List.of("content_block_start", "content_block_start", "content_block_start", "content_block_stop"),
				names(events));

		JsonObject thinking = events.get(0).data;
		assertEquals(0, thinking.get("index").getAsInt());
		assertEquals("thinking", thinking.getAsJsonObject("content_block").get("type").getAsString());
		assertEquals("", thinking.getAsJsonObject("content_block").get("thinking").getAsString());

		JsonObject text = events.get(1).data;
		assertEquals(1, text.get("index").getAsInt());
		assertEquals("text", text.getAsJsonObject("content_block").get("type").getAsString());
		assertEquals("", text.getAsJsonObject("content_block").get("text").getAsString());

		JsonObject tool = events.get(2).data.getAsJsonObject("content_block");
		assertEquals(2, events.get(2).data.get("index").getAsInt());
		assertEquals("tool_use", tool.get("type").getAsString());
		assertEquals("toolu_1", tool.get("id").getAsString());
		assertEquals("get_weather", tool.get("name").getAsString());
		assertEquals(0, tool.getAsJsonObject("input").size());

		assertEquals(2, events.get(3).data.get("index").getAsInt());
		assertEquals("content_block_stop", events.get(3).data.get("type").getAsString());
	}

	@Test
	void writesTextJsonAndThinkingDeltasWithTheirDeltaTypes() throws Exception {
		StringWriter writer = new StringWriter();
		AnthropicMessagesHelper.writeTextDelta(1, "Hello", writer);
		AnthropicMessagesHelper.writeInputJsonDelta(2, "{\"city\":", writer);
		AnthropicMessagesHelper.writeThinkingDelta(0, "hmm", writer);

		List<Event> events = events(writer.toString());
		assertEquals(List.of("content_block_delta", "content_block_delta", "content_block_delta"), names(events));

		JsonObject text = events.get(0).data.getAsJsonObject("delta");
		assertEquals("text_delta", text.get("type").getAsString());
		assertEquals("Hello", text.get("text").getAsString());
		assertEquals(1, events.get(0).data.get("index").getAsInt());

		JsonObject json = events.get(1).data.getAsJsonObject("delta");
		assertEquals("input_json_delta", json.get("type").getAsString());
		assertEquals("{\"city\":", json.get("partial_json").getAsString());

		JsonObject thinking = events.get(2).data.getAsJsonObject("delta");
		assertEquals("thinking_delta", thinking.get("type").getAsString());
		assertEquals("hmm", thinking.get("thinking").getAsString());
	}

	@Test
	void writesACompleteTextStreamInWireOrder() throws Exception {
		StringWriter writer = new StringWriter();
		AnthropicMessagesHelper.writeCompleteTextStream("msg_2", "engine-1", "Hi there", null, 5, writer);

		List<Event> events = events(writer.toString());
		assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "content_block_stop",
				"message_delta", "message_stop"), names(events));
		assertEquals(0, events.get(0).data.getAsJsonObject("message").getAsJsonObject("usage").get("input_tokens")
				.getAsInt(), "unknown input tokens open the stream as zero");
		assertEquals("Hi there", events.get(2).data.getAsJsonObject("delta").get("text").getAsString());
		assertEquals("end_turn", events.get(4).data.getAsJsonObject("delta").get("stop_reason").getAsString());
		assertEquals(5, events.get(4).data.getAsJsonObject("usage").get("output_tokens").getAsInt());
		assertEquals("message_stop", events.get(5).data.get("type").getAsString());
	}

	@Test
	void writesPingsAsRealEvents() throws Exception {
		StringWriter writer = new StringWriter();
		AnthropicMessagesHelper.writePing(writer);

		Event event = events(writer.toString()).get(0);
		assertEquals("ping", event.name);
		assertEquals("ping", event.data.get("type").getAsString());
	}

	@Test
	void buildsErrorsAsATypedErrorEnvelope() throws Exception {
		Map<String, Object> body = AnthropicMessagesHelper.createErrorResponse("invalid_request_error", "bad");
		assertEquals("error", body.get("type"));
		assertEquals(Map.of("type", "invalid_request_error", "message", "bad"), body.get("error"));

		StringWriter writer = new StringWriter();
		AnthropicMessagesHelper.writeErrorEvent("api_error", "boom", writer);
		Event event = events(writer.toString()).get(0);
		assertEquals("error", event.name);
		assertEquals("error", event.data.get("type").getAsString());
		assertEquals("api_error", event.data.getAsJsonObject("error").get("type").getAsString());
		assertEquals("boom", event.data.getAsJsonObject("error").get("message").getAsString());
	}

	@Test
	void joinsTextSystemBlocksWithNewlinesAndSkipsOtherBlocks() {
		List<Map<String, Object>> blocks = List.of(Map.of("type", "text", "text", "You are helpful."),
				Map.of("type", "image", "text", "ignored"), Map.of("type", "text"),
				Map.of("type", "text", "text", "Be brief."));

		assertEquals("You are helpful.\nBe brief.", AnthropicMessagesHelper.getSystemMessage(blocks));
		assertEquals("plain", AnthropicMessagesHelper.getSystemMessage("plain"));
	}

	@Test
	void readsAnyOtherSystemValueAsAnEmptyPrompt() {
		assertEquals("", AnthropicMessagesHelper.getSystemMessage(null));
		assertEquals("", AnthropicMessagesHelper.getSystemMessage(42));
		assertEquals("", AnthropicMessagesHelper.getSystemMessage(List.of()));
	}

	@Test
	void putsTheSystemPromptFirstAndKeepsStringTurnsAsTheyAre() {
		List<Map<String, Object>> messages = List.of(message("user", "Hi"), message("assistant", "Hello"));

		List<Map<String, Object>> out = openAIMessages(
				AnthropicMessagesHelper.normalizeAllAnthropicMessagesToOpenAI(messages, "Be nice", null));

		assertEquals(3, out.size());
		assertEquals(Map.of("role", "system", "content", "Be nice"), out.get(0));
		assertEquals(Map.of("role", "user", "content", "Hi"), out.get(1));
		assertEquals(Map.of("role", "assistant", "content", "Hello"), out.get(2));
	}

	@Test
	void leavesOutABlankSystemPromptAndTurnsWithOtherRoles() {
		List<Map<String, Object>> messages = List.of(message("system", "ignored"), message("user", "Hi"),
				message("assistant", 7));

		Map<String, Object> result = AnthropicMessagesHelper.normalizeAllAnthropicMessagesToOpenAI(messages, "  ",
				null);

		assertEquals(List.of(Map.of("role", "user", "content", "Hi")), openAIMessages(result));
		assertFalse(result.containsKey("tools"));
	}

	@Test
	void movesToolResultsAheadOfTheUserTextOfTheSameTurn() {
		List<Map<String, Object>> content = List.of(block("text", "text", "Here are the results"),
				toolResult("toolu_1", "sunny"), toolResult("toolu_2", "rainy"));

		List<Map<String, Object>> out = openAIMessages(AnthropicMessagesHelper
				.normalizeAllAnthropicMessagesToOpenAI(List.of(message("user", content)), null, null));

		assertEquals(3, out.size());
		assertEquals(Map.of("role", "tool", "tool_call_id", "toolu_1", "content", "sunny"), out.get(0));
		assertEquals(Map.of("role", "tool", "tool_call_id", "toolu_2", "content", "rainy"), out.get(1));
		assertEquals(Map.of("role", "user", "content", "Here are the results"), out.get(2));
	}

	@Test
	void flattensToolResultTextAndToolReferenceBlocks() {
		List<Map<String, Object>> resultBlocks = List.of(block("text", "text", "line 1"),
				block("tool_reference", "tool_name", "search"), block("image", "text", "skipped"),
				block("text", "text", "line 2"));
		List<Map<String, Object>> content = List.of(toolResult("toolu_1", resultBlocks));

		List<Map<String, Object>> out = openAIMessages(AnthropicMessagesHelper
				.normalizeAllAnthropicMessagesToOpenAI(List.of(message("user", content)), null, null));

		assertEquals("line 1\nTool loaded: search\nline 2", out.get(0).get("content"));
	}

	@Test
	void fillsAnEmptyToolResultWithAPlaceholder() {
		List<Map<String, Object>> content = List.of(toolResult("toolu_1", ""), toolResult("toolu_2", null),
				toolResult("toolu_3", List.of(block("image", "text", "only an image"))));

		List<Map<String, Object>> out = openAIMessages(AnthropicMessagesHelper
				.normalizeAllAnthropicMessagesToOpenAI(List.of(message("user", content)), null, null));

		assertEquals(3, out.size());
		for (Map<String, Object> toolMessage : out) {
			assertEquals("Tool executed successfully.", toolMessage.get("content"));
		}
	}

	@Test
	void sendsUserImagesAsImageUrlPartsAfterTheText() {
		Map<String, Object> base64Image = new HashMap<>();
		base64Image.put("type", "image");
		base64Image.put("source", Map.of("type", "base64", "media_type", "image/png", "data", "AAAA"));
		Map<String, Object> urlImage = new HashMap<>();
		urlImage.put("type", "image");
		urlImage.put("source", Map.of("type", "url", "url", "https://example.com/a.png"));
		Map<String, Object> unknownImage = new HashMap<>();
		unknownImage.put("type", "image");
		unknownImage.put("source", Map.of("type", "file", "file_id", "f1"));
		List<Map<String, Object>> content = List.of(base64Image, block("text", "text", "What is this?"), urlImage,
				unknownImage);

		List<Map<String, Object>> out = openAIMessages(AnthropicMessagesHelper
				.normalizeAllAnthropicMessagesToOpenAI(List.of(message("user", content)), null, null));

		assertEquals(1, out.size());
		List<?> parts = (List<?>) out.get(0).get("content");
		assertEquals(3, parts.size(), "an image source that is neither base64 nor url is dropped");
		assertEquals(Map.of("type", "text", "text", "What is this?"), parts.get(0));
		assertEquals(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AAAA")),
				parts.get(1));
		assertEquals(Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/a.png")),
				parts.get(2));
	}

	@Test
	void joinsUserTextBlocksAndDropsBlankOnes() {
		List<Map<String, Object>> content = List.of(block("text", "text", "first"), block("text", "text", "   "),
				block("text", "text", "second"), block("document", "text", "ignored"));

		List<Map<String, Object>> out = openAIMessages(AnthropicMessagesHelper
				.normalizeAllAnthropicMessagesToOpenAI(List.of(message("user", content)), null, null));

		assertEquals(List.of(Map.of("role", "user", "content", "first\nsecond")), out);
	}

	@Test
	void emitsNothingForAUserTurnWithOnlyBlankText() {
		List<Map<String, Object>> content = List.of(block("text", "text", " "));

		List<Map<String, Object>> out = openAIMessages(AnthropicMessagesHelper
				.normalizeAllAnthropicMessagesToOpenAI(List.of(message("user", content)), null, null));

		assertTrue(out.isEmpty());
	}

	@Test
	void turnsAssistantToolUseIntoToolCallsWithTheInputAsArguments() {
		Map<String, Object> toolUse = new HashMap<>();
		toolUse.put("type", "tool_use");
		toolUse.put("id", "toolu_1");
		toolUse.put("name", "get_weather");
		toolUse.put("input", Map.of("city", "Paris"));
		List<Map<String, Object>> content = List.of(block("text", "text", "Let me check."), toolUse);

		List<Map<String, Object>> out = openAIMessages(AnthropicMessagesHelper
				.normalizeAllAnthropicMessagesToOpenAI(List.of(message("assistant", content)), null, null));

		assertEquals(1, out.size());
		Map<String, Object> assistant = out.get(0);
		assertEquals("assistant", assistant.get("role"));
		assertEquals("Let me check.", assistant.get("content"));
		List<?> toolCalls = (List<?>) assistant.get("tool_calls");
		assertEquals(1, toolCalls.size());
		assertEquals(Map.of("id", "toolu_1", "type", "function", "function",
				Map.of("name", "get_weather", "arguments", Map.of("city", "Paris"))), toolCalls.get(0));
		assertFalse(assistant.containsKey("thinking"));
	}

	@Test
	void reattachesAStoredThoughtSignatureToItsToolCall() {
		Map<String, Object> signed = new HashMap<>();
		signed.put("type", "tool_use");
		signed.put("id", "toolu_1");
		signed.put("name", "a");
		signed.put("input", Map.of());
		Map<String, Object> unsigned = new HashMap<>(signed);
		unsigned.put("id", "toolu_2");
		Map<String, Object> emptySigned = new HashMap<>(signed);
		emptySigned.put("id", "toolu_3");

		List<Map<String, Object>> out = openAIMessages(AnthropicMessagesHelper.normalizeAllAnthropicMessagesToOpenAI(
				List.of(message("assistant", List.of(signed, unsigned, emptySigned))), null, null,
				Map.of("toolu_1", "sig-1", "toolu_3", "")));

		List<?> toolCalls = (List<?>) out.get(0).get("tool_calls");
		assertEquals("sig-1", ((Map<?, ?>) toolCalls.get(0)).get("thought_signature"));
		assertFalse(((Map<?, ?>) toolCalls.get(1)).containsKey("thought_signature"));
		assertFalse(((Map<?, ?>) toolCalls.get(2)).containsKey("thought_signature"));

		List<Map<String, Object>> withoutMap = openAIMessages(AnthropicMessagesHelper
				.normalizeAllAnthropicMessagesToOpenAI(List.of(message("assistant", List.of(signed))), null, null, null));
		assertFalse(((Map<?, ?>) ((List<?>) withoutMap.get(0).get("tool_calls")).get(0))
				.containsKey("thought_signature"));
	}

	@Test
	void joinsAssistantThinkingBlocksAndKeepsAnEmptyContentString() {
		List<Map<String, Object>> content = List.of(block("thinking", "thinking", "step 1"),
				block("thinking", "thinking", "step 2"));

		List<Map<String, Object>> out = openAIMessages(AnthropicMessagesHelper
				.normalizeAllAnthropicMessagesToOpenAI(List.of(message("assistant", content)), null, null));

		assertEquals("step 1\nstep 2", out.get(0).get("thinking"));
		assertEquals("", out.get(0).get("content"));
		assertFalse(out.get(0).containsKey("tool_calls"));
	}

	@Test
	void convertsToolsToTheMcpShapeAndLeavesOutAnEmptyList() {
		Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());
		Map<String, Object> withSchema = new HashMap<>();
		withSchema.put("name", "get_weather");
		withSchema.put("description", "Weather lookup");
		withSchema.put("input_schema", schema);
		Map<String, Object> withoutSchema = new HashMap<>();
		withoutSchema.put("name", "noop");

		Map<String, Object> result = AnthropicMessagesHelper.normalizeAllAnthropicMessagesToOpenAI(List.of(), null,
				List.of(withSchema, withoutSchema));

		List<?> tools = (List<?>) result.get("tools");
		assertEquals(2, tools.size());
		Map<?, ?> first = (Map<?, ?>) tools.get(0);
		assertEquals("get_weather", first.get("name"));
		assertEquals("Weather lookup", first.get("description"));
		assertEquals(schema, first.get("inputSchema"));
		assertFalse(first.containsKey("input_schema"));
		assertFalse(((Map<?, ?>) tools.get(1)).containsKey("inputSchema"));

		assertFalse(AnthropicMessagesHelper.normalizeAllAnthropicMessagesToOpenAI(List.of(), null, List.of())
				.containsKey("tools"));
		assertFalse(AnthropicMessagesHelper.normalizeAllAnthropicMessagesToOpenAI(List.of(), null, "not a list")
				.containsKey("tools"));
		assertNull(AnthropicMessagesHelper.normalizeToolsToMCP(Map.of()));
	}

	@Test
	void wrapsToolsAsFunctionsForTheFunctionCallingShape() {
		Map<String, Object> tool = new HashMap<>();
		tool.put("name", "get_weather");
		tool.put("description", "Weather lookup");
		tool.put("input_schema", Map.of("type", "object"));
		Map<String, Object> bare = new HashMap<>();
		bare.put("name", "noop");

		List<Map<String, Object>> tools = AnthropicMessagesHelper.normalizeTools(List.of(tool, bare));

		assertEquals("function", tools.get(0).get("type"));
		Map<?, ?> function = (Map<?, ?>) tools.get(0).get("function");
		assertEquals("get_weather", function.get("name"));
		assertEquals("Weather lookup", function.get("description"));
		assertEquals(Map.of("type", "object"), function.get("parameters"));
		assertFalse(((Map<?, ?>) tools.get(1).get("function")).containsKey("parameters"));
		assertNull(AnthropicMessagesHelper.normalizeTools("nope"));
	}

	@Test
	void keepsTheUserTextAheadOfItsToolResultsForASingleMessage() {
		Map<String, Object> image = new HashMap<>();
		image.put("type", "image");
		image.put("source", Map.of("type", "url", "url", "https://example.com/r.png"));
		List<Map<String, Object>> resultBlocks = List.of(block("text", "text", "part a"), image,
				block("text", "text", "part b"));
		List<Map<String, Object>> content = List.of(toolResult("toolu_1", resultBlocks), block("text", "text", "next"),
				toolResult("toolu_2", "plain"));
		Map<String, Object> userMessage = new HashMap<>();
		userMessage.put("role", "user");
		userMessage.put("content", content);

		List<Map<String, Object>> out = openAIMessages(
				AnthropicMessagesHelper.normalizeAnthropicMessagestoOpenAIMessages(userMessage, "sys", null));

		assertEquals(4, out.size());
		assertEquals("system", out.get(0).get("role"));
		assertEquals(Map.of("role", "user", "content", "next"), out.get(1));
		assertEquals("tool", out.get(2).get("role"));
		assertEquals("toolu_1", out.get(2).get("tool_call_id"));
		List<?> parts = (List<?>) out.get(2).get("content");
		assertEquals(Map.of("type", "text", "text", "part apart b"), parts.get(0));
		assertEquals(Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/r.png")),
				parts.get(1));
		assertEquals(Map.of("role", "tool", "tool_call_id", "toolu_2", "content", "plain"), out.get(3));
	}

	@Test
	void mapsAStringSingleMessageUnderItsOwnRole() {
		Map<String, Object> message = new HashMap<>();
		message.put("content", "hello");

		List<Map<String, Object>> out = openAIMessages(
				AnthropicMessagesHelper.normalizeAnthropicMessagestoOpenAIMessages(message, null, null));
		assertEquals(List.of(Map.of("role", "user", "content", "hello")), out);

		assertTrue(openAIMessages(AnthropicMessagesHelper.normalizeAnthropicMessagestoOpenAIMessages(null, null, null))
				.isEmpty());
	}

	@Test
	void answersATextResponseAsAnEndTurnMessage() {
		AskStringModelEngineResponse response = new AskStringModelEngineResponse("Hi!", 11, 3);
		response.setMessageId("msg_9");

		Map<String, Object> out = AnthropicMessagesHelper.processAskModelEngineResponse("engine-1", response);

		assertEquals("msg_9", out.get("id"));
		assertEquals("message", out.get("type"));
		assertEquals("assistant", out.get("role"));
		assertEquals("engine-1", out.get("model"));
		assertEquals("end_turn", out.get("stop_reason"));
		assertTrue(out.containsKey("stop_sequence"));
		assertNull(out.get("stop_sequence"));
		assertEquals(List.of(Map.of("type", "text", "text", "Hi!")), out.get("content"));
		assertEquals(Map.of("input_tokens", 11, "output_tokens", 3), out.get("usage"));
	}

	@Test
	void answersAToolResponseAsToolUseBlocksAfterAnyThinking() {
		List<Map<String, Object>> calls = new ArrayList<>();
		Map<String, Object> call = new HashMap<>();
		call.put("id", "toolu_1");
		call.put("name", "get_weather");
		call.put("arguments", "{\"city\":\"Paris\"}");
		calls.add(call);
		AskToolModelEngineResponse response = new AskToolModelEngineResponse(calls, null, null);
		response.setThinking("I should look it up");

		Map<String, Object> out = AnthropicMessagesHelper.processAskModelEngineResponse("engine-1", response);

		assertEquals("tool_use", out.get("stop_reason"));
		List<?> content = (List<?>) out.get("content");
		assertEquals(2, content.size());
		assertEquals(Map.of("type", "thinking", "thinking", "I should look it up"), content.get(0));
		Map<?, ?> toolUse = (Map<?, ?>) content.get(1);
		assertEquals("tool_use", toolUse.get("type"));
		assertEquals("toolu_1", toolUse.get("id"));
		assertEquals("get_weather", toolUse.get("name"));
		assertEquals(Map.of("city", "Paris"), toolUse.get("input"));
		assertEquals(Map.of(), out.get("usage"), "unknown token counts are left out");
	}

	@Test
	void readsAStringTurnAsTheRoomQuestion() {
		Map<String, Object> message = new HashMap<>();
		message.put("content", "What is up?");
		assertEquals(Map.of("question", "What is up?"),
				AnthropicMessagesHelper.normalizeMessageForAskRoom(message, null, null));

		message.put("content", "   ");
		assertTrue(AnthropicMessagesHelper.normalizeMessageForAskRoom(message, null, null).isEmpty());
	}

	@Test
	void readsAToolResultTurnAsTheRoomToolExecution() {
		Map<String, Object> image = new HashMap<>();
		image.put("type", "image");
		image.put("source", Map.of("type", "base64", "media_type", "image/jpeg", "data", "BBBB"));
		List<Map<String, Object>> resultBlocks = List.of(block("text", "text", "a"), image, block("text", "text", "b"));
		Map<String, Object> message = new HashMap<>();
		message.put("content", List.of(toolResult("toolu_7", resultBlocks)));

		Map<String, Object> out = AnthropicMessagesHelper.normalizeMessageForAskRoom(message, null, null);

		assertFalse(out.containsKey("question"));
		Map<?, ?> toolExec = (Map<?, ?>) out.get("tool_exec");
		assertEquals("toolu_7", toolExec.get("tool_call_id"));
		assertEquals("ab", toolExec.get("content"));
		assertEquals(List.of("data:image/jpeg;base64,BBBB"), toolExec.get("imageDataUris"));
	}

	@Test
	void uploadsATurnImageToTheRoomFolderAndListsItsFileName(@TempDir Path roomDir) {
		Room room = mock(Room.class);
		when(room.getRoomFolderPath()).thenReturn(roomDir.toString());
		Map<String, Object> image = new HashMap<>();
		image.put("type", "image");
		image.put("source", Map.of("type", "base64", "media_type", "image/png", "data", "CCCC"));
		Map<String, Object> message = new HashMap<>();
		message.put("content", List.of(block("text", "text", "Describe"), image));

		try (MockedStatic<RoomUtils> roomUtils = mockStatic(RoomUtils.class)) {
			roomUtils.when(() -> RoomUtils.writeBase64ImageDataUriToDir(anyString(), any(Path.class)))
					.thenReturn("img-1.png");

			Map<String, Object> out = AnthropicMessagesHelper.normalizeMessageForAskRoom(message, room, null);

			assertEquals("Describe", out.get("question"));
			assertEquals(List.of("img-1.png"), out.get("images"));
			roomUtils.verify(() -> RoomUtils.writeBase64ImageDataUriToDir("data:image/png;base64,CCCC", roomDir));
		}
	}

	private static final class Event {
		private final String name;
		private final JsonObject data;

		private Event(String name, JsonObject data) {
			this.name = name;
			this.data = data;
		}
	}

	private static List<Event> events(String sse) {
		assertTrue(sse.endsWith("\n\n"), "every event ends with a blank line");
		List<Event> events = new ArrayList<>();
		for (String frame : sse.split("\n\n")) {
			String[] lines = frame.split("\n");
			assertEquals(2, lines.length, frame);
			assertTrue(lines[0].startsWith("event: "), frame);
			assertTrue(lines[1].startsWith("data: "), frame);
			events.add(new Event(lines[0].substring("event: ".length()),
					JsonParser.parseString(lines[1].substring("data: ".length())).getAsJsonObject()));
		}
		return events;
	}

	private static List<String> names(List<Event> events) {
		List<String> names = new ArrayList<>();
		for (Event event : events) {
			names.add(event.name);
		}
		return names;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> openAIMessages(Map<String, Object> normalized) {
		return (List<Map<String, Object>>) normalized.get("messages");
	}

	private static Map<String, Object> message(String role, Object content) {
		Map<String, Object> message = new HashMap<>();
		message.put("role", role);
		message.put("content", content);
		return message;
	}

	private static Map<String, Object> block(String type, String key, Object value) {
		Map<String, Object> block = new HashMap<>();
		block.put("type", type);
		block.put(key, value);
		return block;
	}

	private static Map<String, Object> toolResult(String toolUseId, Object content) {
		Map<String, Object> block = new HashMap<>();
		block.put("type", "tool_result");
		block.put("tool_use_id", toolUseId);
		block.put("content", content);
		return block;
	}
}
