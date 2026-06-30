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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;

import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse.ToolResponse;
import prerna.engine.impl.model.responses.EmbeddingsModelEngineResponse;

/**
 * Response/protocol helpers for the Ollama-compatible web endpoints.
 *
 * <p>
 * Translates SEMOSS model responses ({@link AskModelEngineResponse} and
 * friends) into the Ollama wire format for the {@code /api/chat},
 * {@code /api/generate} and {@code /api/embed} routes, and writes streaming
 * output as newline-delimited JSON (NDJSON). All members are static; this class
 * is not meant to be instantiated.
 */
public final class OllamaResponsesHelper {

	private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.disableHtmlEscaping().create();

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Serialize a payload to JSON and write it as a single newline-delimited line,
	 * flushing immediately. Ollama streams responses as NDJSON, so each chunk is
	 * one {@code writeJsonLine} call.
	 *
	 * @param payload the object to serialize as one JSON line
	 * @param writer  the destination stream writer
	 * @throws IOException if writing to the stream fails
	 */
	public static void writeJsonLine(Map<String, Object> payload, Writer writer) throws IOException {
		writer.write(MAPPER.writeValueAsString(payload));
		writer.write("\n");
		writer.flush();
	}

	/**
	 * Write the terminal chat chunk that marks the stream complete. Emits
	 * {@code done:true} with a normalized {@code done_reason} ({@code tool_calls}
	 * and {@code completed} are collapsed to {@code stop}) and an empty assistant
	 * message.
	 *
	 * @param finishReason     the upstream finish reason to normalize and report
	 * @param promptTokens     input/prompt token count (reserved, currently unused)
	 * @param completionTokens generated token count (reserved, currently unused)
	 * @param cachedTokens     cached prompt token count (reserved, currently
	 *                         unused)
	 * @param reasoningTokens  reasoning token count (reserved, currently unused)
	 * @param writer           the destination stream writer
	 * @throws JsonProcessingException if the payload cannot be serialized
	 * @throws IOException             if writing to the stream fails
	 */
	public static void writeFinishReason(String finishReason, Integer promptTokens, Integer completionTokens,
			Integer cachedTokens, Integer reasoningTokens, Writer writer) throws JsonProcessingException, IOException {

		// {"message":{"content":""},"done":true,"done_reason":"stop"}
		Map<String, Object> finishMap = new HashMap<>();
		finishMap.put("done", true);
		if (finishReason.equals("tool_calls") || finishReason.equals("completed")) {
			finishReason = "stop";
		}
		finishMap.put("done_reason", finishReason);
		Map<String, String> message = new HashMap<>();
		message.put("content", "");
		message.put("role", "assistant");
		finishMap.put("message", message);

		if (promptTokens != null) {
			finishMap.put("prompt_eval_count", promptTokens);
		}
		if (completionTokens != null) {
			finishMap.put("eval_count", completionTokens);
		}

		writeJsonLine(finishMap, writer);
	}

	/**
	 * Write a single streaming {@code /api/chat} content chunk ({@code done:false})
	 * carrying an assistant message delta.
	 *
	 * @param engineId   the model id echoed back on the chunk
	 * @param newContent the assistant text for this chunk
	 * @param writer     the destination stream writer
	 * @throws JsonProcessingException if the payload cannot be serialized
	 * @throws IOException             if writing to the stream fails
	 */
	public static void writeChatContentChunk(String engineId, String newContent, Writer writer)
			throws JsonProcessingException, IOException {
		// message is lowest unit
		Map<String, String> message = new HashMap<>();
		message.put("role", "assistant");
		message.put("content", newContent);

		// message is added to a chunk
		Map<String, Object> chunk = new HashMap<>();
		chunk.put("model", engineId);
		chunk.put("created_at", Instant.now().toString());
		chunk.put("message", message);
		chunk.put("done", false);

		writeJsonLine(chunk, writer);
	}

	/**
	 * Write a streaming {@code /api/chat} tool-call chunk. Strips the
	 * transport-only {@code id}/{@code index}/{@code type} keys and normalizes
	 * {@code function.arguments} into a map (parsing it from a JSON string when
	 * necessary) before emitting the chunk.
	 *
	 * @param engineId the model id (echoed onto the wrapped message)
	 * @param toolCall the raw tool-call map to normalize and emit
	 * @param writer   the destination stream writer
	 * @throws JsonProcessingException if the payload cannot be serialized
	 * @throws IOException             if writing to the stream fails
	 */
	public static void writeChatTool(String engineId, Map<String, Object> toolCall, Writer writer)
			throws JsonProcessingException, IOException {

		// unused keys
		toolCall.remove("id");
		toolCall.remove("index");
		toolCall.remove("type");

		Object fn = toolCall.get("function");
		Map<String, Object> fnMap = null;
		if (fn instanceof Map) {
			fnMap = (Map<String, Object>) fn;
		} else {
			fnMap = GSON.fromJson(fn + "", Map.class);
		}

		Object args = fnMap.get("arguments");
		if (args == null) {
			fnMap.put("arguments", new HashMap<>());
		} else if (args instanceof String) {
			fnMap.put("arguments", GSON.fromJson((String) args, Map.class));
		}

		List<Map<String, Object>> toolCalls = new ArrayList<>();
		toolCalls.add(toolCall);

		Map<String, Object> message = new HashMap<>();
		message.put("role", "assistant");
		message.put("content", "");
		message.put("tool_calls", toolCalls);

		Map<String, Object> chunk = new HashMap<>();
		chunk.put("message", message);
		chunk.put("done", false);

		writeJsonLine(chunk, writer);
	}

	/**
	 * Emit a non-streamed tool response as streaming chunks - one
	 * {@link #writeChatTool} chunk per tool in the list. Used when the model
	 * returns all tool calls at once but the client is consuming the chat stream.
	 *
	 * @param engineId          the model id echoed onto each chunk
	 * @param toolsResponseList the tool responses to emit, in order
	 * @param writer            the destination stream writer
	 * @throws JsonProcessingException if a payload cannot be serialized
	 * @throws IOException             if writing to the stream fails
	 */
	public static void writeFullChatToolResponseAsChunk(String engineId, List<Map<String, Object>> toolsResponseList,
			Writer writer) throws JsonProcessingException, IOException {
		// there might be multiple tools
		// loop through and send each one as a chunk
		long index = 0;
		for (Map<String, Object> toolResponseMap : toolsResponseList) {
			Map<String, Object> dataMap = new HashMap<>();
			dataMap.put("index", index);
			dataMap.put("id", toolResponseMap.get(AskToolModelEngineResponse.ID_KEY));
			dataMap.put("type", toolResponseMap.get(AskToolModelEngineResponse.TYPE_KEY));
			Map<String, Object> functionMap = new HashMap<>();
			functionMap.put("name", toolResponseMap.get(AskToolModelEngineResponse.NAME_KEY));
			functionMap.put("arguments", toolResponseMap.get(AskToolModelEngineResponse.ARGUMENTS_KEY));
			dataMap.put("function", functionMap);

			writeChatTool(engineId, dataMap, writer);
		}
	}

	/**
	 * Build the non-streaming {@code /api/chat} response object from a model
	 * response. Produces an assistant message - either text
	 * ({@code done_reason:stop}) or {@code tool_calls} when the model returned tool
	 * invocations - and appends token usage counts.
	 *
	 * @param model       the model id echoed back on the response
	 * @param llmResponse the model response to convert
	 * @return the Ollama chat response payload
	 */
	public static Map<String, Object> processFullChatResponse(String model, AskModelEngineResponse llmResponse) {
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

	/**
	 * Build the non-streaming {@code /api/generate} response object from a model
	 * response. The completion is returned in the {@code response} field (tool
	 * calls are JSON-encoded into that field) and token usage counts are appended.
	 *
	 * @param model       the model id echoed back on the response
	 * @param llmResponse the model response to convert
	 * @return the Ollama generate response payload
	 */
	public static Map<String, Object> processFullGenerateResponse(String model, AskModelEngineResponse llmResponse) {
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

	/**
	 * Write a single streaming {@code /api/generate} chunk ({@code done:false})
	 * carrying a piece of the completion text.
	 *
	 * @param model  the model id echoed back on the chunk
	 * @param text   the completion text for this chunk; {@code null} is written as
	 *               empty
	 * @param writer the destination stream writer
	 * @throws IOException if writing to the stream fails
	 */
	public static void writeGenerateContentChunk(String model, String text, Writer writer) throws IOException {
		Map<String, Object> chunk = new HashMap<>();
		chunk.put("model", model);
		chunk.put("created_at", Instant.now().toString());
		chunk.put("response", text == null ? "" : text);
		chunk.put("done", false);

		writeJsonLine(chunk, writer);
	}

	/**
	 * Normalize the inbound {@code messages} payload into the SEMOSS full-prompt
	 * message list. A raw string or non-list value is wrapped as a single
	 * {@code user} message; a list is converted entry by entry.
	 *
	 * <p>
	 * Ollama identifies tool results by position rather than id, so this method
	 * synthesizes sequential {@code tool_<n>} ids on assistant {@code tool_calls}
	 * and pairs each following tool result back to them by order - giving the
	 * full-prompt conversion logic the ids it expects.
	 *
	 * @param messagesInput the raw {@code messages} value from the request
	 * @return the normalized list of message maps (never {@code null})
	 */
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

		int uniqueToolCallIndex = 0;
		LinkedList<Map<String, Object>> toolCallTracker = new LinkedList<>();
		for (Object entry : (List<Object>) messagesInput) {
			if (entry instanceof Map<?, ?>) {
				Map<String, Object> raw = (Map<String, Object>) entry;
				Map<String, Object> msg = new HashMap<>();
				Object role = raw.get("role");
				msg.put("role", role != null ? role : "user");

				/**
				 * Ollama doesn't use tool_id but aligns to the index, so we need to fake ids
				 * for conversion logic of full_prompt to semoss message objects via
				 * {@link MessageUtils#convertFullPrompt(Object, Room, IModelEngine)} method.
				 */

				if (raw.containsKey("tool_calls")) {
					List<Map<String, Object>> toolCallList = (List<Map<String, Object>>) raw.get("tool_calls");
					for (Map<String, Object> toolCall : toolCallList) {
						String id = "tool_" + uniqueToolCallIndex++;
						toolCall.put("id", id);
						toolCall.put("type", "function");

						Map<String, Object> toolTrack = new HashMap<>();
						toolTrack.put("id", id);
						toolTrack.put("name", ((Map<String, Object>) toolCall.get("function")).get("name"));
						toolCallTracker.add(toolTrack);
					}
					msg.put("tool_calls", raw.get("tool_calls"));

				} else if (!toolCallTracker.isEmpty()) {
					// we got a tool call
					// so next responses should be results to match by index
					Map<String, Object> toolCall = toolCallTracker.removeFirst();

					msg.put("tool_call_id", toolCall.get("id"));
					msg.put("name", toolCall.get("name"));
					msg.put("content", stringifyContent(raw.get("content")));
				} else {
					msg.put("content", stringifyContent(raw.get("content")));
				}
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

	/**
	 * Resolve the prompt text for a generate request, preferring
	 * {@code promptInput} and falling back to {@code inputFallback} when the prompt
	 * is missing or blank.
	 *
	 * @param promptInput   the primary prompt value
	 * @param inputFallback the fallback value used when the prompt is empty
	 * @return the trimmed prompt string, or {@code null} if neither yields content
	 */
	public static String extractPrompt(Object promptInput, Object inputFallback) {
		String prompt = stringifyContent(promptInput);
		if (prompt == null || prompt.trim().isEmpty()) {
			prompt = stringifyContent(inputFallback);
		}
		return prompt == null ? null : prompt.trim();
	}

	/**
	 * Convert a SEMOSS tool response into the Ollama {@code tool_calls} list,
	 * mapping each tool to a {@code function} object with its name and arguments.
	 *
	 * @param toolResponse the tool response to convert
	 * @return the list of tool-call maps
	 */
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

	/**
	 * Flatten an arbitrary content value into a plain string. Strings are returned
	 * as-is; maps are unwrapped via their {@code text}/{@code content} keys; lists
	 * are recursively flattened and newline-joined. Used to coerce the many shapes
	 * a message {@code content} can take into a single string.
	 *
	 * @param value the content value to flatten, may be {@code null}
	 * @return the flattened string, or {@code null} when {@code value} is
	 *         {@code null}
	 */
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

	/**
	 * Append Ollama token-usage fields ({@code prompt_eval_count} /
	 * {@code eval_count}) to a response payload when the model reported them.
	 *
	 * @param payload     the response map to mutate
	 * @param llmResponse the model response carrying the token counts
	 */
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

	/**
	 * Build the Ollama embeddings response from an embeddings model response. A
	 * single-input request returns the first vector under {@code embedding}; a
	 * batch request returns all vectors under {@code embeddings}. Token usage
	 * counts are included when present.
	 *
	 * @param model              the model id echoed back on the response
	 * @param embeddingsResponse the embeddings model response to convert
	 * @param singleInput        whether the request carried a single input
	 * @return the Ollama embeddings response payload
	 */
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

	/**
	 * Collect the strings to embed from the request, preferring {@code input} (a
	 * single value or a list) and falling back to {@code prompt} when {@code input}
	 * yields nothing. Blank entries are skipped and all values are trimmed.
	 *
	 * @param promptInput the fallback {@code prompt} value
	 * @param inputInput  the primary {@code input} value (string or list)
	 * @return the list of non-blank input strings (never {@code null})
	 */
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

	/**
	 * Merge a streamed tool-call delta into the tool call being accumulated. The
	 * first chunk (carrying the function name) is copied wholesale; subsequent
	 * chunks only contribute additional {@code function.arguments} text, which is
	 * appended to the running arguments string.
	 *
	 * @param currentToolMap the tool call accumulated so far (mutated in place)
	 * @param newToolChunk   the next streamed tool-call delta to fold in
	 */
	public static void aggregateToolChunks(Map<String, Object> currentToolMap, Map<String, Object> newToolChunk) {
		// the only key that aggregates is the arguments
		// if the currentToolMap doesn't have arguments, then we can always just merge
		// the newToolChunk in

		if (!currentToolMap.containsKey("function")) {
			currentToolMap.putAll(newToolChunk);
			return;
		}

		Map<String, Object> currentFunctionMap = (Map<String, Object>) currentToolMap.get("function");
		String currentArguments = (String) currentFunctionMap.get("arguments");
		if (currentArguments == null) {
			currentArguments = "";
		}

		// at this point
		// if we have the function key in the current map
		// we have the initial function name chunk so only thing we are doing is merging
		// arguments
		currentArguments += ((Map<String, Object>) newToolChunk.get("function")).get("arguments");

		// update in the current function map the combined arguments string
		currentFunctionMap.put("arguments", currentArguments);
	}

	/**
	 * Private constructor - this class exposes only static helpers and must not be
	 * instantiated.
	 */
	private OllamaResponsesHelper() {
	}
}
