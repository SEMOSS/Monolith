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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;

import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse.ToolResponse;

/**
 * Helper class for formatting Anthropic Messages API responses.
 * Handles both streaming and non-streaming response formats.
 * 
 * Anthropic streaming events follow this sequence:
 * 1. message_start - contains Message object with empty content
 * 2. content_block_start - for each content block
 * 3. content_block_delta - incremental updates (text_delta, input_json_delta, thinking_delta)
 * 4. content_block_stop - when content block is complete
 * 5. message_delta - top-level changes (stop_reason, usage)
 * 6. message_stop - final event
 */
public final class AnthropicMessagesHelper {

	private static final Gson GSON = new GsonBuilder()
			.setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.disableHtmlEscaping()
			.create();

	private static final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Writes an SSE event in Anthropic format.
	 * Format: event: {eventType}\ndata: {json}\n\n
	 */
	public static void writeSSEEvent(String eventType, Map<String, Object> data, Writer writer) 
			throws JsonProcessingException, IOException {
		writer.write("event: " + eventType + "\n");
		writer.write("data: " + mapper.writeValueAsString(data) + "\n\n");
		writer.flush();
	}

	/**
	 * Writes a ping event to keep the connection alive.
	 */
	public static void writePingEvent(Writer writer) throws IOException {
		Map<String, Object> pingData = new HashMap<>();
		pingData.put("type", "ping");
		writeSSEEvent("ping", pingData, writer);
	}

	// ==================== Message Start/Stop Events ====================

	/**
	 * Writes the message_start event which initiates the streaming response.
	 * Contains a Message object with empty content array.
	 */
	public static void writeMessageStart(String messageId, String engineId, int inputTokens, Writer writer) 
			throws JsonProcessingException, IOException {
		Map<String, Object> messageStart = new HashMap<>();
		messageStart.put("type", "message_start");

		Map<String, Object> message = new HashMap<>();
		message.put("id", messageId);
		message.put("type", "message");
		message.put("role", "assistant");
		message.put("content", new ArrayList<>());
		message.put("model", engineId);
		message.put("stop_reason", null);
		message.put("stop_sequence", null);

		Map<String, Object> usage = new HashMap<>();
		usage.put("input_tokens", inputTokens);
		usage.put("output_tokens", 1);
		message.put("usage", usage);

		messageStart.put("message", message);
		writeSSEEvent("message_start", messageStart, writer);
	}

	/**
	 * Writes the message_delta event with final stop reason and usage.
	 */
	public static void writeMessageDelta(String stopReason, Integer outputTokens, Writer writer) 
			throws JsonProcessingException, IOException {
		Map<String, Object> messageDelta = new HashMap<>();
		messageDelta.put("type", "message_delta");

		Map<String, Object> delta = new HashMap<>();
		delta.put("stop_reason", stopReason);
		delta.put("stop_sequence", null);
		messageDelta.put("delta", delta);

		Map<String, Object> usage = new HashMap<>();
		usage.put("output_tokens", outputTokens != null ? outputTokens : 0);
		messageDelta.put("usage", usage);

		writeSSEEvent("message_delta", messageDelta, writer);
	}

	/**
	 * Writes the final message_stop event.
	 */
	public static void writeMessageStop(Writer writer) throws JsonProcessingException, IOException {
		Map<String, Object> messageStop = new HashMap<>();
		messageStop.put("type", "message_stop");
		writeSSEEvent("message_stop", messageStop, writer);
	}

	// ==================== Content Block Events ====================

	/**
	 * Writes content_block_start for a text content block.
	 */
	public static void writeTextContentBlockStart(int index, Writer writer) 
			throws JsonProcessingException, IOException {
		Map<String, Object> event = new HashMap<>();
		event.put("type", "content_block_start");
		event.put("index", index);

		Map<String, Object> contentBlock = new HashMap<>();
		contentBlock.put("type", "text");
		contentBlock.put("text", "");
		event.put("content_block", contentBlock);

		writeSSEEvent("content_block_start", event, writer);
	}

	/**
	 * Writes content_block_start for a tool_use content block.
	 */
	public static void writeToolUseContentBlockStart(int index, String toolId, String toolName, Writer writer) 
			throws JsonProcessingException, IOException {
		Map<String, Object> event = new HashMap<>();
		event.put("type", "content_block_start");
		event.put("index", index);

		Map<String, Object> contentBlock = new HashMap<>();
		contentBlock.put("type", "tool_use");
		contentBlock.put("id", toolId);
		contentBlock.put("name", toolName);
		contentBlock.put("input", new HashMap<>());
		event.put("content_block", contentBlock);

		writeSSEEvent("content_block_start", event, writer);
	}

	/**
	 * Writes content_block_start for a thinking content block.
	 */
	public static void writeThinkingContentBlockStart(int index, Writer writer) 
			throws JsonProcessingException, IOException {
		Map<String, Object> event = new HashMap<>();
		event.put("type", "content_block_start");
		event.put("index", index);

		Map<String, Object> contentBlock = new HashMap<>();
		contentBlock.put("type", "thinking");
		contentBlock.put("thinking", "");
		event.put("content_block", contentBlock);

		writeSSEEvent("content_block_start", event, writer);
	}

	/**
	 * Writes content_block_stop event.
	 */
	public static void writeContentBlockStop(int index, Writer writer) 
			throws JsonProcessingException, IOException {
		Map<String, Object> event = new HashMap<>();
		event.put("type", "content_block_stop");
		event.put("index", index);
		writeSSEEvent("content_block_stop", event, writer);
	}

	// ==================== Content Block Delta Events ====================

	/**
	 * Writes a text_delta content block delta.
	 */
	public static void writeTextDelta(int index, String text, Writer writer) 
			throws JsonProcessingException, IOException {
		Map<String, Object> event = new HashMap<>();
		event.put("type", "content_block_delta");
		event.put("index", index);

		Map<String, Object> delta = new HashMap<>();
		delta.put("type", "text_delta");
		delta.put("text", text);
		event.put("delta", delta);

		writeSSEEvent("content_block_delta", event, writer);
	}

	/**
	 * Writes an input_json_delta for tool use arguments.
	 */
	public static void writeInputJsonDelta(int index, String partialJson, Writer writer) 
			throws JsonProcessingException, IOException {
		Map<String, Object> event = new HashMap<>();
		event.put("type", "content_block_delta");
		event.put("index", index);

		Map<String, Object> delta = new HashMap<>();
		delta.put("type", "input_json_delta");
		delta.put("partial_json", partialJson);
		event.put("delta", delta);

		writeSSEEvent("content_block_delta", event, writer);
	}

	/**
	 * Writes a thinking_delta for extended thinking content.
	 */
	public static void writeThinkingDelta(int index, String thinking, Writer writer) 
			throws JsonProcessingException, IOException {
		Map<String, Object> event = new HashMap<>();
		event.put("type", "content_block_delta");
		event.put("index", index);

		Map<String, Object> delta = new HashMap<>();
		delta.put("type", "thinking_delta");
		delta.put("thinking", thinking);
		event.put("delta", delta);

		writeSSEEvent("content_block_delta", event, writer);
	}

	// ==================== Message Normalization ====================

	/**
	 * Normalizes Anthropic Messages API format to internal ML format.
	 * 
	 * Anthropic format:
	 * - messages: [{"role": "user", "content": "text" or [{"type": "text", "text": "..."}]}]
	 * - system: "system prompt" (separate from messages)
	 * - tools: [{"name": "...", "description": "...", "input_schema": {...}}]
	 * 
	 * Internal format expects:
	 * - messages with role and content
	 * - system message as first message with role "system"
	 * - tools in OpenAI format
	 */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> normalizeMessages(Object messages, Object systemPrompt) {
		List<Map<String, Object>> normalizedMessages = new ArrayList<>();

		if (systemPrompt != null) {
			Map<String, Object> systemMessage = new HashMap<>();
			systemMessage.put("role", "system");
			if (systemPrompt instanceof String) {
				systemMessage.put("content", systemPrompt);
			} else if (systemPrompt instanceof List) {
				List<Map<String, Object>> systemBlocks = (List<Map<String, Object>>) systemPrompt;
				StringBuilder systemText = new StringBuilder();
				for (Map<String, Object> block : systemBlocks) {
					if ("text".equals(block.get("type")) && block.containsKey("text")) {
						if (systemText.length() > 0) {
							systemText.append("\n");
						}
						systemText.append(block.get("text").toString());
					}
				}
				systemMessage.put("content", systemText.toString());
			}
			normalizedMessages.add(systemMessage);
		}

		if (!(messages instanceof List)) {
			return normalizedMessages;
		}

		List<Map<String, Object>> messageList = (List<Map<String, Object>>) messages;
		for (Map<String, Object> message : messageList) {
			Map<String, Object> normalizedMsg = new HashMap<>();
			normalizedMsg.put("role", message.get("role"));

			Object content = message.get("content");
			if (content instanceof String) {
				normalizedMsg.put("content", content);
			} else if (content instanceof List) {
				List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) content;
				StringBuilder textContent = new StringBuilder();
				List<Map<String, Object>> toolResults = new ArrayList<>();
				List<Map<String, Object>> toolCalls = new ArrayList<>();

				for (Map<String, Object> block : contentBlocks) {
					String type = (String) block.get("type");
					if ("text".equals(type)) {
						if (textContent.length() > 0) {
							textContent.append("\n");
						}
						textContent.append(block.get("text").toString());
					} else if ("tool_use".equals(type)) {
						Map<String, Object> toolCall = new HashMap<>();
						toolCall.put("id", block.get("id"));
						toolCall.put("type", "function");
						Map<String, Object> function = new HashMap<>();
						function.put("name", block.get("name"));
						Object input = block.get("input");
						function.put("arguments", input != null ? GSON.toJson(input) : "{}");
						toolCall.put("function", function);
						toolCalls.add(toolCall);
					} else if ("tool_result".equals(type)) {
						Map<String, Object> toolResult = new HashMap<>();
						toolResult.put("tool_call_id", block.get("tool_use_id"));
						toolResult.put("content", block.get("content"));
						toolResults.add(toolResult);
					} else if ("image".equals(type)) {
						// Handle image content - pass through for now
						// Could be enhanced to handle base64 images
						if (textContent.length() > 0) {
							textContent.append("\n");
						}
						textContent.append("[Image content]");
					}
				}

				if (!toolCalls.isEmpty()) {
					normalizedMsg.put("tool_calls", toolCalls);
					if (textContent.length() > 0) {
						normalizedMsg.put("content", textContent.toString());
					}
				}

				else if (!toolResults.isEmpty()) {
					if (textContent.length() > 0) {
						normalizedMsg.put("content", textContent.toString());
						normalizedMessages.add(normalizedMsg);
					}
					for (Map<String, Object> toolResult : toolResults) {
						Map<String, Object> toolMsg = new HashMap<>();
						toolMsg.put("role", "tool");
						toolMsg.put("tool_call_id", toolResult.get("tool_call_id"));
						toolMsg.put("content", toolResult.get("content"));
						normalizedMessages.add(toolMsg);
					}
					continue;
				} else {
					normalizedMsg.put("content", textContent.toString());
				}
			}

			normalizedMessages.add(normalizedMsg);
		}

		return normalizedMessages;
	}

	/**
	 * Normalizes Anthropic tools format to OpenAI tools format.
	 * 
	 * Anthropic format:
	 * {"name": "get_weather", "description": "...", "input_schema": {"type": "object", "properties": {...}}}
	 * 
	 * OpenAI format:
	 * {"type": "function", "function": {"name": "get_weather", "description": "...", "parameters": {...}}}
	 */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> normalizeTools(Object tools) {
		if (!(tools instanceof List)) {
			return null;
		}

		List<Map<String, Object>> anthropicTools = (List<Map<String, Object>>) tools;
		List<Map<String, Object>> normalizedTools = new ArrayList<>();

		for (Map<String, Object> tool : anthropicTools) {
			Map<String, Object> normalizedTool = new HashMap<>();
			normalizedTool.put("type", "function");

			Map<String, Object> function = new HashMap<>();
			function.put("name", tool.get("name"));
			function.put("description", tool.get("description"));
			
			Object inputSchema = tool.get("input_schema");
			if (inputSchema != null) {
				function.put("parameters", inputSchema);
			}

			normalizedTool.put("function", function);
			normalizedTools.add(normalizedTool);
		}

		return normalizedTools;
	}

	// ==================== Response Processing ====================

	/**
	 * Process AskModelEngineResponse into Anthropic Messages API format.
	 * Used for non-streaming responses.
	 */
	public static Map<String, Object> processAskModelEngineResponse(String engineId, 
			AskModelEngineResponse llmResponse) {
		String messageId = llmResponse.getMessageId();
		Integer promptTokens = llmResponse.getNumberOfTokensInPrompt();
		Integer responseTokens = llmResponse.getNumberOfTokensInResponse();

		Map<String, Object> responseMap = new HashMap<>();
		responseMap.put("id", messageId);
		responseMap.put("type", "message");
		responseMap.put("role", "assistant");
		responseMap.put("model", engineId);

		List<Map<String, Object>> content = new ArrayList<>();

		if (AskModelEngineResponse.TOOL.equals(llmResponse.getMessageType())) {
			AskToolModelEngineResponse toolResponse = (AskToolModelEngineResponse) llmResponse;
			List<ToolResponse> tools = toolResponse.getTools();

			for (ToolResponse t : tools) {
				Map<String, Object> toolUse = new HashMap<>();
				toolUse.put("type", "tool_use");
				toolUse.put("id", t.getId());
				toolUse.put("name", t.getName());
				toolUse.put("input", t.getArguments());
				content.add(toolUse);
			}

			responseMap.put("stop_reason", "tool_use");
		} else {
			String response = llmResponse.getStringResponse();

			Map<String, Object> textContent = new HashMap<>();
			textContent.put("type", "text");
			textContent.put("text", response);
			content.add(textContent);

			responseMap.put("stop_reason", "end_turn");
		}

		if (llmResponse.getThinking() != null && !llmResponse.getThinking().isEmpty()) {
			Map<String, Object> thinkingContent = new HashMap<>();
			thinkingContent.put("type", "thinking");
			thinkingContent.put("thinking", llmResponse.getThinking());
			content.add(0, thinkingContent);
		}

		responseMap.put("content", content);
		responseMap.put("stop_sequence", null);

		Map<String, Object> usage = new HashMap<>();
		if (promptTokens != null) {
			usage.put("input_tokens", promptTokens);
		}
		if (responseTokens != null) {
			usage.put("output_tokens", responseTokens);
		}
		responseMap.put("usage", usage);

		return responseMap;
	}

	/**
	 * Writes a complete streaming response for a text message.
	 * Convenience method for simple text responses.
	 */
	public static void writeCompleteTextStream(String messageId, String engineId, String text,
			Integer inputTokens, Integer outputTokens, Writer writer) 
			throws JsonProcessingException, IOException {
		// 1. message_start
		writeMessageStart(messageId, engineId, inputTokens != null ? inputTokens : 0, writer);

		// 2. content_block_start for text
		writeTextContentBlockStart(0, writer);

		// 3. text_delta with the content
		writeTextDelta(0, text, writer);

		// 4. content_block_stop
		writeContentBlockStop(0, writer);

		// 5. message_delta with stop reason
		writeMessageDelta("end_turn", outputTokens, writer);

		// 6. message_stop
		writeMessageStop(writer);
	}

	/**
	 * Writes error response in Anthropic format.
	 */
	public static Map<String, Object> createErrorResponse(String errorType, String message) {
		Map<String, Object> response = new HashMap<>();
		response.put("type", "error");
		
		Map<String, Object> error = new HashMap<>();
		error.put("type", errorType);
		error.put("message", message);
		response.put("error", error);

		return response;
	}

	/**
	 * Writes an error event for streaming responses.
	 */
	public static void writeErrorEvent(String errorType, String message, Writer writer) 
			throws JsonProcessingException, IOException {
		Map<String, Object> errorData = createErrorResponse(errorType, message);
		writeSSEEvent("error", errorData, writer);
	}

	private AnthropicMessagesHelper() {
	}
}
