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

public final class OpenAIChatCompletionsHelper {

	private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.disableHtmlEscaping().create();

	private static ObjectMapper mapper = new ObjectMapper();

	/**
	 * 
	 * @param engineId
	 * @param messageId
	 * @param creationTimestamp
	 * @param finishReason
	 * @param writer
	 * @throws IOException
	 * @throws JsonProcessingException
	 */
	public static void writeFinishReason(String engineId, String messageId, long creationTimestamp, String finishReason,
			Writer writer) throws JsonProcessingException, IOException {

		// delta is empty
		Map<String, Object> delta = new HashMap<>();
		// get added to a choice with the finish rason
		Map<String, Object> choice = new HashMap<>();
		choice.put("index", 0);
		choice.put("delta", delta);
		choice.put("finish_reason", finishReason);
		// which gets added to a choices array of length 1
		List<Map<String, Object>> choices = new ArrayList<>();
		choices.add(choice);
		// which gets added to the final chunk
		Map<String, Object> finalChunk = new HashMap<>();
		finalChunk.put("id", messageId);
		finalChunk.put("object", "chat.completion.chunk");
		finalChunk.put("created", creationTimestamp);
		finalChunk.put("model", engineId);
		finalChunk.put("choices", choices);

		writer.write("data: " + mapper.writeValueAsString(finalChunk) + "\n\n");

		writer.write("data: [DONE]\n\n");
		writer.flush();
	}

	/**
	 * 
	 * @param engineId
	 * @param messageId
	 * @param creationTimestamp
	 * @param newContent
	 * @param firstChunk
	 * @param writer
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	public static void writeContentChunk(String engineId, String messageId, long creationTimestamp, String newContent,
			boolean firstChunk, Writer writer) throws JsonProcessingException, IOException {
		// delta is lowest unit
		Map<String, Object> delta = new HashMap<>();
		// if first chunk include role
		if (!firstChunk) {
			delta.put("role", "assistant");
		}
		delta.put("content", newContent);

		// delta gets added to a choice
		Map<String, Object> choice = new HashMap<>();
		choice.put("index", 0);
		choice.put("delta", delta);
		choice.put("finish_reason", null);
		// choice gets added into a array of length 1
		List<Map<String, Object>> choices = new ArrayList<>();
		choices.add(choice);

		// choice is added to a chunk
		Map<String, Object> chunk = new HashMap<>();
		chunk.put("id", messageId);
		chunk.put("object", "chat.completion.chunk");
		chunk.put("created", creationTimestamp);
		chunk.put("model", engineId);
		chunk.put("choices", choices);

		// sending chunk as SSE event
		writer.write("data: " + mapper.writeValueAsString(chunk) + "\n\n");
		writer.flush();
	}

	/**
	 * 
	 * @param engineId
	 * @param messageId
	 * @param creationTimestamp
	 * @param dataMap
	 * @param firstChunk
	 * @param writer
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	public static void writeToolChunk(String engineId, String messageId, long creationTimestamp,
			Map<String, Object> dataMap, boolean firstChunk, Writer writer)
			throws JsonProcessingException, IOException {
		Number indexNum = (Number) dataMap.get("index");
		Long curToolIndex = indexNum != null ? indexNum.longValue() : 0L;
		// formatting as OpenAI streaming chunk
		// tool_call is the lowest level
		Map<String, Object> toolCall = new HashMap<>();
		toolCall.put("index", curToolIndex);
		if (dataMap.containsKey("id")) {
			toolCall.put("id", dataMap.get("id"));
		}
		// Chat-completions wire requires type="function". Responses-API engines
		// emit "function_call"; normalize unconditionally.
		toolCall.put("type", "function");
		if (dataMap.containsKey("function")) {
			Object fn = dataMap.get("function");
			if (fn instanceof Map) {
				@SuppressWarnings("unchecked")
				Map<String, Object> fnMap = new HashMap<>((Map<String, Object>) fn);
				Object args = fnMap.get("arguments");
				if (args != null && !(args instanceof String)) {
					fnMap.put("arguments", GSON.toJson(args));
				}
				toolCall.put("function", fnMap);
			} else {
				toolCall.put("function", fn);
			}
		} else {
			toolCall.put("function", new HashMap<>());
		}
		// toolCall goes into toolCalls
		List<Map<String, Object>> toolCalls = new ArrayList<>();
		toolCalls.add(toolCall);
		Map<String, Object> delta = new HashMap<>();
		// if first chunk include role
		if (!firstChunk) {
			delta.put("role", "assistant");
		}
		delta.put("content", null);
		delta.put("tool_calls", toolCalls);

		// delta gets added to a choice
		Map<String, Object> choice = new HashMap<>();
		choice.put("index", 0);
		choice.put("delta", delta);
		choice.put("finish_reason", null);
		// choice gets added into a array of length 1
		List<Map<String, Object>> choices = new ArrayList<>();
		choices.add(choice);

		// choice is added to a chunk
		Map<String, Object> chunk = new HashMap<>();
		chunk.put("id", messageId);
		chunk.put("object", "chat.completion.chunk");
		chunk.put("created", creationTimestamp);
		chunk.put("model", engineId);
		chunk.put("choices", choices);

		// sending chunk as SSE event
		writer.write("data: " + mapper.writeValueAsString(chunk) + "\n\n");
		writer.flush();
	}

	/**
	 * 
	 * @param engineId
	 * @param messageId
	 * @param creationTimestamp
	 * @param toolsResponseList
	 * @param writer
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	public static void writeFullToolResponseAsChunk(String engineId, String messageId, long creationTimestamp,
			List<Map<String, Object>> toolsResponseList, Writer writer) throws JsonProcessingException, IOException {

		// there might be multiple tools
		// loop through and send each one as a chunk
		long index = 0;
		for (Map<String, Object> toolResponseMap : toolsResponseList) {
			Map<String, Object> dataMap = new HashMap<>();
			dataMap.put("index", index);
			dataMap.put("id", dataMap.get(AskToolModelEngineResponse.ID_KEY));
			dataMap.put("type", toolResponseMap.get(AskToolModelEngineResponse.TYPE_KEY));
			Map<String, Object> functionMap = new HashMap<>();
			functionMap.put("name", toolResponseMap.get(AskToolModelEngineResponse.NAME_KEY));
			functionMap.put("arguments", toolResponseMap.get(AskToolModelEngineResponse.ARGUMENTS_KEY));
			dataMap.put("function", functionMap);

			writeToolChunk(engineId, messageId, creationTimestamp, dataMap, true, writer);
		}
	}

	/**
	 * 
	 * @param engineId
	 * @param llmResponse
	 * @return
	 */
	public static Map<String, Object> processAskModelEngineResponse(String engineId,
			AskModelEngineResponse llmResponse) {
		String messageId = llmResponse.getMessageId();
		Integer promptTokens = llmResponse.getNumberOfTokensInPrompt();
		Integer responseTokens = llmResponse.getNumberOfTokensInResponse();

		Map<String, Object> llmResponseMap = new HashMap<>();
		llmResponseMap.put("id", messageId);
		llmResponseMap.put("model", engineId);
		llmResponseMap.put("object", "chat.completion");
		// Get the number of seconds since the epoch
		long unixTimestamp = Instant.now().getEpochSecond();
		llmResponseMap.put("created", unixTimestamp);

		// usage object
		Map<String, Object> usage = new HashMap<>();
		if (promptTokens != null && responseTokens != null) {
			usage.put("completion_tokens", responseTokens);
			usage.put("prompt_tokens", promptTokens);
			usage.put("total_tokens", promptTokens + responseTokens);
		} else {
			if (responseTokens != null) {
				usage.put("completion_tokens", responseTokens);
			}
			if (promptTokens != null) {
				usage.put("prompt_tokens", promptTokens);
			}
		}
		llmResponseMap.put("usage", usage);

		// now we have to add the chat or the tool
		if (AskModelEngineResponse.TOOL.equals(llmResponse.getMessageType())) {
			AskToolModelEngineResponse toolResponse = (AskToolModelEngineResponse) llmResponse;

			List<Map<String, Object>> toolCalls = new ArrayList<>();
			List<ToolResponse> tools = toolResponse.getTools();
			for (ToolResponse t : tools) {
				Map<String, Object> thisToolMap = new HashMap<>();
				thisToolMap.put("id", t.getId());
				thisToolMap.put("type", "function");
				Map<String, Object> functionMap = new HashMap<>();
				functionMap.put("name", t.getName());
				functionMap.put("arguments", GSON.toJson(t.getArguments()));
				thisToolMap.put("function", functionMap);
				toolCalls.add(thisToolMap);
			}
			// message contains the actual response
			Map<String, Object> message = new HashMap<>();
			message.put("tool_calls", toolCalls);
			message.put("role", "assistant");

			// message goes into a choice object
			Map<String, Object> choice = new HashMap<>();
			choice.put("finish_reason", "tool_calls");
			choice.put("index", 0);
			choice.put("message", message);
			// choice goes into array of length 1
			List<Map<String, Object>> choicesList = new ArrayList<>();
			choicesList.add(choice);

			// then we create the final response map
			llmResponseMap.put("choices", choicesList);
		} else {
			// assume chat
			String response = llmResponse.getStringResponse();

			// message contains the actual response
			Map<String, Object> message = new HashMap<>();
			message.put("content", response);
			message.put("role", "assistant");

			// message goes into a choice object
			// assumption at the moment is finish_reason is always stop
			Map<String, Object> choice = new HashMap<>();
			choice.put("finish_reason", "stop");
			choice.put("index", 0);
			choice.put("message", message);
			// choice goes into array of length 1
			List<Map<String, Object>> choicesList = new ArrayList<>();
			choicesList.add(choice);

			// then we create the final response map
			llmResponseMap.put("choices", choicesList);
		}

		return llmResponseMap;
	}

	private OpenAIChatCompletionsHelper() {

	}
}
