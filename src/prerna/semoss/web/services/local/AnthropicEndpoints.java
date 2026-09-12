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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.f4b6a3.uuid.alt.GUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.AbstractModelEngine;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.model.inferencetracking.ModelInferenceLogsUtils;
import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Utility;
import prerna.web.services.util.ModelPixelExecutor;
import prerna.web.services.util.WebUtility;

/**
 * Anthropic Messages API compatible endpoints. Allows connections from Claude
 * Code and other Anthropic SDK clients.
 * 
 * Endpoint: /model/anthropic/v1/messages
 * 
 * This class translates between Anthropic's Messages API format and the
 * internal ML format used by the platform.
 */
@Path("/model/anthropic")
@PermitAll
public class AnthropicEndpoints {

	private static final Logger classLogger = LogManager.getLogger(AnthropicEndpoints.class);

	private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.disableHtmlEscaping().create();

	/**
	 * Safety ceiling (in tokens) for the extended-thinking budget. The client's
	 * requested {@code thinking.budget_tokens} is forwarded to the backend, but
	 * clamped to this value so a large - or unbounded/dynamic (negative) - budget
	 * cannot trigger a multi-minute reasoning turn. Tune as needed; backend effort
	 * levels map roughly as medium=8192, high=24576, max=63999.
	 */
	private static final int MAX_THINKING_BUDGET_TOKENS = 16384;

	/**
	 * Main Messages API endpoint - handles both streaming and non-streaming
	 * requests. Compatible with Anthropic's /v1/messages endpoint.
	 * 
	 * Request format: { "model": "engine-id", "max_tokens": 1024, "messages":
	 * [{"role": "user", "content": "Hello"}], "system": "optional system prompt",
	 * "stream": false, "tools": [...], "temperature": 0.7 }
	 */
	@POST
	@Path("/v1/messages")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "text/event-stream" })
	public Response createMessage(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		User user = ModelPixelExecutor.getSessionUser(session);
		if (user == null) {
			return ModelPixelExecutor.invalidSessionResponse(request, session);
		}
		// set the user timezone
		ModelPixelExecutor.applyUserTimezone(user, request);

		final String SESSION_ID = session.getId();
		final String JOB_ID = GUID.v7().toUUID().toString();
		Insight insight = null;
		Room room = null;

		String claudeCodeSessionId = request.getHeader("x-claude-code-session-id");
		classLogger.debug("Anthropic-Session-Header::{}::{}", JOB_ID, claudeCodeSessionId);

		StringBuilder requestData = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				requestData.append(line);
			}
		} catch (IOException e) {
			classLogger.error("Error reading request body", e);
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse("invalid_request_error",
					"Failed to read request body");
			return WebUtility.getResponse(errorMap, 400);
		}

		classLogger.debug("Anthropic-Messages-API-request::{}::{}", JOB_ID, requestData.toString());

		Map<String, Object> dataMap;
		try {
			dataMap = GSON.fromJson(requestData.toString(), new TypeToken<Map<String, Object>>() {
			}.getType());
		} catch (JsonSyntaxException e) {
			classLogger.error("Error parsing request JSON", e);
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse("invalid_request_error",
					"Invalid JSON in request body");
			return WebUtility.getResponse(errorMap, 400);
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse("invalid_request_error",
					"model is required");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse("permission_error",
					"User does not have access to model: " + engineId);
			return WebUtility.getResponse(errorMap, 403);
		}

		IModelEngine engine = Utility.getModel(engineId);
		if (engine == null) {
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse("not_found_error",
					"Model not found: " + engineId);
			return WebUtility.getResponse(errorMap, 404);
		}

		// ROOM & INSIGHT LOGIC START ---------
		String insightId = WebUtility.inputSanitizer((String) dataMap.remove("insight_id"));
		String roomId = WebUtility.inputSanitizer((String) dataMap.remove("room_id"));
		if (roomId == null || roomId.isEmpty()) {
			// Fallback: roomId may be set as a request attribute by CodeAssistantFilter
			// when it parses the "room-{roomId}" segment from the x-api-key header.
			roomId = (String) request.getAttribute("roomId");
		}
		if (roomId == null || roomId.isEmpty()) {
			roomId = WebUtility.inputSanitizer(request.getHeader("x-semoss-room-id"));
		}
		if (roomId == null || roomId.isEmpty()) {
			roomId = WebUtility.inputSanitizer(claudeCodeSessionId);
		}
		if (roomId != null && !roomId.isEmpty()) {
			roomId = WebUtility.safePathSegment(roomId);
			if (roomId == null) {
				Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse("invalid_request_error",
						"Invalid room id");
				return WebUtility.getResponse(errorMap, 400);
			}
		}

		Object systemPromptBlock = dataMap.remove("system");
		String systemPromptString = AnthropicMessagesHelper.getSystemMessage(systemPromptBlock);

		if (roomId != null && insightId == null) {
			String userId = user.getPrimaryLoginToken().getId();
			Room existingRoom = ModelInferenceLogsUtils.getRoomById(roomId, userId);
			if (existingRoom != null) {
				Insight existingInsight = existingRoom.getInsight();
				if (existingInsight != null) {
					insightId = existingInsight.getInsightId();
				}
			}
		}

		if (insightId == null) {
			insight = new Insight();
			InsightStore.getInstance().put(insight);
			insightId = insight.getInsightId();
		} else {
			insight = InsightStore.getInstance().get(insightId);
			if (insight == null) {
				insight = new Insight();
				insight.setInsightId(insightId);
				InsightStore.getInstance().put(insight);
			}
		}
		insight.setUser(user);

		ModelPixelExecutor.initializeThreadStore(insight, SESSION_ID, JOB_ID);
		// ROOM & INSIGHT LOGIC END ---------

		Object messages = dataMap.remove("messages");
		if (messages == null) {
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse("invalid_request_error",
					"messages is required");
			return WebUtility.getResponse(errorMap, 400);
		}

		boolean isStreamingRequest = Boolean.parseBoolean(dataMap.getOrDefault("stream", false).toString());
		dataMap.remove("stream");
		// Forward the client's extended-thinking request to the backend instead of
		// dropping it. reasoning.normalize_reasoning() understands the Anthropic
		// {"type":"enabled","budget_tokens":N} shape. Previously this did
		// dataMap.remove("thinking"), which discarded the client's budget and let
		// the backend model fall back to its (often unbounded/dynamic) default -
		// producing multi-minute reasoning turns. Clamp the budget as a guard.
		Object thinkingParam = dataMap.get("thinking");
		if (thinkingParam instanceof Map) {
			Map<String, Object> thinkingMap = (Map<String, Object>) thinkingParam;
			Object budgetObj = thinkingMap.get("budget_tokens");
			if (budgetObj instanceof Number) {
				int requestedBudget = ((Number) budgetObj).intValue();
				if (requestedBudget < 0 || requestedBudget > MAX_THINKING_BUDGET_TOKENS) {
					classLogger.info("Anthropic-thinking-budget-clamp::{}::{}=>{}", JOB_ID, requestedBudget,
							MAX_THINKING_BUDGET_TOKENS);
					thinkingMap.put("budget_tokens", MAX_THINKING_BUDGET_TOKENS);
				}
			}
		}
		dataMap.remove("context_management");
		dataMap.remove("output_config");
		dataMap.remove("metadata");

		List<Map<String, Object>> messagesList = (List<Map<String, Object>>) messages;

		room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);

		Object tools = dataMap.remove("tools");

		// Load any persisted Vertex/Gemini thought_signatures for this room so we
		// can re-attach them to replayed tool_use blocks. Empty map for non-Vertex
		// paths (no file written) - no-op then.
		Map<String, String> thoughtSigMap = ThoughtSignatureSidecar.load(room.getRoomFolderPath());

		// HANDLE NON-STREAMING REQUESTS THROUGH THE LLM PIXEL
		if (!isStreamingRequest) {
			Map<String, Object> openAIFormat = AnthropicMessagesHelper
					.normalizeAllAnthropicMessagesToOpenAI(messagesList, systemPromptString, tools, thoughtSigMap);

			List<Map<String, Object>> openAIMessages = (List<Map<String, Object>>) openAIFormat.get("messages");
			dataMap.put(AbstractModelEngine.FULL_PROMPT, openAIMessages);
			dataMap.put("append_full_prompt", true);
			classLogger.info("Anthropic-normalized-prompt::{}::messages={} chars={}", JOB_ID, openAIMessages.size(),
					GSON.toJson(openAIMessages).length());

			if (openAIFormat.containsKey("tools")) {
				dataMap.put("tools", openAIFormat.get("tools"));
			}

			return handleNonStreamingRequest(engine, insight, room, dataMap, engineId);
		} else {

			final Insight finalInsight = insight;
			final Room finalRoom = room;

			Map<String, Object> openAIFormat = AnthropicMessagesHelper
					.normalizeAllAnthropicMessagesToOpenAI(messagesList, systemPromptString, tools, thoughtSigMap);

			List<Map<String, Object>> openAIMessages = (List<Map<String, Object>>) openAIFormat.get("messages");
			dataMap.put(AbstractModelEngine.FULL_PROMPT, openAIMessages);
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			String openAIJson = gson.toJson(openAIMessages);
			classLogger.info("Anthropic-normalized-prompt::{}::messages={} chars={}", JOB_ID, openAIMessages.size(),
					openAIJson.length());
			classLogger.debug("OpenAI-Formatted-Message::{}::{},", JOB_ID, openAIJson);

			if (openAIFormat.containsKey("tools")) {
				dataMap.put("tools", openAIFormat.get("tools"));
			}

			return handleStreamingRequest(engine, finalInsight, finalRoom, dataMap, SESSION_ID, JOB_ID, engineId,
					response);
		}
	}

	/**
	 * Handle non-streaming message request.
	 */
	private Response handleNonStreamingRequest(IModelEngine engine, Insight insight, Room room,
			Map<String, Object> dataMap, String engineId) {
		AskModelEngineResponse<?> llmResponse;
		try {
			llmResponse = ModelPixelExecutor.askModelSync(engine, insight, room, dataMap);
		} catch (Exception e) {
			classLogger.error("Synchronous model call failed for engine '{}'", engineId, e);
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse("api_error",
					"Error processing request: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}

		// Guard against empty completions: a non-TOOL response with no text means
		// the model returned nothing. Surface it as an error rather than a silent
		// empty end_turn (which would make the client halt with no signal).
		if (!AskModelEngineResponse.TOOL.equals(llmResponse.getMessageType())
				&& (llmResponse.getStringResponse() == null || llmResponse.getStringResponse().isEmpty())) {
			classLogger.error("Empty model completion for engine '{}': no content, tool calls, or usage.", engineId);
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse("api_error",
					"Model returned an empty response (no content or tool calls).");
			return WebUtility.getResponse(errorMap, 502);
		}

		Map<String, Object> responseMap = AnthropicMessagesHelper.processAskModelEngineResponse(engineId, llmResponse);
		return WebUtility.getResponse(responseMap, 200);
	}

	/**
	 * Handle streaming message request. Returns SSE stream with
	 * Anthropic-compatible events.
	 */
	private Response handleStreamingRequest(IModelEngine engine, final Insight FINAL_INSIGHT, final Room FINAL_ROOM,
			final Map<String, Object> FINAL_DATAMAP, final String FINAL_SESSION_ID, final String FINAL_JOB_ID,
			final String FINAL_ENGINE_ID, HttpServletResponse servletResponse) {

		// Disable servlet output buffering so SSE events and keep-alive
		// comments are flushed to the wire immediately, not held in an
		// internal buffer until it fills up (typically 8 KB in Tomcat).
		servletResponse.setBufferSize(0);

		return Response.ok().header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive").header("X-Content-Type-Options", "nosniff")
				.entity(new StreamingOutput() {
					@Override
					@SuppressWarnings("unchecked")
					public void write(OutputStream output) throws IOException, WebApplicationException {
						String messageId = "msg_" + GUID.v7().toUUID().toString().replace("-", "");
						String asyncJobId = null;
						long streamStartTime = System.currentTimeMillis();

						try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
							asyncJobId = ModelPixelExecutor.startAsyncModelRequest(engine, FINAL_INSIGHT, FINAL_ROOM,
									FINAL_DATAMAP, FINAL_SESSION_ID);
							classLogger.debug("Streaming job started: {}", asyncJobId);

							// Send message_start IMMEDIATELY to prevent client timeout.
							// The Anthropic API sends this event before any content is ready.
							AnthropicMessagesHelper.writeMessageStart(messageId, FINAL_ENGINE_ID, 0, writer);
							int contentBlockIndex = 0;
							boolean textBlockStarted = false;

							// Extended-thinking ("thinking") blocks lead the message at
							// index 0; text/tool blocks then shift by thinkingOffset.
							// Without this, the reasoning the backend streams
							// (stream_type="thinking") is silently dropped on the floor.
							boolean thinkingBlockStarted = false;
							boolean thinkingBlockClosed = false;
							int thinkingOffset = 0;

							// Track tool data across chunks
							Map<Integer, String> pendingToolIds = new HashMap<>();
							Map<Integer, String> pendingToolNames = new HashMap<>();
							Map<Integer, StringBuilder> pendingToolArgs = new HashMap<>();
							Map<Integer, Boolean> toolBlockStarted = new HashMap<>();
							Map<Integer, String> pendingToolSignatures = new HashMap<>();

							Integer capturedInputTokens = null;
							Integer capturedOutputTokens = null;
							Integer capturedCacheReadTokens = null;
							Integer capturedCacheCreationTokens = null;

							STREAM_COMPLETE_LOOP: while (true) {
								PixelJobRunner jt = PixelJobManager.getManager().getJob(asyncJobId);
								List<Map<String, Object>> partialResponseContent = PixelJobManager.getManager()
										.getStreamOut(asyncJobId);
								PixelJobStatus jobStatus = jt == null ? PixelJobStatus.UNKNOWN_JOB
										: jt.getPixelJobStatus();

								if (partialResponseContent != null && partialResponseContent.size() > 0) {
									for (Map<String, Object> streamObj : partialResponseContent) {
										String streamType = (String) streamObj.get("stream_type");
										Map<String, Object> streamData = (Map<String, Object>) streamObj.get("data");

										if ("usage".equalsIgnoreCase(streamType)) {
											Object inT = streamData.get("input_tokens");
											if (inT instanceof Number) {
												capturedInputTokens = ((Number) inT).intValue();
											}
											Object outT = streamData.get("output_tokens");
											if (outT instanceof Number) {
												capturedOutputTokens = ((Number) outT).intValue();
											}
											Object crT = streamData.get("cache_read_input_tokens");
											if (crT instanceof Number) {
												capturedCacheReadTokens = ((Number) crT).intValue();
											}
											Object ccT = streamData.get("cache_creation_input_tokens");
											if (ccT instanceof Number) {
												capturedCacheCreationTokens = ((Number) ccT).intValue();
											}
											continue;
										}

										if ("thinking".equalsIgnoreCase(streamType)) {
											Object thinkingObj = streamData.get("thinking");
											String thinkingChunk = thinkingObj != null ? thinkingObj.toString() : null;
											// Thinking must lead the message at index 0. If a text or tool
											// block already opened we cannot insert it before them, so drop
											// the chunk rather than corrupt content-block ordering.
											if (thinkingChunk != null && !thinkingChunk.isEmpty()
													&& !thinkingBlockClosed && !textBlockStarted
													&& toolBlockStarted.isEmpty()) {
												if (!thinkingBlockStarted) {
													AnthropicMessagesHelper.writeThinkingContentBlockStart(0, writer);
													thinkingBlockStarted = true;
													thinkingOffset = 1;
													contentBlockIndex = 1;
												}
												AnthropicMessagesHelper.writeThinkingDelta(0, thinkingChunk, writer);
											}
											continue;
										}

										if ("content".equalsIgnoreCase(streamType)) {
											if (streamData.containsKey("finish_reason")) {
												String finishReason = (String) streamData.get("finish_reason");

												for (Integer idx : toolBlockStarted.keySet()) {
													if (toolBlockStarted.get(idx)) {
														AnthropicMessagesHelper.writeContentBlockStop(idx, writer);
													}
												}

												if (textBlockStarted && toolBlockStarted.isEmpty()) {
													AnthropicMessagesHelper.writeContentBlockStop(contentBlockIndex,
															writer);
												}

												if (thinkingBlockStarted && !thinkingBlockClosed) {
													AnthropicMessagesHelper.writeContentBlockStop(0, writer);
													thinkingBlockClosed = true;
												}
												String stopReason = mapFinishReasonToStopReason(finishReason);
												AnthropicMessagesHelper.writeMessageDelta(stopReason,
														capturedInputTokens, capturedOutputTokens,
														capturedCacheReadTokens, capturedCacheCreationTokens, writer);
												AnthropicMessagesHelper.writeMessageStop(writer);
												break STREAM_COMPLETE_LOOP;
											} else {
												String newContent = (String) streamData.get("content");
												if (newContent != null && !newContent.isEmpty()) {
													if (!textBlockStarted) {
														if (thinkingBlockStarted && !thinkingBlockClosed) {
															AnthropicMessagesHelper.writeContentBlockStop(0, writer);
															thinkingBlockClosed = true;
														}
														long elapsed = System.currentTimeMillis() - streamStartTime;
														classLogger.info("SSE first content after {}ms for job {}",
																elapsed, asyncJobId);
														AnthropicMessagesHelper
																.writeTextContentBlockStart(contentBlockIndex, writer);
														textBlockStarted = true;
													}
													AnthropicMessagesHelper.writeTextDelta(contentBlockIndex,
															newContent, writer);
												}
											}
										} else {
											// Tool streaming
											if (streamData.containsKey("finish_reason")) {
												String finishReason = (String) streamData.get("finish_reason");

												for (Integer idx : toolBlockStarted.keySet()) {
													if (toolBlockStarted.get(idx)) {
														AnthropicMessagesHelper.writeContentBlockStop(idx, writer);
													}
												}

												if (textBlockStarted && toolBlockStarted.isEmpty()) {
													AnthropicMessagesHelper.writeContentBlockStop(contentBlockIndex,
															writer);
												}

												// Persist any captured Vertex thought_signatures
												// Anthropic SSE has no slot for these bytes, so they would otherwise be
												// lost the moment the SDK writes its JSONL transcript.
												if (!pendingToolSignatures.isEmpty() && FINAL_ROOM != null) {
													String roomFolder = FINAL_ROOM.getRoomFolderPath();
													for (Map.Entry<Integer, String> sigEntry : pendingToolSignatures
															.entrySet()) {
														String toolId = pendingToolIds.get(sigEntry.getKey());
														ThoughtSignatureSidecar.append(roomFolder, toolId,
																sigEntry.getValue());
													}
												}

												if (thinkingBlockStarted && !thinkingBlockClosed) {
													AnthropicMessagesHelper.writeContentBlockStop(0, writer);
													thinkingBlockClosed = true;
												}
												String stopReason = mapFinishReasonToStopReason(finishReason);
												AnthropicMessagesHelper.writeMessageDelta(stopReason,
														capturedInputTokens, capturedOutputTokens,
														capturedCacheReadTokens, capturedCacheCreationTokens, writer);
												AnthropicMessagesHelper.writeMessageStop(writer);
												break STREAM_COMPLETE_LOOP;
											} else {
												// Shift tool blocks past any leading thinking block (index 0).
												// thinkingOffset is fixed before tools stream (thinking leads),
												// so every chunk for a given tool resolves to the same index.
												Integer toolIndex = (streamData.get("index") != null
														? ((Number) streamData.get("index")).intValue()
														: 0) + thinkingOffset;

												if (streamData.containsKey("id")) {
													pendingToolIds.put(toolIndex, (String) streamData.get("id"));
												}

												if (streamData.containsKey("thought_signature")) {
													Object sig = streamData.get("thought_signature");
													if (sig instanceof String && !((String) sig).isEmpty()) {
														pendingToolSignatures.put(toolIndex, (String) sig);
													}
												}

												Map<String, Object> functionMap = (Map<String, Object>) streamData
														.get("function");
												if (functionMap != null) {
													if (functionMap.containsKey("name")) {
														pendingToolNames.put(toolIndex,
																(String) functionMap.get("name"));
													}
													if (functionMap.containsKey("arguments")) {
														Object argsObj = functionMap.get("arguments");
														String argsChunk = argsObj instanceof String ? (String) argsObj
																: GSON.toJson(argsObj);
														pendingToolArgs
																.computeIfAbsent(toolIndex, k -> new StringBuilder())
																.append(argsChunk);
													}
												}

												String toolId = pendingToolIds.get(toolIndex);
												String toolName = pendingToolNames.get(toolIndex);

												if (toolId != null && toolName != null
														&& !toolBlockStarted.getOrDefault(toolIndex, false)) {
													if (thinkingBlockStarted && !thinkingBlockClosed) {
														AnthropicMessagesHelper.writeContentBlockStop(0, writer);
														thinkingBlockClosed = true;
													}
													AnthropicMessagesHelper.writeToolUseContentBlockStart(toolIndex,
															toolId, toolName, writer);
													toolBlockStarted.put(toolIndex, true);

													StringBuilder accumulatedArgs = pendingToolArgs.get(toolIndex);
													if (accumulatedArgs != null && accumulatedArgs.length() > 0) {
														AnthropicMessagesHelper.writeInputJsonDelta(toolIndex,
																accumulatedArgs.toString(), writer);
													}
												} else if (toolBlockStarted.getOrDefault(toolIndex, false)
														&& functionMap != null
														&& functionMap.containsKey("arguments")) {
													Object argsObj = functionMap.get("arguments");
													String argsChunk = argsObj instanceof String ? (String) argsObj
															: GSON.toJson(argsObj);
													if (argsChunk != null && !argsChunk.isEmpty()) {
														AnthropicMessagesHelper.writeInputJsonDelta(toolIndex,
																argsChunk, writer);

													}
												}
											}
										}
									}
								}

								// Send proper SSE ping event when no data is available.
								// Ping events count as real SSE events and reset client
								// timeouts, unlike SSE comments which are ignored by
								// event-based timeout logic.
								if (partialResponseContent == null || partialResponseContent.isEmpty()) {
									AnthropicMessagesHelper.writePing(writer);
								}

								if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE
										&& (textBlockStarted || !toolBlockStarted.isEmpty())) {
									for (Integer idx : toolBlockStarted.keySet()) {
										if (toolBlockStarted.get(idx)) {
											AnthropicMessagesHelper.writeContentBlockStop(idx, writer);
										}
									}

									if (toolBlockStarted.isEmpty() && textBlockStarted) {
										AnthropicMessagesHelper.writeContentBlockStop(contentBlockIndex, writer);
									}

									if (!pendingToolSignatures.isEmpty() && FINAL_ROOM != null) {
										String roomFolder = FINAL_ROOM.getRoomFolderPath();
										for (Map.Entry<Integer, String> sigEntry : pendingToolSignatures.entrySet()) {
											String toolId = pendingToolIds.get(sigEntry.getKey());
											ThoughtSignatureSidecar.append(roomFolder, toolId, sigEntry.getValue());
										}
									}

									if (thinkingBlockStarted && !thinkingBlockClosed) {
										AnthropicMessagesHelper.writeContentBlockStop(0, writer);
										thinkingBlockClosed = true;
									}
									String stopReason = toolBlockStarted.isEmpty() ? "end_turn" : "tool_use";
									AnthropicMessagesHelper.writeMessageDelta(stopReason, capturedInputTokens,
											capturedOutputTokens, capturedCacheReadTokens, capturedCacheCreationTokens,
											writer);
									AnthropicMessagesHelper.writeMessageStop(writer);
									break STREAM_COMPLETE_LOOP;
								} else if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE && !textBlockStarted
										&& toolBlockStarted.isEmpty()) {
									// Close any leading thinking block before emitting the final-output
									// content/tool blocks (which shift by thinkingOffset).
									if (thinkingBlockStarted && !thinkingBlockClosed) {
										AnthropicMessagesHelper.writeContentBlockStop(0, writer);
										thinkingBlockClosed = true;
									}
									PixelRunner finalOutput = PixelJobManager.getManager().getOutput(asyncJobId);
									NounMetadata finalNoun = finalOutput.getResults().get(0);
									Object finalObject = finalNoun.getValue();

									String messageType = null;
									Map<String, Object> resultOutput = null;
									if (finalObject instanceof Map) {
										resultOutput = (Map<String, Object>) finalObject;
										messageType = (String) resultOutput.get("messageType");
									}

									if ("TOOL".equals(messageType)) {
										List<Map<String, Object>> toolResponses = (List<Map<String, Object>>) resultOutput
												.get("response");
										String roomFolder = FINAL_ROOM != null ? FINAL_ROOM.getRoomFolderPath() : null;
										if (toolResponses != null) {
											for (int i = 0; i < toolResponses.size(); i++) {
												Map<String, Object> toolResp = toolResponses.get(i);
												String toolId = (String) toolResp.get("id");
												String toolName = (String) toolResp.get("name");
												Object toolArgs = toolResp.get("arguments");
												String argsJson = toolArgs instanceof String ? (String) toolArgs
														: GSON.toJson(toolArgs);

												if (argsJson == null || argsJson.isEmpty()) {
													argsJson = "{}";
												}

												AnthropicMessagesHelper.writeToolUseContentBlockStart(
														i + thinkingOffset, toolId, toolName, writer);
												AnthropicMessagesHelper.writeInputJsonDelta(i + thinkingOffset,
														argsJson, writer);
												AnthropicMessagesHelper.writeContentBlockStop(i + thinkingOffset,
														writer);

												Object sig = toolResp.get("thought_signature");
												if (sig instanceof String && !((String) sig).isEmpty()) {
													ThoughtSignatureSidecar.append(roomFolder, toolId, (String) sig);
												}
											}
										}
										AnthropicMessagesHelper.writeMessageDelta("tool_use", capturedInputTokens,
												capturedOutputTokens, capturedCacheReadTokens,
												capturedCacheCreationTokens, writer);
									} else {
										String content = resultOutput != null ? (String) resultOutput.get("response")
												: "";

										// A completed job with no text, no tool calls, and no usage
										// means the model produced nothing. Emitting a bare empty
										// end_turn here makes Claude Code treat the turn as a clean
										// stop, so the harness halts silently with no error. Surface
										// it as an error event (and log the raw job output) instead.
										if (content == null || content.isEmpty()) {
											classLogger.error(
													"Empty model completion for engine '{}' job '{}': no content, tool calls, or usage. Raw job output: {}",
													FINAL_ENGINE_ID, asyncJobId, GSON.toJson(finalObject));
											AnthropicMessagesHelper.writeErrorEvent("api_error",
													"Model returned an empty response (no content, tool calls, or usage) for job "
															+ asyncJobId + ". See server logs for details.",
													writer);
											break STREAM_COMPLETE_LOOP;
										}

										AnthropicMessagesHelper.writeTextContentBlockStart(thinkingOffset, writer);
										AnthropicMessagesHelper.writeTextDelta(thinkingOffset, content, writer);
										AnthropicMessagesHelper.writeContentBlockStop(thinkingOffset, writer);
										AnthropicMessagesHelper.writeMessageDelta("end_turn", capturedInputTokens,
												capturedOutputTokens, capturedCacheReadTokens,
												capturedCacheCreationTokens, writer);
									}

									AnthropicMessagesHelper.writeMessageStop(writer);
									break STREAM_COMPLETE_LOOP;
								}

								try {
									Thread.sleep(50);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
									break;
								}
							}
						} catch (IOException ioe) {
							final String capturedJobId = asyncJobId;
							if (!WebUtility.handleStreamingException(ioe, classLogger, FINAL_ENGINE_ID, capturedJobId,
									() -> PixelJobManager.getManager().interruptThread(capturedJobId))) {
								classLogger.error("I/O error in streaming response for engine '{}' job '{}'",
										FINAL_ENGINE_ID, asyncJobId, ioe);
							}
						} catch (Throwable e) {
							classLogger.error("Error in streaming response", e);
						} finally {
							if (asyncJobId != null) {
								PixelJobManager.getManager().clearJob(asyncJobId);
								PixelJobManager.getManager().removeJob(asyncJobId);
							}
						}
					}
				}).build();
	}

	/**
	 * Map OpenAI finish reasons to Anthropic stop reasons.
	 */
	private String mapFinishReasonToStopReason(String finishReason) {
		if (finishReason == null) {
			return "end_turn";
		}
		switch (finishReason) {
		case "stop":
			return "end_turn";
		case "tool_calls":
			return "tool_use";
		case "length":
			return "max_tokens";
		case "content_filter":
			return "end_turn"; // Anthropic doesn't have content_filter, map to end_turn
		default:
			return "end_turn";
		}
	}

}
