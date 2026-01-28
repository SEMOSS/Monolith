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
	/**
	 * Normalizes Anthropic Messages API format to internal SEMOSS/ML format.
	 * 
	 * IMPORTANT: Each Anthropic message must map to internal messages while preserving
	 * the message boundary so that when Python rebuilds messages for Anthropic, the
	 * user/assistant alternation is maintained.
	 * 
	 * Strategy:
	 * - User messages with text/image -> single INPUT_TEXT message (with mediaInputs if images present)
	 * - User messages with tool_result -> one INPUT_TOOL_EXEC per tool_result  
	 * - Assistant messages with text -> single RESPONSE_TEXT message
	 * - Assistant messages with tool_use -> single RESPONSE_TOOL message (all tool_uses grouped)
	 */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> normalizeMessages(Object messages, Object systemPrompt) {
	    List<Map<String, Object>> normalizedMessages = new ArrayList<>();

	    // 1. Handle System Prompt
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
	                    if (systemText.length() > 0) systemText.append("\n");
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

	    // 2. Process Message List - each Anthropic message becomes one or more internal messages
	    // but we must be careful to maintain proper alternation
	    List<Map<String, Object>> messageList = (List<Map<String, Object>>) messages;
	    for (Map<String, Object> message : messageList) {
	        String role = (String) message.get("role");
	        Object content = message.get("content");

	        // If content is a simple string, map it directly
	        if (content instanceof String) {
	            String textContent = (String) content;
	            if (textContent != null && !textContent.trim().isEmpty()) {
	                Map<String, Object> normalizedMsg = new HashMap<>();
	                normalizedMsg.put("role", role);
	                normalizedMsg.put("type", role.equals("user") ? "INPUT_TEXT" : "RESPONSE_TEXT");
	                normalizedMsg.put("content", textContent);
	                if (role.equals("user")) {
	                    normalizedMsg.put("inputPrompt", textContent);
	                    normalizedMsg.put("inputUIPrompt", textContent);
	                }
	                normalizedMessages.add(normalizedMsg);
	            }
	            continue;
	        }

	        // If content is a list (Anthropic Block Format)
	        if (content instanceof List) {
	            List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) content;
	            
	            // Categorize blocks
	            List<Map<String, Object>> textBlocks = new ArrayList<>();
	            List<Map<String, Object>> imageBlocks = new ArrayList<>();
	            List<Map<String, Object>> toolUseBlocks = new ArrayList<>();
	            List<Map<String, Object>> toolResultBlocks = new ArrayList<>();
	            String thinkingContent = null;
	            String thinkingSignature = null;
	            
	            for (Map<String, Object> block : contentBlocks) {
	                String type = (String) block.get("type");
	                if ("text".equals(type)) {
	                    String text = (String) block.get("text");
	                    if (text != null && !text.trim().isEmpty()) {
	                        textBlocks.add(block);
	                    }
	                } else if ("image".equals(type)) {
	                    imageBlocks.add(block);
	                } else if ("tool_use".equals(type)) {
	                    toolUseBlocks.add(block);
	                } else if ("tool_result".equals(type)) {
	                    toolResultBlocks.add(block);
	                } else if ("thinking".equals(type)) {
	                    thinkingContent = (String) block.get("thinking");
	                    thinkingSignature = (String) block.get("signature");
	                }
	            }
	            
	            // Process based on role
	            if ("user".equals(role)) {
	                // For user messages: combine text+images into one INPUT_TEXT message
	                // Tool results become separate INPUT_TOOL_EXEC messages
	                
	                // First, handle text + images together
	                if (!textBlocks.isEmpty() || !imageBlocks.isEmpty()) {
	                    Map<String, Object> userMsg = new HashMap<>();
	                    userMsg.put("role", "user");
	                    userMsg.put("type", !imageBlocks.isEmpty() ? "INPUT_MEDIA" : "INPUT_TEXT");
	                    
	                    // Combine all text
	                    StringBuilder textContent = new StringBuilder();
	                    for (Map<String, Object> textBlock : textBlocks) {
	                        if (textContent.length() > 0) textContent.append("\n");
	                        textContent.append(textBlock.get("text"));
	                    }
	                    String finalText = textContent.toString();
	                    userMsg.put("content", finalText);
	                    userMsg.put("inputPrompt", finalText);
	                    userMsg.put("inputUIPrompt", finalText);
	                    
	                    // Add images as mediaInputs
	                    if (!imageBlocks.isEmpty()) {
	                        List<Map<String, Object>> mediaInputs = new ArrayList<>();
	                        for (Map<String, Object> imageBlock : imageBlocks) {
	                            mediaInputs.add(extractMediaInput(imageBlock));
	                        }
	                        userMsg.put("mediaInputs", mediaInputs);
	                    }
	                    
	                    normalizedMessages.add(userMsg);
	                }
	                
	                // Then, handle each tool_result as separate INPUT_TOOL_EXEC
	                for (Map<String, Object> toolResultBlock : toolResultBlocks) {
	                    Map<String, Object> toolExecMsg = new HashMap<>();
	                    toolExecMsg.put("role", "user");
	                    toolExecMsg.put("type", "INPUT_TOOL_EXEC");
	                    toolExecMsg.put("tool_call_id", toolResultBlock.get("tool_use_id"));
	                    
	                    Object toolContent = toolResultBlock.get("content");
	                    List<Map<String, Object>> mediaInputs = new ArrayList<>();
	                    StringBuilder textAggregator = new StringBuilder();

	                    if (toolContent instanceof List) {
	                        for (Map<String, Object> innerBlock : (List<Map<String, Object>>) toolContent) {
	                            String innerType = (String) innerBlock.get("type");
	                            if ("text".equals(innerType)) {
	                                textAggregator.append(innerBlock.get("text"));
	                            } else if ("image".equals(innerType)) {
	                                mediaInputs.add(extractMediaInput(innerBlock));
	                            }
	                        }
	                    } else if (toolContent instanceof String) {
	                        textAggregator.append(toolContent);
	                    }

	                    String resultText = textAggregator.toString();
	                    toolExecMsg.put("content", resultText);
	                    toolExecMsg.put("inputUIPrompt", resultText);
	                    if (!mediaInputs.isEmpty()) {
	                        toolExecMsg.put("mediaInputs", mediaInputs);
	                    }
	                    normalizedMessages.add(toolExecMsg);
	                }
	            } else if ("assistant".equals(role)) {
	                // For assistant messages: 
	                // - Text becomes RESPONSE_TEXT
	                // - Tool uses become single RESPONSE_TOOL with all tools
	                
	                // Handle text response
	                if (!textBlocks.isEmpty()) {
	                    Map<String, Object> assistantMsg = new HashMap<>();
	                    assistantMsg.put("role", "assistant");
	                    assistantMsg.put("type", "RESPONSE_TEXT");
	                    
	                    StringBuilder textContent = new StringBuilder();
	                    for (Map<String, Object> textBlock : textBlocks) {
	                        if (textContent.length() > 0) textContent.append("\n");
	                        textContent.append(textBlock.get("text"));
	                    }
	                    assistantMsg.put("content", textContent.toString());
	                    normalizedMessages.add(assistantMsg);
	                }
	                
	                // Handle tool uses - group ALL into ONE RESPONSE_TOOL message
	                if (!toolUseBlocks.isEmpty()) {
	                    Map<String, Object> toolMsg = new HashMap<>();
	                    toolMsg.put("role", "assistant");
	                    toolMsg.put("type", "RESPONSE_TOOL");
	                    
	                    List<Map<String, Object>> toolResponses = new ArrayList<>();
	                    for (Map<String, Object> toolBlock : toolUseBlocks) {
	                        Map<String, Object> toolResponse = new HashMap<>();
	                        toolResponse.put("id", toolBlock.get("id"));
	                        toolResponse.put("name", toolBlock.get("name"));
	                        toolResponse.put("arguments", GSON.toJson(toolBlock.get("input")));
	                        toolResponses.add(toolResponse);
	                    }
	                    toolMsg.put("tool_responses", toolResponses);
	                    
	                    // Include thinking content if present
	                    if (thinkingContent != null) {
	                        toolMsg.put("thinking", thinkingContent);
	                    }
	                    if (thinkingSignature != null) {
	                        toolMsg.put("thinking_signature", thinkingSignature);
	                    }
	                    
	                    normalizedMessages.add(toolMsg);
	                }
	            }
	        }
	    }
	    return normalizedMessages;
	}

	/**
	 * Helper to extract Anthropic image source into SEMOSS mediaInput format
	 */
	private static Map<String, Object> extractMediaInput(Map<String, Object> block) {
	    Map<String, Object> mediaInput = new HashMap<>();
	    Map<String, Object> source = (Map<String, Object>) block.get("source");
	    if (source != null) {
	        String sourceType = (String) source.get("type");
	        mediaInput.put("mimeType", source.get("media_type"));
	        if ("base64".equals(sourceType)) {
	            mediaInput.put("base64Data", source.get("data"));
	        } else if ("url".equals(sourceType)) {
	            mediaInput.put("sourceUrl", source.get("url"));
	        }
	    }
	    return mediaInput;
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
