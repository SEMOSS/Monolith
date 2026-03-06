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

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;

import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse.ToolResponse;
import prerna.engine.impl.model.responses.EmbeddingsModelEngineResponse;

public final class OllamaResponsesHelper {

	private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.disableHtmlEscaping().create();
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void writeJsonLine(Map<String, Object> payload, Writer writer) throws IOException {
		writer.write(MAPPER.writeValueAsString(payload));
		writer.write("\n");
		writer.flush();
	}

	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> normalizeChatMessages(Object messagesInput) {
		List<Map<String, Object>> normalized = new ArrayList<>();
		if (messagesInput == null) {
			return normalized;
		}

		if (messagesInput instanceof String) {
			Map<String, Object> message = new HashMap<>();
			message.put("role", "user");
			message.put("content", messagesInput);
			normalized.add(message);
			return normalized;
		}

		if (!(messagesInput instanceof List<?>)) {
			Map<String, Object> message = new HashMap<>();
			message.put("role", "user");
			message.put("content", messagesInput.toString());
			normalized.add(message);
			return normalized;
		}

		for (Object entry : (List<Object>) messagesInput) {
			if (entry instanceof Map<?, ?>) {
				Map<String, Object> raw = (Map<String, Object>) entry;
				Map<String, Object> msg = new HashMap<>();
				Object role = raw.get("role");
				msg.put("role", role != null ? role : "user");
				msg.put("content", stringifyContent(raw.get("content")));
				normalized.add(msg);
			} else if (entry != null) {
				Map<String, Object> message = new HashMap<>();
				message.put("role", "user");
				message.put("content", entry.toString());
				normalized.add(message);
			}
		}
		return normalized;
	}

	public static String extractPrompt(Object promptInput, Object inputFallback) {
		String prompt = stringifyContent(promptInput);
		if (prompt == null || prompt.trim().isEmpty()) {
			prompt = stringifyContent(inputFallback);
		}
		return prompt == null ? null : prompt.trim();
	}

	@SuppressWarnings("unchecked")
	public static List<String> extractEmbeddingInputs(Object promptInput, Object inputInput) {
		List<String> inputs = new ArrayList<>();

		if (inputInput instanceof List<?>) {
			for (Object value : (List<Object>) inputInput) {
				String text = stringifyContent(value);
				if (text != null && !text.trim().isEmpty()) {
					inputs.add(text.trim());
				}
			}
		} else {
			String inputText = stringifyContent(inputInput);
			if (inputText != null && !inputText.trim().isEmpty()) {
				inputs.add(inputText.trim());
			}
		}

		if (inputs.isEmpty()) {
			String promptText = stringifyContent(promptInput);
			if (promptText != null && !promptText.trim().isEmpty()) {
				inputs.add(promptText.trim());
			}
		}

		return inputs;
	}

	public static Map<String, Object> processGenerateResponse(String model, AskModelEngineResponse llmResponse) {
		Map<String, Object> response = new HashMap<>();
		response.put("model", model);
		response.put("created_at", Instant.now().toString());
		response.put("done", true);

		if (AskModelEngineResponse.TOOL.equals(llmResponse.getMessageType())) {
			AskToolModelEngineResponse toolResponse = (AskToolModelEngineResponse) llmResponse;
			response.put("response", GSON.toJson(toToolCalls(toolResponse)));
			response.put("done_reason", "tool_calls");
		} else {
			response.put("response", llmResponse.getStringResponse());
			response.put("done_reason", "stop");
		}

		appendTokenUsage(response, llmResponse);
		return response;
	}

	public static Map<String, Object> processChatResponse(String model, AskModelEngineResponse llmResponse) {
		Map<String, Object> response = new HashMap<>();
		response.put("model", model);
		response.put("created_at", Instant.now().toString());
		response.put("done", true);

		Map<String, Object> message = new HashMap<>();
		message.put("role", "assistant");

		if (AskModelEngineResponse.TOOL.equals(llmResponse.getMessageType())) {
			AskToolModelEngineResponse toolResponse = (AskToolModelEngineResponse) llmResponse;
			message.put("content", "");
			message.put("tool_calls", toToolCalls(toolResponse));
			response.put("done_reason", "tool_calls");
		} else {
			message.put("content", llmResponse.getStringResponse());
			response.put("done_reason", "stop");
		}

		response.put("message", message);
		appendTokenUsage(response, llmResponse);
		return response;
	}

	public static Map<String, Object> processEmbeddingsResponse(String model,
			EmbeddingsModelEngineResponse embeddingsResponse, boolean singleInput) {
		Map<String, Object> response = new HashMap<>();
		response.put("model", model);

		List<List<Double>> vectors = embeddingsResponse.getResponse();
		if (singleInput) {
			response.put("embedding", vectors == null || vectors.isEmpty() ? new ArrayList<>() : vectors.get(0));
		} else {
			response.put("embeddings", vectors == null ? new ArrayList<>() : vectors);
		}

		Integer promptTokens = embeddingsResponse.getNumberOfTokensInPrompt();
		Integer responseTokens = embeddingsResponse.getNumberOfTokensInResponse();
		if (promptTokens != null) {
			response.put("prompt_eval_count", promptTokens);
		}
		if (responseTokens != null) {
			response.put("eval_count", responseTokens);
		}

		return response;
	}

	public static Map<String, Object> createGenerateStreamChunk(String model, String text, boolean done,
			String doneReason, AskModelEngineResponse llmResponse) {
		Map<String, Object> chunk = new HashMap<>();
		chunk.put("model", model);
		chunk.put("created_at", Instant.now().toString());
		chunk.put("response", text == null ? "" : text);
		chunk.put("done", done);
		if (done && doneReason != null) {
			chunk.put("done_reason", doneReason);
		}
		if (done && llmResponse != null) {
			appendTokenUsage(chunk, llmResponse);
		}
		return chunk;
	}

	public static Map<String, Object> createChatStreamChunk(String model, String text,
			List<Map<String, Object>> toolCalls, boolean done, String doneReason, AskModelEngineResponse llmResponse) {
		Map<String, Object> chunk = new HashMap<>();
		chunk.put("model", model);
		chunk.put("created_at", Instant.now().toString());
		chunk.put("done", done);

		Map<String, Object> message = new HashMap<>();
		message.put("role", "assistant");
		message.put("content", text == null ? "" : text);
		if (toolCalls != null && !toolCalls.isEmpty()) {
			message.put("tool_calls", toolCalls);
		}
		chunk.put("message", message);

		if (done && doneReason != null) {
			chunk.put("done_reason", doneReason);
		}
		if (done && llmResponse != null) {
			appendTokenUsage(chunk, llmResponse);
		}
		return chunk;
	}

	private static void appendTokenUsage(Map<String, Object> payload, AskModelEngineResponse llmResponse) {
		Integer promptTokens = llmResponse.getNumberOfTokensInPrompt();
		Integer responseTokens = llmResponse.getNumberOfTokensInResponse();
		if (promptTokens != null) {
			payload.put("prompt_eval_count", promptTokens);
		}
		if (responseTokens != null) {
			payload.put("eval_count", responseTokens);
		}
	}

	private static List<Map<String, Object>> toToolCalls(AskToolModelEngineResponse toolResponse) {
		List<Map<String, Object>> toolCalls = new ArrayList<>();
		for (ToolResponse tool : toolResponse.getTools()) {
			Map<String, Object> call = new HashMap<>();
			if (tool.getId() != null) {
				call.put("id", tool.getId());
			}
			Map<String, Object> function = new HashMap<>();
			function.put("name", tool.getName());
			function.put("arguments", tool.getArguments());
			call.put("function", function);
			toolCalls.add(call);
		}
		return toolCalls;
	}

	@SuppressWarnings("unchecked")
	private static String stringifyContent(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof String) {
			return (String) value;
		}
		if (value instanceof Map<?, ?>) {
			Map<String, Object> map = (Map<String, Object>) value;
			if (map.containsKey("text")) {
				return stringifyContent(map.get("text"));
			}
			if (map.containsKey("content")) {
				return stringifyContent(map.get("content"));
			}
			return value.toString();
		}
		if (value instanceof List<?>) {
			StringBuilder builder = new StringBuilder();
			for (Object part : (List<Object>) value) {
				String text = stringifyContent(part);
				if (text != null && !text.isEmpty()) {
					if (builder.length() > 0) {
						builder.append("\n");
					}
					builder.append(text);
				}
			}
			return builder.toString();
		}
		return value.toString();
	}

	private OllamaResponsesHelper() {
	}
}
