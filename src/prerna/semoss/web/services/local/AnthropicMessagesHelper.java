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
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;

import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse;
import prerna.engine.impl.model.responses.AskToolModelEngineResponse.ToolResponse;
import prerna.engine.impl.model.message.MessageUtils;
import prerna.engine.impl.model.Room;
import prerna.om.Insight;

/**
 * Helper class for formatting Anthropic Messages API responses. Handles both
 * streaming and non-streaming response formats.
 * 
 * Anthropic streaming events follow this sequence: 1. message_start - contains
 * Message object with empty content 2. content_block_start - for each content
 * block 3. content_block_delta - incremental updates (text_delta,
 * input_json_delta, thinking_delta) 4. content_block_stop - when content block
 * is complete 5. message_delta - top-level changes (stop_reason, usage) 6.
 * message_stop - final event
 */
public final class AnthropicMessagesHelper {

	private static final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Writes an SSE event in Anthropic format. Format: event: {eventType}\ndata:
	 * {json}\n\n
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
	public static void writeContentBlockStop(int index, Writer writer) throws JsonProcessingException, IOException {
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
	 * Grab the Anthropic system prompt as a string This system prompt can be
	 * multiple blocks of type text
	 */
	public static String getSystemMessage(Object systemPrompt) {
		if (systemPrompt instanceof String) {
			return (String) systemPrompt;
		} else if (systemPrompt instanceof List) {
			StringBuilder finalSystemPrompt = new StringBuilder();
			List<Map<String, Object>> systemBlocks = (List<Map<String, Object>>) systemPrompt;

			for (Map<String, Object> block : systemBlocks) {
				if ("text".equals(block.get("type")) && block.containsKey("text")) {
					if (finalSystemPrompt.length() > 0)
						finalSystemPrompt.append("\n");
					finalSystemPrompt.append(block.get("text").toString());
				}
			}
			return finalSystemPrompt.toString();
		}
		return "";
	}
	
	/**
	 * Converts a full Anthropic message history to OpenAI/internal format.
	 * Handles all message types including assistant tool_use and user tool_result blocks.
	 * 
	 * @param messages     The full list of Anthropic formatted messages
	 * @param systemPrompt The system prompt string (already extracted)
	 * @param tools        The Anthropic formatted tools list
	 * @return Map containing "messages" (List) in OpenAI format and optionally "tools" (List) in MCP format
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> normalizeAllAnthropicMessagesToOpenAI(
	        List<Map<String, Object>> messages, 
	        String systemPrompt, 
	        Object tools) {
	    
	    Map<String, Object> result = new HashMap<>();
	    List<Map<String, Object>> openAIMessages = new ArrayList<>();

	    if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
	        Map<String, Object> systemMessage = new HashMap<>();
	        systemMessage.put("role", "system");
	        systemMessage.put("content", systemPrompt);
	        openAIMessages.add(systemMessage);
	    }

	    for (Map<String, Object> message : messages) {
	        String role = (String) message.get("role");
	        Object content = message.get("content");

	        if ("user".equals(role)) {
	            processUserMessage(content, openAIMessages);
	        } else if ("assistant".equals(role)) {
	            processAssistantMessage(content, openAIMessages);
	        }
	    }

	    result.put("messages", openAIMessages);

	    // 3. Convert tools from Anthropic to MCP format
	    if (tools != null) {
	        List<Map<String, Object>> mcpTools = normalizeToolsToMCP(tools);
	        if (mcpTools != null && !mcpTools.isEmpty()) {
	            result.put("tools", mcpTools);
	        }
	    }

	    return result;
	}

	/**
	 * Process an Anthropic user message and add to OpenAI message list.
	 * Handles text, images, and tool_result blocks.
	 */
	@SuppressWarnings("unchecked")
	private static void processUserMessage(Object content, List<Map<String, Object>> openAIMessages) {
	    if (content instanceof String) {
	        Map<String, Object> userMsg = new HashMap<>();
	        userMsg.put("role", "user");
	        userMsg.put("content", content);
	        openAIMessages.add(userMsg);
	        return;
	    }

	    if (!(content instanceof List)) {
	        return;
	    }

	    List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) content;

	    List<Map<String, Object>> textBlocks = new ArrayList<>();
	    List<Map<String, Object>> imageBlocks = new ArrayList<>();
	    List<Map<String, Object>> toolResultBlocks = new ArrayList<>();

	    for (Map<String, Object> block : contentBlocks) {
	        String type = (String) block.get("type");
	        if ("text".equals(type)) {
	            String text = (String) block.get("text");
	            if (text != null && !text.trim().isEmpty()) {
	                textBlocks.add(block);
	            }
	        } else if ("image".equals(type)) {
	            imageBlocks.add(block);
	        } else if ("tool_result".equals(type)) {
	            toolResultBlocks.add(block);
	        }
	    }

	    if (!textBlocks.isEmpty() || !imageBlocks.isEmpty()) {
	        Map<String, Object> userMsg = new HashMap<>();
	        userMsg.put("role", "user");

	        if (!imageBlocks.isEmpty()) {
	            List<Map<String, Object>> openAIContent = new ArrayList<>();

	            for (Map<String, Object> textBlock : textBlocks) {
	                Map<String, Object> textPart = new HashMap<>();
	                textPart.put("type", "text");
	                textPart.put("text", textBlock.get("text"));
	                openAIContent.add(textPart);
	            }

	            for (Map<String, Object> imageBlock : imageBlocks) {
	                String imageUrl = extractImageAsOpenAIUrl(imageBlock);
	                if (imageUrl != null) {
	                    Map<String, Object> imagePart = new HashMap<>();
	                    imagePart.put("type", "image_url");
	                    Map<String, Object> imageUrlObj = new HashMap<>();
	                    imageUrlObj.put("url", imageUrl);
	                    imagePart.put("image_url", imageUrlObj);
	                    openAIContent.add(imagePart);
	                }
	            }

	            userMsg.put("content", openAIContent);
	        } else {
	            StringBuilder textContent = new StringBuilder();
	            for (Map<String, Object> textBlock : textBlocks) {
	                if (textContent.length() > 0) {
	                    textContent.append("\n");
	                }
	                textContent.append(textBlock.get("text"));
	            }
	            userMsg.put("content", textContent.toString());
	        }

	        openAIMessages.add(userMsg);
	    }

	    for (Map<String, Object> toolResultBlock : toolResultBlocks) {
	        Map<String, Object> toolMsg = new HashMap<>();
	        toolMsg.put("role", "tool");
	        toolMsg.put("tool_call_id", toolResultBlock.get("tool_use_id"));

	        Object toolContent = toolResultBlock.get("content");
	        String toolContentStr = extractToolResultContent(toolContent);
	        toolMsg.put("content", toolContentStr);

	        openAIMessages.add(toolMsg);
	    }
	}

	/**
	 * Process an Anthropic assistant message and add to OpenAI message list.
	 * Handles text, thinking, and tool_use blocks.
	 */
	@SuppressWarnings("unchecked")
	private static void processAssistantMessage(Object content, List<Map<String, Object>> openAIMessages) {
	    if (content instanceof String) {
	        Map<String, Object> assistantMsg = new HashMap<>();
	        assistantMsg.put("role", "assistant");
	        assistantMsg.put("content", content);
	        openAIMessages.add(assistantMsg);
	        return;
	    }

	    if (!(content instanceof List)) {
	        return;
	    }

	    List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) content;

	    List<Map<String, Object>> textBlocks = new ArrayList<>();
	    List<Map<String, Object>> thinkingBlocks = new ArrayList<>();
	    List<Map<String, Object>> toolUseBlocks = new ArrayList<>();

	    for (Map<String, Object> block : contentBlocks) {
	        String type = (String) block.get("type");
	        if ("text".equals(type)) {
	            String text = (String) block.get("text");
	            if (text != null && !text.trim().isEmpty()) {
	                textBlocks.add(block);
	            }
	        } else if ("thinking".equals(type)) {
	            thinkingBlocks.add(block);
	        } else if ("tool_use".equals(type)) {
	            toolUseBlocks.add(block);
	        }
	    }

	    Map<String, Object> assistantMsg = new HashMap<>();
	    assistantMsg.put("role", "assistant");

	    StringBuilder textContent = new StringBuilder();
	    for (Map<String, Object> textBlock : textBlocks) {
	        if (textContent.length() > 0) {
	            textContent.append("\n");
	        }
	        textContent.append(textBlock.get("text"));
	    }

	    assistantMsg.put("content", textContent.toString());

	    if (!toolUseBlocks.isEmpty()) {
	        List<Map<String, Object>> toolCalls = new ArrayList<>();

	        for (Map<String, Object> toolUse : toolUseBlocks) {
	            Map<String, Object> toolCall = new HashMap<>();
	            toolCall.put("id", toolUse.get("id"));
	            toolCall.put("type", "function");

	            Map<String, Object> function = new HashMap<>();
	            function.put("name", toolUse.get("name"));

	            Object input = toolUse.get("input");
	            String argsJson;
	            if (input instanceof String) {
	                argsJson = (String) input;
	            } else {
	                argsJson = new Gson().toJson(input);
	            }
	            function.put("arguments", argsJson);

	            toolCall.put("function", function);
	            toolCalls.add(toolCall);
	        }

	        assistantMsg.put("tool_calls", toolCalls);
	    }

	    if (!thinkingBlocks.isEmpty()) {
	        StringBuilder thinking = new StringBuilder();
	        for (Map<String, Object> thinkingBlock : thinkingBlocks) {
	            if (thinking.length() > 0) {
	                thinking.append("\n");
	            }
	            thinking.append(thinkingBlock.get("thinking"));
	        }
	        assistantMsg.put("thinking", thinking.toString());
	    }

	    openAIMessages.add(assistantMsg);
	}

	/**
	 * Extract content from a tool_result block as a string.
	 */
	@SuppressWarnings("unchecked")
	private static String extractToolResultContent(Object toolContent) {
	    if (toolContent instanceof String) {
	        return (String) toolContent;
	    }

	    if (toolContent instanceof List) {
	        StringBuilder textAggregator = new StringBuilder();
	        for (Map<String, Object> innerBlock : (List<Map<String, Object>>) toolContent) {
	            String innerType = (String) innerBlock.get("type");
	            if ("text".equals(innerType)) {
	                if (textAggregator.length() > 0) {
	                    textAggregator.append("\n");
	                }
	                textAggregator.append(innerBlock.get("text"));
	            }
	            // Note: Images in tool results are harder to represent in OpenAI format
	            // may need to handle these separately or skip them
	        }
	        return textAggregator.toString();
	    }

	    return "";
	}
	
	/**
	 * Converts an Anthropic user message, system prompt, and tools to OpenAI/MCP format.
	 * Messages are converted to OpenAI format, tools are converted to MCP format.
	 * 
	 * @param message      The Anthropic formatted user message
	 * @param systemPrompt The system prompt string (already extracted)
	 * @param tools        The Anthropic formatted tools list
	 * @return Map containing "messages" (List) in OpenAI format and optionally "tools" (List) in MCP format
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> normalizeAnthropicMessagestoOpenAIMessages(Map<String, Object> message, String systemPrompt, Object tools) {
		Map<String, Object> result = new HashMap<>();
		List<Map<String, Object>> openAIMessages = new ArrayList<>();

		// 1. Add system prompt as first message if provided
		if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
			Map<String, Object> systemMessage = new HashMap<>();
			systemMessage.put("role", "system");
			systemMessage.put("content", systemPrompt);
			openAIMessages.add(systemMessage);
		}

		// 2. Process the Anthropic user message
		if (message != null) {
			String role = (String) message.get("role");
			Object content = message.get("content");

			// If content is a simple string, map it directly
			if (content instanceof String) {
				Map<String, Object> userMsg = new HashMap<>();
				userMsg.put("role", role != null ? role : "user");
				userMsg.put("content", content);
				openAIMessages.add(userMsg);
			}
			// If content is a list (Anthropic Block Format)
			else if (content instanceof List) {
				List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) content;

				// Categorize blocks
				List<Map<String, Object>> textBlocks = new ArrayList<>();
				List<Map<String, Object>> imageBlocks = new ArrayList<>();
				List<Map<String, Object>> toolResultBlocks = new ArrayList<>();

				for (Map<String, Object> block : contentBlocks) {
					String type = (String) block.get("type");
					if ("text".equals(type)) {
						String text = (String) block.get("text");
						if (text != null && !text.trim().isEmpty()) {
							textBlocks.add(block);
						}
					} else if ("image".equals(type)) {
						imageBlocks.add(block);
					} else if ("tool_result".equals(type)) {
						toolResultBlocks.add(block);
					}
				}

				// Handle text + images together as a single user message
				if (!textBlocks.isEmpty() || !imageBlocks.isEmpty()) {
					Map<String, Object> userMsg = new HashMap<>();
					userMsg.put("role", "user");

					// If we have images, use the array content format
					if (!imageBlocks.isEmpty()) {
						List<Map<String, Object>> openAIContent = new ArrayList<>();

						// Add all text blocks
						for (Map<String, Object> textBlock : textBlocks) {
							Map<String, Object> textPart = new HashMap<>();
							textPart.put("type", "text");
							textPart.put("text", textBlock.get("text"));
							openAIContent.add(textPart);
						}

						// Add all image blocks converted to OpenAI format
						for (Map<String, Object> imageBlock : imageBlocks) {
							String imageUrl = extractImageAsOpenAIUrl(imageBlock);
							if (imageUrl != null) {
								Map<String, Object> imagePart = new HashMap<>();
								imagePart.put("type", "image_url");

								Map<String, Object> imageUrlObj = new HashMap<>();
								imageUrlObj.put("url", imageUrl);
								imagePart.put("image_url", imageUrlObj);

								openAIContent.add(imagePart);
							}
						}

						userMsg.put("content", openAIContent);
					} else {
						// Text only - use simple string content
						StringBuilder textContent = new StringBuilder();
						for (Map<String, Object> textBlock : textBlocks) {
							if (textContent.length() > 0) {
								textContent.append("\n");
							}
							textContent.append(textBlock.get("text"));
						}
						userMsg.put("content", textContent.toString());
					}

					openAIMessages.add(userMsg);
				}

				// Handle tool_result blocks - each becomes a separate "tool" role message in OpenAI
				for (Map<String, Object> toolResultBlock : toolResultBlocks) {
					Map<String, Object> toolMsg = new HashMap<>();
					toolMsg.put("role", "tool");
					toolMsg.put("tool_call_id", toolResultBlock.get("tool_use_id"));

					Object toolContent = toolResultBlock.get("content");
					StringBuilder textAggregator = new StringBuilder();
					List<Map<String, Object>> contentParts = new ArrayList<>();
					boolean hasImages = false;

					if (toolContent instanceof List) {
						for (Map<String, Object> innerBlock : (List<Map<String, Object>>) toolContent) {
							String innerType = (String) innerBlock.get("type");
							if ("text".equals(innerType)) {
								textAggregator.append(innerBlock.get("text"));
							} else if ("image".equals(innerType)) {
								hasImages = true;
								String imageUrl = extractImageAsOpenAIUrl(innerBlock);
								if (imageUrl != null) {
									Map<String, Object> imagePart = new HashMap<>();
									imagePart.put("type", "image_url");

									Map<String, Object> imageUrlObj = new HashMap<>();
									imageUrlObj.put("url", imageUrl);
									imagePart.put("image_url", imageUrlObj);

									contentParts.add(imagePart);
								}
							}
						}
					} else if (toolContent instanceof String) {
						textAggregator.append(toolContent);
					}

					// If there are images, use array format; otherwise use string
					if (hasImages) {
						// Add text part first if present
						if (textAggregator.length() > 0) {
							Map<String, Object> textPart = new HashMap<>();
							textPart.put("type", "text");
							textPart.put("text", textAggregator.toString());
							contentParts.add(0, textPart);
						}
						toolMsg.put("content", contentParts);
					} else {
						toolMsg.put("content", textAggregator.toString());
					}

					openAIMessages.add(toolMsg);
				}
			}
		}

		result.put("messages", openAIMessages);

		// 3. Convert tools from Anthropic to MCP format
		if (tools != null) {
			List<Map<String, Object>> mcpTools = normalizeToolsToMCP(tools);
			if (mcpTools != null && !mcpTools.isEmpty()) {
				result.put("tools", mcpTools);
			}
		}

		return result;
	}

	/**
	 * Extract Anthropic image source as an OpenAI compatible URL.
	 * For base64 images, returns data URI format.
	 * For URL images, returns the URL directly.
	 */
	@SuppressWarnings("unchecked")
	private static String extractImageAsOpenAIUrl(Map<String, Object> block) {
		Map<String, Object> source = (Map<String, Object>) block.get("source");
		if (source != null) {
			String sourceType = (String) source.get("type");
			if ("base64".equals(sourceType)) {
				String mediaType = (String) source.get("media_type");
				String data = (String) source.get("data");
				return "data:" + mediaType + ";base64," + data;
			} else if ("url".equals(sourceType)) {
				return (String) source.get("url");
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> normalizeMessageForAskRoom(Map<String, Object> message, Room room, Insight insight) {
		Map<String, Object> normalizedMessageData = new HashMap<>();

		Object content = message.get("content");

		// If content is a simple string, map it directly
		if (content instanceof String) {
			String textContent = (String) content;
			if (textContent != null && !textContent.trim().isEmpty()) {
				normalizedMessageData.put("question", textContent);
				return normalizedMessageData;
			}
		}

		// If content is a list (Anthropic Block Format)
		if (content instanceof List) {
			List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) content;

			List<Map<String, Object>> textBlocks = new ArrayList<>();
			List<Map<String, Object>> imageBlocks = new ArrayList<>();
			List<Map<String, Object>> toolResultBlocks = new ArrayList<>();
			List<Map<String, Object>> documentBlocks = new ArrayList<>();

			for (Map<String, Object> block : contentBlocks) {
				String type = (String) block.get("type");
				if ("text".equals(type)) {
					String text = (String) block.get("text");
					if (text != null && !text.trim().isEmpty()) {
						textBlocks.add(block);
					}
				} else if ("image".equals(type)) {
					imageBlocks.add(block);
				} else if ("tool_result".equals(type)) {
					toolResultBlocks.add(block);
				} else if ("document".equals(type)) {
					documentBlocks.add(block);
				}

				StringBuilder finalTextContent = new StringBuilder();
				if (!textBlocks.isEmpty() || !imageBlocks.isEmpty()) {
					// Combine all text
					for (Map<String, Object> textBlock : textBlocks) {
						if (finalTextContent.length() > 0)
							finalTextContent.append("\n");
						finalTextContent.append(textBlock.get("text"));
					}
					String finalText = finalTextContent.toString();
					normalizedMessageData.put("question", finalText);

					// Add images to the room folder
					List<String> copiedImages = new ArrayList<String>();
					if (!imageBlocks.isEmpty()) {
						for (Map<String, Object> imageBlock : imageBlocks) {
							String imageUri = extractImageAsDataUri(imageBlock);
							if (imageUri != null) {
								copiedImages.add(uploadImageToRoom(imageUri, room, insight));
							}
						}
						normalizedMessageData.put("images", copiedImages);
					}
				}

				// Handle each tool_result
				for (Map<String, Object> toolResultBlock : toolResultBlocks) {
					Map<String, Object> toolExecMsg = new HashMap<>();
					toolExecMsg.put("tool_call_id", toolResultBlock.get("tool_use_id"));

					Object toolContent = toolResultBlock.get("content");
					List<String> imageDataUris = new ArrayList<>();
					StringBuilder textAggregator = new StringBuilder();

					if (toolContent instanceof List) {
						for (Map<String, Object> innerBlock : (List<Map<String, Object>>) toolContent) {
							String innerType = (String) innerBlock.get("type");
							if ("text".equals(innerType)) {
								textAggregator.append(innerBlock.get("text"));
							} else if ("image".equals(innerType)) {
								String imageUri = extractImageAsDataUri(innerBlock);
								if (imageUri != null) {
									imageDataUris.add(imageUri);
								}
							}
						}
					} else if (toolContent instanceof String) {
						textAggregator.append(toolContent);
					}

					String resultText = textAggregator.toString();
					toolExecMsg.put("content", resultText);

					if (!imageDataUris.isEmpty()) {
						toolExecMsg.put("imageDataUris", imageDataUris);
					}
					normalizedMessageData.put("tool_exec", toolExecMsg);
				}
			}
		}

		return normalizedMessageData;
	}

	/**
	 * Extract Anthropic image source as a data URI or URL string. Returns format
	 * compatible with MessageUtils.copyFilesToRoomFolder: - base64 images:
	 * "data:<media_type>;base64,<data>" - URL images: the URL string directly
	 */
	@SuppressWarnings("unchecked")
	private static String extractImageAsDataUri(Map<String, Object> block) {
		Map<String, Object> source = (Map<String, Object>) block.get("source");
		if (source != null) {
			String sourceType = (String) source.get("type");
			if ("base64".equals(sourceType)) {
				String mediaType = (String) source.get("media_type");
				String data = (String) source.get("data");
				return "data:" + mediaType + ";base64," + data;
			} else if ("url".equals(sourceType)) {
				return (String) source.get("url");
			}
		}
		return null;
	}


	private static String uploadImageToRoom(String inputImage, Room room, Insight insight) {
		Path roomPath = Path.of(room.getRoomFolderPath());
		return MessageUtils.writeBase64ImageDataUriToDir(inputImage, roomPath);
	}

	/**
	 * Normalizes Anthropic tools format to OpenAI tools format.
	 * 
	 * Anthropic format: {"name": "get_weather", "description": "...",
	 * "input_schema": {"type": "object", "properties": {...}}}
	 * 
	 * OpenAI format: {"type": "function", "function": {"name": "get_weather",
	 * "description": "...", "parameters": {...}}}
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

	/**
	 * Normalizes Anthropic tools format to MCP tools format.
	 * 
	 * Anthropic format: {"name": "get_weather", "description": "...",
	 * "input_schema": {"type": "object", "properties": {...}}}
	 * 
	 * MCP format: {"name": "get_weather", "description": "...",
	 * "inputSchema": {"type": "object", "properties": {...}}}
	 */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> normalizeToolsToMCP(Object tools) {
		if (!(tools instanceof List)) {
			return null;
		}

		List<Map<String, Object>> anthropicTools = (List<Map<String, Object>>) tools;
		List<Map<String, Object>> mcpTools = new ArrayList<>();

		for (Map<String, Object> tool : anthropicTools) {
			Map<String, Object> mcpTool = new HashMap<>();
			mcpTool.put("name", tool.get("name"));
			mcpTool.put("description", tool.get("description"));

			// Convert input_schema (Anthropic snake_case) to inputSchema (MCP camelCase)
			Object inputSchema = tool.get("input_schema");
			if (inputSchema != null) {
				mcpTool.put("inputSchema", inputSchema);
			}

			mcpTools.add(mcpTool);
		}

		return mcpTools;
	}

	// ==================== Response Processing ====================

	/**
	 * Process AskModelEngineResponse into Anthropic Messages API format. Used for
	 * non-streaming responses.
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
	 * Writes a complete streaming response for a text message. Convenience method
	 * for simple text responses.
	 */
	public static void writeCompleteTextStream(String messageId, String engineId, String text, Integer inputTokens,
			Integer outputTokens, Writer writer) throws JsonProcessingException, IOException {
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
