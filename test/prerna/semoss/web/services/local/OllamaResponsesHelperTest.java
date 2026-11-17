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

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import prerna.engine.impl.model.responses.AskStringModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse;
import prerna.engine.impl.model.responses.EmbeddingsModelEngineResponse;

class OllamaResponsesHelperTest {

	@Test
	void writesEachPayloadAsOneJsonLine() throws Exception {
		StringWriter writer = new StringWriter();
		OllamaResponsesHelper.writeJsonLine(Map.of("a", 1), writer);
		OllamaResponsesHelper.writeJsonLine(Map.of("b", "two"), writer);

		assertEquals("{\"a\":1}\n{\"b\":\"two\"}\n", writer.toString());
	}

	@Test
	void collapsesToolCallAndCompletedFinishReasonsToStop() throws Exception {
		assertEquals("stop", lines(finish("tool_calls")).get(0).get("done_reason").getAsString());
		assertEquals("stop", lines(finish("completed")).get(0).get("done_reason").getAsString());
		assertEquals("stop", lines(finish("stop")).get(0).get("done_reason").getAsString());
		assertEquals("length", lines(finish("length")).get(0).get("done_reason").getAsString());
	}

	@Test
	void endsTheStreamWithADoneChunkCarryingTheTokenCounts() throws Exception {
		StringWriter writer = new StringWriter();
		OllamaResponsesHelper.writeFinishReason("stop", 12, 34, 5, 6, writer);

		JsonObject chunk = lines(writer.toString()).get(0);
		assertTrue(chunk.get("done").getAsBoolean());
		assertEquals(12, chunk.get("prompt_eval_count").getAsInt());
		assertEquals(34, chunk.get("eval_count").getAsInt());
		assertEquals("assistant", chunk.getAsJsonObject("message").get("role").getAsString());
		assertEquals("", chunk.getAsJsonObject("message").get("content").getAsString());

		JsonObject withoutCounts = lines(finish("stop")).get(0);
		assertFalse(withoutCounts.has("prompt_eval_count"));
		assertFalse(withoutCounts.has("eval_count"));
	}

	@Test
	void writesAChatContentChunkAsAnAssistantMessageThatIsNotDone() throws Exception {
		StringWriter writer = new StringWriter();
		OllamaResponsesHelper.writeChatContentChunk("engine-1", "Hel", writer);

		JsonObject chunk = lines(writer.toString()).get(0);
		assertEquals("engine-1", chunk.get("model").getAsString());
		assertFalse(chunk.get("done").getAsBoolean());
		assertTrue(chunk.has("created_at"));
		assertEquals("assistant", chunk.getAsJsonObject("message").get("role").getAsString());
		assertEquals("Hel", chunk.getAsJsonObject("message").get("content").getAsString());
	}

	@Test
	void writesAToolCallChunkWithItsArgumentsAsAnObject() throws Exception {
		Map<String, Object> function = new HashMap<>();
		function.put("name", "get_weather");
		function.put("arguments", "{\"city\":\"Paris\",\"days\":3}");
		Map<String, Object> toolCall = new HashMap<>();
		toolCall.put("id", "call_1");
		toolCall.put("index", 0);
		toolCall.put("type", "function");
		toolCall.put("function", function);

		StringWriter writer = new StringWriter();
		OllamaResponsesHelper.writeChatTool("engine-1", toolCall, writer);

		JsonObject chunk = lines(writer.toString()).get(0);
		assertFalse(chunk.get("done").getAsBoolean());
		JsonObject message = chunk.getAsJsonObject("message");
		assertEquals("", message.get("content").getAsString());
		JsonArray toolCalls = message.getAsJsonArray("tool_calls");
		assertEquals(1, toolCalls.size());
		JsonObject call = toolCalls.get(0).getAsJsonObject();
		assertFalse(call.has("id"), "tool calls are matched by position, not id");
		assertFalse(call.has("index"));
		assertFalse(call.has("type"));
		JsonObject fn = call.getAsJsonObject("function");
		assertEquals("get_weather", fn.get("name").getAsString());
		assertEquals("Paris", fn.getAsJsonObject("arguments").get("city").getAsString());
		assertEquals(3, fn.getAsJsonObject("arguments").get("days").getAsInt());
	}

	@Test
	void sendsMissingToolArgumentsAsAnEmptyObjectAndKeepsMapArguments() throws Exception {
		Map<String, Object> noArgs = new HashMap<>();
		noArgs.put("function", new HashMap<>(Map.of("name", "noop")));
		Map<String, Object> mapArgs = new HashMap<>();
		mapArgs.put("function", new HashMap<>(Map.of("name", "lookup", "arguments", Map.of("q", "x"))));

		StringWriter writer = new StringWriter();
		OllamaResponsesHelper.writeChatTool("engine-1", noArgs, writer);
		OllamaResponsesHelper.writeChatTool("engine-1", mapArgs, writer);

		List<JsonObject> chunks = lines(writer.toString());
		assertEquals(0, toolFunction(chunks.get(0)).getAsJsonObject("arguments").size());
		assertEquals("x", toolFunction(chunks.get(1)).getAsJsonObject("arguments").get("q").getAsString());
	}

	@Test
	void writesOneChunkPerToolOfAFullToolResponse() throws Exception {
		List<Map<String, Object>> tools = new ArrayList<>();
		tools.add(new HashMap<>(Map.of("id", "a", "type", "function", "name", "first", "arguments", "{\"x\":1}")));
		tools.add(new HashMap<>(Map.of("id", "b", "type", "function", "name", "second", "arguments", Map.of("y", 2))));

		StringWriter writer = new StringWriter();
		OllamaResponsesHelper.writeFullChatToolResponseAsChunk("engine-1", tools, writer);

		List<JsonObject> chunks = lines(writer.toString());
		assertEquals(2, chunks.size());
		assertEquals("first", toolFunction(chunks.get(0)).get("name").getAsString());
		assertEquals(1, toolFunction(chunks.get(0)).getAsJsonObject("arguments").get("x").getAsInt());
		assertEquals("second", toolFunction(chunks.get(1)).get("name").getAsString());
		assertEquals(2, toolFunction(chunks.get(1)).getAsJsonObject("arguments").get("y").getAsInt());
	}

	@Test
	void answersAChatTextResponseAsADoneAssistantMessage() {
		Map<String, Object> out = OllamaResponsesHelper.processFullChatResponse("engine-1",
				new AskStringModelEngineResponse("Hello", 7, 2));

		assertEquals("engine-1", out.get("model"));
		assertEquals(true, out.get("done"));
		assertEquals("stop", out.get("done_reason"));
		assertTrue(out.containsKey("created_at"));
		assertEquals(Map.of("role", "assistant", "content", "Hello"), out.get("message"));
		assertEquals(7, out.get("prompt_eval_count"));
		assertEquals(2, out.get("eval_count"));
	}

	@Test
	void answersAChatToolResponseWithToolCallsAndNoText() {
		Map<String, Object> out = OllamaResponsesHelper.processFullChatResponse("engine-1", toolResponse());

		assertEquals("tool_calls", out.get("done_reason"));
		Map<?, ?> message = (Map<?, ?>) out.get("message");
		assertEquals("", message.get("content"));
		List<?> toolCalls = (List<?>) message.get("tool_calls");
		assertEquals(2, toolCalls.size());
		assertEquals(Map.of("id", "call_1", "function", Map.of("name", "get_weather", "arguments", Map.of("city", "Rome"))),
				toolCalls.get(0));
		assertFalse(((Map<?, ?>) toolCalls.get(1)).containsKey("id"), "a tool without an id is sent without one");
		assertFalse(out.containsKey("prompt_eval_count"));
	}

	@Test
	void answersAGenerateResponseInTheResponseField() {
		Map<String, Object> text = OllamaResponsesHelper.processFullGenerateResponse("engine-1",
				new AskStringModelEngineResponse("42", 1, 1));
		assertEquals("42", text.get("response"));
		assertEquals("stop", text.get("done_reason"));
		assertEquals(true, text.get("done"));
		assertEquals(1, text.get("eval_count"));

		Map<String, Object> tool = OllamaResponsesHelper.processFullGenerateResponse("engine-1", toolResponse());
		assertEquals("tool_calls", tool.get("done_reason"));
		JsonArray calls = JsonParser.parseString((String) tool.get("response")).getAsJsonArray();
		assertEquals("get_weather", calls.get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
	}

	@Test
	void writesGenerateChunksAndSendsNullTextAsEmpty() throws Exception {
		StringWriter writer = new StringWriter();
		OllamaResponsesHelper.writeGenerateContentChunk("engine-1", "abc", writer);
		OllamaResponsesHelper.writeGenerateContentChunk("engine-1", null, writer);

		List<JsonObject> chunks = lines(writer.toString());
		assertEquals("abc", chunks.get(0).get("response").getAsString());
		assertFalse(chunks.get(0).get("done").getAsBoolean());
		assertEquals("engine-1", chunks.get(0).get("model").getAsString());
		assertEquals("", chunks.get(1).get("response").getAsString());
	}

	@Test
	void wrapsAStringOrScalarMessagesValueAsOneUserMessage() {
		assertEquals(List.of(Map.of("role", "user", "content", "hi")), OllamaResponsesHelper.normalizeChatMessages("hi"));
		assertEquals(List.of(Map.of("role", "user", "content", "12")), OllamaResponsesHelper.normalizeChatMessages(12));
		assertTrue(OllamaResponsesHelper.normalizeChatMessages(null).isEmpty());
	}

	@Test
	void defaultsAMissingRoleToUserAndFlattensStructuredContent() {
		List<Object> messages = new ArrayList<>();
		messages.add(new HashMap<>(Map.of("content", "plain")));
		messages.add(new HashMap<>(Map.of("role", "system", "content",
				List.of(Map.of("type", "text", "text", "a"), Map.of("content", "b"), "c", Map.of("text", "")))));
		messages.add(null);
		messages.add("bare string");

		List<Map<String, Object>> out = OllamaResponsesHelper.normalizeChatMessages(messages);

		assertEquals(3, out.size(), "a null entry is skipped");
		assertEquals(Map.of("role", "user", "content", "plain"), out.get(0));
		assertEquals(Map.of("role", "system", "content", "a\nb\nc"), out.get(1));
		assertEquals(Map.of("role", "user", "content", "bare string"), out.get(2));
	}

	@Test
	void pairsToolResultsWithTheToolCallsBeforeThemByOrder() {
		List<Object> toolCalls = new ArrayList<>();
		toolCalls.add(new HashMap<>(Map.of("function", Map.of("name", "first", "arguments", Map.of()))));
		toolCalls.add(new HashMap<>(Map.of("function", Map.of("name", "second", "arguments", Map.of()))));
		List<Object> messages = new ArrayList<>();
		messages.add(new HashMap<>(Map.of("role", "user", "content", "go")));
		messages.add(new HashMap<>(Map.of("role", "assistant", "content", "", "tool_calls", toolCalls)));
		messages.add(new HashMap<>(Map.of("role", "tool", "content", "result 1")));
		messages.add(new HashMap<>(Map.of("role", "tool", "content", "result 2")));
		messages.add(new HashMap<>(Map.of("role", "assistant", "content", "done")));

		List<Map<String, Object>> out = OllamaResponsesHelper.normalizeChatMessages(messages);

		assertEquals(5, out.size());
		List<?> calls = (List<?>) out.get(1).get("tool_calls");
		assertEquals("tool_0", ((Map<?, ?>) calls.get(0)).get("id"));
		assertEquals("function", ((Map<?, ?>) calls.get(0)).get("type"));
		assertEquals("tool_1", ((Map<?, ?>) calls.get(1)).get("id"));
		assertEquals(Map.of("role", "tool", "tool_call_id", "tool_0", "name", "first", "content", "result 1"),
				out.get(2));
		assertEquals(Map.of("role", "tool", "tool_call_id", "tool_1", "name", "second", "content", "result 2"),
				out.get(3));
		assertEquals(Map.of("role", "assistant", "content", "done"), out.get(4));
	}

	@Test
	void keepsToolCallIdsUniqueAcrossTurns() {
		List<Object> messages = new ArrayList<>();
		for (int turn = 0; turn < 2; turn++) {
			List<Object> toolCalls = new ArrayList<>();
			toolCalls.add(new HashMap<>(Map.of("function", Map.of("name", "t" + turn))));
			messages.add(new HashMap<>(Map.of("role", "assistant", "tool_calls", toolCalls)));
			messages.add(new HashMap<>(Map.of("role", "tool", "content", "r" + turn)));
		}

		List<Map<String, Object>> out = OllamaResponsesHelper.normalizeChatMessages(messages);

		assertEquals("tool_0", out.get(1).get("tool_call_id"));
		assertEquals("tool_1", out.get(3).get("tool_call_id"));
		assertEquals("t1", out.get(3).get("name"));
	}

	@Test
	void prefersThePromptAndFallsBackToInput() {
		assertEquals("ask", OllamaResponsesHelper.extractPrompt("  ask  ", "other"));
		assertEquals("other", OllamaResponsesHelper.extractPrompt("   ", " other "));
		assertEquals("x\ny", OllamaResponsesHelper.extractPrompt(List.of("x", "y"), null));
		assertNull(OllamaResponsesHelper.extractPrompt(null, null));
	}

	@Test
	void returnsOneVectorForASingleInputAndAllVectorsOtherwise() {
		EmbeddingsModelEngineResponse response = new EmbeddingsModelEngineResponse(
				List.of(List.of(0.1, 0.2), List.of(0.3, 0.4)), 9, null);

		Map<String, Object> single = OllamaResponsesHelper.processEmbeddingsResponse("engine-1", response, true);
		assertEquals(List.of(0.1, 0.2), single.get("embedding"));
		assertFalse(single.containsKey("embeddings"));
		assertEquals(9, single.get("prompt_eval_count"));
		assertFalse(single.containsKey("eval_count"));

		Map<String, Object> batch = OllamaResponsesHelper.processEmbeddingsResponse("engine-1", response, false);
		assertEquals(List.of(List.of(0.1, 0.2), List.of(0.3, 0.4)), batch.get("embeddings"));
		assertEquals("engine-1", batch.get("model"));

		EmbeddingsModelEngineResponse empty = new EmbeddingsModelEngineResponse(null, null, null);
		assertEquals(List.of(), OllamaResponsesHelper.processEmbeddingsResponse("e", empty, true).get("embedding"));
		assertEquals(List.of(), OllamaResponsesHelper.processEmbeddingsResponse("e", empty, false).get("embeddings"));
	}

	@Test
	void readsEmbeddingInputsFromInputBeforePrompt() {
		assertEquals(List.of("a", "b"), OllamaResponsesHelper.extractEmbeddingInputs("p", List.of(" a ", "b")));
		assertEquals(List.of("single"), OllamaResponsesHelper.extractEmbeddingInputs("p", " single "));
		assertEquals(List.of("p"), OllamaResponsesHelper.extractEmbeddingInputs(" p ", null));
		assertEquals(List.of("p"), OllamaResponsesHelper.extractEmbeddingInputs("p", "  "));
		assertTrue(OllamaResponsesHelper.extractEmbeddingInputs(null, null).isEmpty());
	}

	@Test
	void appendsStreamedArgumentTextToTheFirstToolChunk() {
		Map<String, Object> current = new HashMap<>();
		Map<String, Object> first = new HashMap<>();
		first.put("id", "call_1");
		first.put("function", new HashMap<>(Map.of("name", "get_weather", "arguments", "{\"ci")));
		OllamaResponsesHelper.aggregateToolChunks(current, first);
		OllamaResponsesHelper.aggregateToolChunks(current, Map.of("function", Map.of("arguments", "ty\":\"Oslo\"}")));

		assertEquals("call_1", current.get("id"));
		Map<?, ?> function = (Map<?, ?>) current.get("function");
		assertEquals("get_weather", function.get("name"));
		assertEquals("{\"city\":\"Oslo\"}", function.get("arguments"));

		Map<String, Object> noArgs = new HashMap<>();
		noArgs.put("function", new HashMap<>(Map.of("name", "late")));
		Map<String, Object> started = new HashMap<>();
		OllamaResponsesHelper.aggregateToolChunks(started, noArgs);
		OllamaResponsesHelper.aggregateToolChunks(started, Map.of("function", Map.of("arguments", "{}")));
		assertEquals("{}", ((Map<?, ?>) started.get("function")).get("arguments"));
	}

	private static AskToolModelEngineResponse toolResponse() {
		List<Map<String, Object>> calls = new ArrayList<>();
		calls.add(new HashMap<>(Map.of("id", "call_1", "name", "get_weather", "arguments", Map.of("city", "Rome"))));
		calls.add(new HashMap<>(Map.of("name", "noop", "arguments", Map.of())));
		return new AskToolModelEngineResponse(calls, null, null);
	}

	private static String finish(String reason) throws Exception {
		StringWriter writer = new StringWriter();
		OllamaResponsesHelper.writeFinishReason(reason, null, null, null, null, writer);
		return writer.toString();
	}

	private static JsonObject toolFunction(JsonObject chunk) {
		return chunk.getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject()
				.getAsJsonObject("function");
	}

	private static List<JsonObject> lines(String ndjson) {
		assertTrue(ndjson.endsWith("\n"), "every chunk ends with a newline");
		List<JsonObject> chunks = new ArrayList<>();
		for (String line : ndjson.split("\n")) {
			chunks.add(JsonParser.parseString(line).getAsJsonObject());
		}
		return chunks;
	}
}
