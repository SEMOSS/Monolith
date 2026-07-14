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
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.f4b6a3.uuid.alt.GUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.date.SemossDate;
import prerna.engine.api.IEngine;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.AbstractModelEngine;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.responses.EmbeddingsModelEngineResponse;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.reactor.security.MyEnginesReactor;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.ModelPixelExecutor;
import prerna.web.services.util.WebUtility;

@Path("/model/openai")
@PermitAll
public class OpenAIEndpoints {

	private static final Logger classLogger = LogManager.getLogger(NameServer.class);

	private static final String ERROR_TYPE = "errorType";
	private static final String INSIGHT_NOT_FOUND = "INSIGHT_NOT_FOUND";

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	@POST
	@Path("/v1/chat/completions")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "text/event-stream" })
	public Response runV1ModelChatCompletion(@Context HttpServletRequest request) {
		return runModelChatCompletion(request);
	}

	@POST
	@Path("/chat/completions")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "text/event-stream" })
	public Response runModelChatCompletion(@Context HttpServletRequest request) {
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

		// Retrieve raw data from the request
		StringBuilder requestData = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
			String line;

			while ((line = reader.readLine()) != null) {
				requestData.append(line);
			}
		} catch (IOException e) {
			classLogger.error("Failed to read chat completions request body for path '{}': {}", request.getRequestURI(),
					e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Bad Request: The 'data' parameter is missing.");
		}

		classLogger.info("Chat completion request data: {}", requestData);

		// Convert the JSON string to a Map
		Map<String, Object> dataMap;
		try {
			dataMap = GSON.fromJson(WebUtility.jsonSanitizer(requestData.toString()),
					new TypeToken<Map<String, Object>>() {
					}.getType());
		} catch (Exception e) {
			classLogger.error("Failed to parse chat completions request JSON for path '{}': {}",
					request.getRequestURI(), e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Error processing JSON data: " + e.getMessage());
		}

		boolean isStreamingRequest = false;
		if (dataMap.containsKey("stream")) {
			isStreamingRequest = Boolean.parseBoolean(dataMap.get("stream").toString());
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400,
					"Bad Request: The 'data' parameter is missing the required 'model' field.");
		}
		IModelEngine engine = Utility.getModel(engineId);

		Object fullPrompt = dataMap.remove("messages");
		if (fullPrompt == null) {
			return ModelPixelExecutor.errorResponse(400, "Please provide 'messages'.");
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return ModelPixelExecutor.errorResponse(403,
					"Model " + engineId + " does not exist or user does not have access to this model");
		}

		String insightId = WebUtility.inputSanitizer((String) dataMap.remove("insight_id"));
		if (insightId == null) {
			Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(SESSION_ID);
			if (sessionInsights == null || sessionInsights.isEmpty()) {
				// need to make a new insight here
				insight = new Insight();
				InsightStore.getInstance().put(insight);
				insightId = insight.getInsightId();
				InsightStore.getInstance().addToSessionHash(SESSION_ID, insightId);
			} else {
				// pull the insight id from the session set
				insightId = sessionInsights.iterator().next();
				insight = InsightStore.getInstance().get(insightId);
			}
		} else {
			insight = InsightStore.getInstance().get(insightId);
			// maybe its an insight id from another session
			InsightStore.getInstance().addToSessionHash(SESSION_ID, insightId);
		}

		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find the Insight with an Insight ID of " + insightId);
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
		// set the user
		insight.setUser(user);

		// Room ID from JSON body, or from bearer token 3rd segment
		// (GitHubCopilotManager)
		String roomId = WebUtility.inputSanitizer((String) dataMap.remove("room_id"));
		if (roomId == null) {
			roomId = (String) request.getAttribute("roomId");
		}
		// room name gets updated during parsing of full prompt
		room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);
		// this is if you are passing full prompt but want us to maintain the history
		boolean appendFullPrompt = Boolean
				.parseBoolean(WebUtility.inputSanitizer((String) dataMap.remove("append_full_prompt")) + "");

		ModelPixelExecutor.initializeThreadStore(insight, SESSION_ID, JOB_ID);

		final Insight FINAL_INSIGHT = insight;
		final Room FINAL_ROOM = room;

		dataMap.put(AbstractModelEngine.FULL_PROMPT, fullPrompt);
		dataMap.put(AbstractModelEngine.APPEND_FULL_PROMPT, appendFullPrompt);

		if (!isStreamingRequest) {
			AskModelEngineResponse llmResponse;
			try {
				llmResponse = ModelPixelExecutor.askModelSync(engine, FINAL_INSIGHT, FINAL_ROOM, dataMap);
			} catch (Exception e) {
				classLogger.error("Chat completions synchronous model call failed for engine '{}'", engineId, e);
				return ModelPixelExecutor.errorResponse(400, e.getMessage());
			}

			Map<String, Object> processedResposne = OpenAIChatCompletionsHelper.processAskModelEngineResponse(engineId,
					llmResponse);
			return WebUtility.getResponse(processedResposne, 200);
		} else {
			classLogger.info("Starting streaming response for model: {}", engineId);
			return Response.ok().header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
					.header("Connection", "keep-alive").entity(new StreamingOutput() {
						@Override
						public void write(OutputStream output) throws IOException, WebApplicationException {
							String messageId = "chatcmpl-" + JOB_ID;
							long creationTimestamp = Instant.now().getEpochSecond();

							String jobId = null;
							try (Writer writer = new BufferedWriter(
									new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
								// Execute model request but get job ID so can poll for partial responses
								jobId = ModelPixelExecutor.startAsyncModelRequest(engine, FINAL_INSIGHT, FINAL_ROOM,
										dataMap, SESSION_ID);

								boolean started = false;

								// Token usage forwarded by Python via stream_type="usage".
								// Surfaced as a usage-only chunk before the terminal [DONE].
								// Names follow Anthropic/Responses-API spelling on the wire
								// from Python; we translate to Chat-Completions wire fields
								// (prompt/completion) inside writeFinishReason.
								Integer capturedPromptTokens = null;
								Integer capturedCompletionTokens = null;
								Integer capturedCachedTokens = null;
								Integer capturedReasoningTokens = null;

								// polling streaming endpoint until response complete
								STREAM_COMPLETE_LOOP: while (true) {
									PixelJobRunner jt = PixelJobManager.getManager().getJob(jobId);
									List<Map<String, Object>> partialResponseContent = PixelJobManager.getManager()
											.getStreamOut(jobId);
									PixelJobStatus jobStatus = jt == null ? PixelJobStatus.UNKNOWN_JOB
											: jt.getPixelJobStatus();

									/**
									 * The partialResponseContent Map<String,Object> values comes from the python
									 * code
									 * 
									 * smss_stream_func defined in gaas_tcp_server_handler.py determines the payload
									 * {"stream_type": stream_type, "data": data}
									 * 
									 * The data portion of this payload matches the Dictionary definitions in
									 * semoss_streaming_util.py StreamUtil class
									 * 
									 */
									if (partialResponseContent != null && partialResponseContent.size() > 0) {
										for (Map<String, Object> streamObj : partialResponseContent) {
											String streamType = (String) streamObj.get("stream_type");
											Map<String, Object> dataMap = (Map<String, Object>) streamObj.get("data");
											if ("usage".equalsIgnoreCase(streamType)) {
												Object inT = dataMap.get("input_tokens");
												if (inT instanceof Number) {
													capturedPromptTokens = ((Number) inT).intValue();
												}
												Object outT = dataMap.get("output_tokens");
												if (outT instanceof Number) {
													capturedCompletionTokens = ((Number) outT).intValue();
												}
												Object crT = dataMap.get("cache_read_input_tokens");
												if (crT instanceof Number) {
													capturedCachedTokens = ((Number) crT).intValue();
												}
												Object rT = dataMap.get("reasoning_tokens");
												if (rT instanceof Number) {
													capturedReasoningTokens = ((Number) rT).intValue();
												}
												continue;
											}
											if (streamType.equalsIgnoreCase("content")) {
												if (dataMap.containsKey("finish_reason")) {
													String finishReason = (String) dataMap.get("finish_reason");
													// this is a map only on finish reason
													OpenAIChatCompletionsHelper.writeFinishReason(engineId, messageId,
															creationTimestamp, finishReason, capturedPromptTokens,
															capturedCompletionTokens, capturedCachedTokens,
															capturedReasoningTokens, writer);
													break STREAM_COMPLETE_LOOP;
												} else {
													String newContent = (String) dataMap.get("content");
													if (newContent != null && !newContent.isEmpty()) {
														OpenAIChatCompletionsHelper.writeContentChunk(engineId,
																messageId, creationTimestamp, newContent, started,
																writer);
														started = true;
													}
												}
												// TODO: handle "thinking" stream type from models like Gemini/Claude
												// that emit reasoning chunks separately. These would need to be
												// forwarded as content chunks since OpenAI chat completions format
												// has no dedicated thinking field.
//										} else if (streamType.equalsIgnoreCase("thinking")) {
//											String thinkingContent = (String) dataMap.get("thinking");
//											if (thinkingContent != null && !thinkingContent.isEmpty()) {
//												OpenAIChatCompletionsHelper.writeContentChunk(engineId,
//														messageId, creationTimestamp, thinkingContent, started,
//														writer);
//												started = true;
//											}
											} else {
												// assuming only other type is tool at the moment
												if (dataMap.containsKey("finish_reason")) {
													// send the finish chunk
													String finishReason = (String) dataMap.get("finish_reason");
													OpenAIChatCompletionsHelper.writeFinishReason(engineId, messageId,
															creationTimestamp, finishReason, capturedPromptTokens,
															capturedCompletionTokens, capturedCachedTokens,
															capturedReasoningTokens, writer);
													break STREAM_COMPLETE_LOOP;
												} else {
													OpenAIChatCompletionsHelper.writeToolChunk(engineId, messageId,
															creationTimestamp, dataMap, started, writer);
													started = true;
												}
											}
										}
									}

									// if job is complete, we should never hit this if
									// a completion should have been sent
									if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE && started) {
										// send final chunk with empty delta && finish_reason="stop"
										OpenAIChatCompletionsHelper.writeFinishReason(engineId, messageId,
												creationTimestamp, "stop", capturedPromptTokens,
												capturedCompletionTokens, capturedCachedTokens, capturedReasoningTokens,
												writer);
										break STREAM_COMPLETE_LOOP;
									} else if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE && !started) {
										// we didn't start
										// and there is no output
										// lets check the result
										// ... most likely this is a tool output
										PixelRunner finalOutput = PixelJobManager.getManager().getOutput(jobId);
										NounMetadata finalNoun = finalOutput.getResults().get(0);
										Object finalObject = finalNoun.getValue();
										String messageType = null;
										Map<String, Object> resultOutput = null;
										if (finalObject instanceof Map) {
											resultOutput = (Map<String, Object>) finalObject;
											messageType = (String) resultOutput.get("messageType");
										}

										if ("TOOL".equals(messageType)) {
											// this is a function call request that was not streamed
											// maybe the model doesn't support streaming of tools
											List<Map<String, Object>> response = (List<Map<String, Object>>) resultOutput
													.get("response");

											if (response != null && !response.isEmpty()) {
												OpenAIChatCompletionsHelper.writeFullToolResponseAsChunk(engineId,
														messageId, creationTimestamp, response, writer);
											}
											OpenAIChatCompletionsHelper.writeFinishReason(engineId, messageId,
													creationTimestamp, "tool_calls", capturedPromptTokens,
													capturedCompletionTokens, capturedCachedTokens,
													capturedReasoningTokens, writer);
										} else {
											// Handle regular text response
											String content = null;
											if (resultOutput != null) {
												content = (String) resultOutput.get("response");
												if (content != null && !content.isEmpty()) {
													OpenAIChatCompletionsHelper.writeContentChunk(engineId, messageId,
															creationTimestamp, content, true, writer);
												}
											}

											// send final chunk with empty delta && finish_reason="stop"
											OpenAIChatCompletionsHelper.writeFinishReason(engineId, messageId,
													creationTimestamp, "stop", capturedPromptTokens,
													capturedCompletionTokens, capturedCachedTokens,
													capturedReasoningTokens, writer);
										}

										// job is marked complete, always break
										break STREAM_COMPLETE_LOOP;
									}

									// small delay
									try {
										Thread.sleep(100);
									} catch (InterruptedException e) {
										Thread.currentThread().interrupt();
										break;
									}
								}
							} catch (IOException ioe) {
								final String capturedJobId = jobId;
								if (!WebUtility.handleStreamingException(ioe, classLogger, engineId, capturedJobId,
										() -> PixelJobManager.getManager().interruptThread(capturedJobId))) {
									classLogger.error(
											"Streaming chat completions response failed for engine '{}' and job '{}': {}",
											engineId, jobId, ioe.getMessage(), ioe);
									throw new WebApplicationException(ioe, 500);
								}
							} catch (Exception e) {
								classLogger.error(
										"Streaming chat completions response failed for engine '{}' and job '{}': {}",
										engineId, jobId, e.getMessage(), e);
								throw new WebApplicationException(e, 500);
							} finally {
								if (jobId != null) {
									PixelJobManager.getManager().clearJob(jobId);
									PixelJobManager.getManager().removeJob(jobId);
								}
							}
						}
					}).build();
		}
	}

	@POST
	@Path("/v1/responses")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "text/event-stream" })
	public Response runV1Responses(@Context HttpServletRequest request) {
		return runResponses(request);
	}

	@POST
	@Path("/responses")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "text/event-stream" })
	public Response runResponses(@Context HttpServletRequest request) {
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

		StringBuilder requestData = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				requestData.append(line);
			}
		} catch (IOException e) {
			classLogger.error("Failed to read responses request body for path '{}': {}", request.getRequestURI(),
					e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Bad Request: Data parameter missing.");
		}

		Map<String, Object> dataMap;
		try {
			dataMap = GSON.fromJson(WebUtility.jsonSanitizer(requestData.toString()),
					new TypeToken<Map<String, Object>>() {
					}.getType());
		} catch (Exception e) {
			return ModelPixelExecutor.errorResponse(400, "Error processing JSON: " + e.getMessage());
		}

		boolean isStreamingRequest = Boolean.parseBoolean(dataMap.getOrDefault("stream", false).toString());
		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));

		if (engineId == null || engineId.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400, "Missing 'model' field.");
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return ModelPixelExecutor.errorResponse(403, "Model " + engineId + " inaccessible.");
		}

		IModelEngine engine = Utility.getModel(engineId);
		Object messages = dataMap.remove("input");

		messages = OpenAIResponsesHelper.normalizeMessages(messages);

		String insightId = WebUtility.inputSanitizer((String) dataMap.remove("insight_id"));
		if (insightId == null) {
			Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(SESSION_ID);
			if (sessionInsights == null || sessionInsights.isEmpty()) {
				insight = new Insight();
				InsightStore.getInstance().put(insight);
				insightId = insight.getInsightId();
				InsightStore.getInstance().addToSessionHash(SESSION_ID, insightId);
			} else {
				insightId = sessionInsights.iterator().next();
				insight = InsightStore.getInstance().get(insightId);
			}
		} else {
			insight = InsightStore.getInstance().get(insightId);
		}

		if (insight == null) {
			return ModelPixelExecutor.errorResponse(400, "Insight not found: " + insightId);
		}
		insight.setUser(user);

		// Room ID from JSON body, or from bearer token 3rd segment
		// (GitHubCopilotManager)
		String roomId = WebUtility.inputSanitizer((String) dataMap.remove("room_id"));
		if (roomId == null) {
			roomId = (String) request.getAttribute("roomId");
		}
		if (roomId == null) {
			roomId = resolveRoomIdFromCodexHeaders(request);
		}
		room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);

		ModelPixelExecutor.initializeThreadStore(insight, SESSION_ID, JOB_ID);

		dataMap.put(AbstractModelEngine.FULL_PROMPT, messages);

		if (!isStreamingRequest) {
			try {
				AskModelEngineResponse llmResponse = ModelPixelExecutor.askModelSync(engine, insight, room, dataMap);

				Map<String, Object> processedResponse = OpenAIResponsesHelper.processAskModelEngineResponse(engineId,
						llmResponse);
				return WebUtility.getResponse(processedResponse, 200);
			} catch (Exception e) {
				classLogger.error("Responses synchronous model call failed for engine '{}'", engineId, e);
				return ModelPixelExecutor.errorResponse(400, e.getMessage());
			}
		} else {
			return handleStreamingResponse(engine, insight, room, dataMap, SESSION_ID, JOB_ID, engineId);
		}
	}

	private String resolveRoomIdFromCodexHeaders(HttpServletRequest request) {
		String threadId = getSanitizedHeader(request, "thread-id");
		if (threadId == null) {
			threadId = getSanitizedHeader(request, "thread_id");
		}
		if (threadId != null) {
			return threadId;
		}

		String sessionId = getSanitizedHeader(request, "session-id");
		if (sessionId == null) {
			sessionId = getSanitizedHeader(request, "session_id");
		}
		return sessionId;
	}

	private String getSanitizedHeader(HttpServletRequest request, String headerName) {
		return WebUtility.inputSanitizer(request.getHeader(headerName));
	}

	private Response handleStreamingResponse(IModelEngine engine, final Insight FINAL_INSIGHT, final Room FINAL_ROOM,
			final Map<String, Object> FINAL_DATA_MAP, final String FINAL_SESSION_ID, final String FINAL_JOB_ID,
			final String FINAL_ENGINE_ID) {
		classLogger.info("Starting responses streaming for engine: {}", FINAL_ENGINE_ID);

		return Response.ok().header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive").header("X-Content-Type-Options", "nosniff")
				.entity(new StreamingOutput() {
					@Override
					public void write(OutputStream output) throws IOException, WebApplicationException {
						String responseId = "resp_" + FINAL_JOB_ID;
						long creationTimestamp = Instant.now().getEpochSecond();
						String jobId = null;
						int seq = 0;

						// --- STATE TRACKING ---
						// These variables persist across chunks
						String currentItemId = null;
						String currentItemType = null;
						String currentToolName = null;
						boolean isContentPartOpen = false;

						// Image-generation item id; non-null while partial frames are
						// streaming for the same image. Cleared after the completed/done
						// pair so a subsequent image opens a fresh item.
						String imgItemId = null;

						int outputIndex = 0;
						int contentIndex = 0;
						StringBuilder currentAccumulator = new StringBuilder();

						// Token usage forwarded by Python via stream_type="usage".
						// Attached to response.completed.response.usage at end of stream.
						Integer capturedInputTokens = null;
						Integer capturedOutputTokens = null;
						Integer capturedCachedTokens = null;
						Integer capturedReasoningTokens = null;

						try (Writer writer = new BufferedWriter(
								new OutputStreamWriter(output, StandardCharsets.UTF_8))) {

							// 1. Initial Handshake Events
							OpenAIResponsesHelper.writeSSEEvent(OpenAIResponsesHelper.createBaseEvent(
									"response.created", seq++, responseId, FINAL_ENGINE_ID, creationTimestamp), writer);
							OpenAIResponsesHelper
									.writeSSEEvent(OpenAIResponsesHelper.createBaseEvent("response.in_progress", seq++,
											responseId, FINAL_ENGINE_ID, creationTimestamp), writer);

							jobId = ModelPixelExecutor.startAsyncModelRequest(engine, FINAL_INSIGHT, FINAL_ROOM,
									FINAL_DATA_MAP, FINAL_SESSION_ID);

							// 2. MAIN POLLING LOOP
							STREAM_LOOP: while (true) {
								PixelJobRunner jt = PixelJobManager.getManager().getJob(jobId);
								List<Map<String, Object>> partialResponseContent = PixelJobManager.getManager()
										.getStreamOut(jobId);
								PixelJobStatus jobStatus = jt == null ? PixelJobStatus.UNKNOWN_JOB
										: jt.getPixelJobStatus();

								if (partialResponseContent != null && !partialResponseContent.isEmpty()) {
									for (Map<String, Object> streamObj : partialResponseContent) {
//										classLogger.info("Stream chunk received: {}",
//												GSON.toJson(streamObj));

										String streamType = (String) streamObj.get("stream_type");
										Map<String, Object> streamData = (Map<String, Object>) streamObj.get("data");

										// Media (image) chunks. The Responses API protocol expects:
										// output_item.added (status=in_progress) - once
										// image_generation_call.partial_image - zero or more, all under
										// the same item_id, each carrying partial_image_b64 and an
										// incrementing partial_image_index
										// image_generation_call.completed - once (bare lifecycle event)
										// output_item.done (status=completed, item.result=<base64>) - once
										// We open the item lazily on the first media chunk and close it on
										// the final (partial_image_index == null), so the openai SDK sees
										// one image item from added -> done.
										if ("media".equalsIgnoreCase(streamType)) {
											if (currentItemId != null) {
												if ("message".equals(currentItemType) && isContentPartOpen) {
													OpenAIResponsesHelper.sendTextDone(writer, seq++, responseId,
															currentItemId, outputIndex, contentIndex,
															currentAccumulator.toString());
													OpenAIResponsesHelper.sendContentPartDone(writer, seq++, responseId,
															currentItemId, outputIndex, contentIndex,
															currentAccumulator.toString());
													isContentPartOpen = false;
												}
												OpenAIResponsesHelper.sendItemDone(writer, seq++, responseId,
														currentItemId, outputIndex, currentItemType,
														currentAccumulator.toString(), currentToolName);
												outputIndex++;
												currentAccumulator.setLength(0);
												currentItemId = null;
												currentItemType = null;
												currentToolName = null;
											}

											Map<String, Object> mediaInfo = (Map<String, Object>) streamData
													.get("media_info");
											Object partialIdx = streamData.get("partial_image_index");

											if (imgItemId == null) {
												imgItemId = "img_" + GUID.v7().toUUID().toString();
												Map<String, Object> imgItem = new HashMap<>();
												imgItem.put("id", imgItemId);
												imgItem.put("type", "image_generation_call");
												imgItem.put("status", "in_progress");
												Map<String, Object> addedEvent = new HashMap<>();
												addedEvent.put("type", "response.output_item.added");
												addedEvent.put("sequence_number", seq++);
												addedEvent.put("response_id", responseId);
												addedEvent.put("output_index", outputIndex);
												addedEvent.put("item", imgItem);
												OpenAIResponsesHelper.writeSSEEvent(addedEvent, writer);
											}

											if (partialIdx != null) {
												OpenAIResponsesHelper.sendImageGenerationPartialImage(writer, seq++,
														responseId, imgItemId, outputIndex, mediaInfo, partialIdx);
											} else {
												OpenAIResponsesHelper.sendImageGenerationCompleted(writer, seq++,
														responseId, imgItemId, outputIndex);

												Map<String, Object> doneItem = new HashMap<>();
												doneItem.put("id", imgItemId);
												doneItem.put("type", "image_generation_call");
												doneItem.put("status", "completed");
												if (mediaInfo != null) {
													Object b64 = mediaInfo.get("base64Data");
													if (b64 instanceof String && !((String) b64).isEmpty()) {
														doneItem.put("result", b64);
													}
												}
												Map<String, Object> doneEvent = new HashMap<>();
												doneEvent.put("type", "response.output_item.done");
												doneEvent.put("sequence_number", seq++);
												doneEvent.put("response_id", responseId);
												doneEvent.put("output_index", outputIndex);
												doneEvent.put("item", doneItem);
												OpenAIResponsesHelper.writeSSEEvent(doneEvent, writer);

												outputIndex++;
												imgItemId = null;
											}

											if (streamData.containsKey("finish_reason")) {
												break STREAM_LOOP;
											}
											continue;
										}

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
												capturedCachedTokens = ((Number) crT).intValue();
											}
											Object rT = streamData.get("reasoning_tokens");
											if (rT instanceof Number) {
												capturedReasoningTokens = ((Number) rT).intValue();
											}
											continue;
										}

										boolean isChunkTool = "tool".equalsIgnoreCase(streamType)
												|| "function_call".equalsIgnoreCase(streamType);
										String targetType = isChunkTool ? "function_call" : "message";

										String incomingId = (String) streamData.get("id");

										// --- TRANSITION LOGIC ---
										// Rule 1: Nothing started yett
										// Rule 2: Type switched (Thnking --->> Tool)
										// Rule 3: A NEW Tool ID appeared (Tool A -> Tool B)
										boolean shouldSwitch = (currentItemId == null)
												|| (!targetType.equals(currentItemType)) || (isChunkTool
														&& incomingId != null && !incomingId.equals(currentItemId));

										if (shouldSwitch) {
											// A. close previous item if it exists
											if (currentItemId != null) {
												if ("message".equals(currentItemType) && isContentPartOpen) {
													OpenAIResponsesHelper.sendTextDone(writer, seq++, responseId,
															currentItemId, outputIndex, contentIndex,
															currentAccumulator.toString());
													OpenAIResponsesHelper.sendContentPartDone(writer, seq++, responseId,
															currentItemId, outputIndex, contentIndex,
															currentAccumulator.toString());
													isContentPartOpen = false;
												}
												OpenAIResponsesHelper.sendItemDone(writer, seq++, responseId,
														currentItemId, outputIndex, currentItemType,
														currentAccumulator.toString(), currentToolName);

												outputIndex++;
												currentAccumulator.setLength(0);
											}

											// B. Setup NEW Item
											currentItemType = targetType;
											currentItemId = (incomingId != null) ? incomingId
													: "msg_" + GUID.v7().toUUID().toString();

											if (isChunkTool) {
												currentToolName = (String) streamData.get("name");

												if (currentToolName == null) {
													Map<String, Object> functionObj = (Map<String, Object>) streamData
															.get("function");
													if (functionObj != null) {
														currentToolName = (String) functionObj.get("name");
													}
												}

												if (currentToolName == null) {
													currentToolName = "shell";
												}
											}

											// C. Send ADDED event
											OpenAIResponsesHelper.sendItemAdded(writer, seq++, responseId,
													currentItemId, outputIndex, currentItemType, currentToolName);

											if ("message".equals(currentItemType)) {
												OpenAIResponsesHelper.sendContentPartAdded(writer, seq++, responseId,
														currentItemId, outputIndex, contentIndex);
												isContentPartOpen = true;
											}
										}

										// --- DELTA LOGIC: stream the actual data ---
										if ("message".equals(currentItemType)) {
											String content = (String) streamData.get("content");
											if (content != null && !content.isEmpty()) {
												currentAccumulator.append(content);
												OpenAIResponsesHelper.sendTextDelta(writer, seq++, responseId,
														currentItemId, outputIndex, contentIndex, content);
											}
										} else {
											Object argsObj = streamData.get("arguments");

											if (argsObj == null) {
												Map<String, Object> functionObj = (Map<String, Object>) streamData
														.get("function");
												if (functionObj != null) {
													argsObj = functionObj.get("arguments");

													if (currentToolName == null || "shell".equals(currentToolName)) {
														String nestedName = (String) functionObj.get("name");
														if (nestedName != null) {
															currentToolName = nestedName;
														}
													}
												}
											}

											if (argsObj == null) {
												argsObj = streamData.get("content");
											}

											if (argsObj != null) {
												String argsString = (argsObj instanceof String) ? (String) argsObj
														: GSON.toJson(argsObj);
												if (!argsString.isEmpty()) {
													currentAccumulator.append(argsString);
													OpenAIResponsesHelper.sendToolDelta(writer, seq++, responseId,
															currentItemId, outputIndex, argsString);
												}
											}
										}

										if (streamData.containsKey("finish_reason")) {
											break STREAM_LOOP;
										}
									}
								}

								if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE) {
									break STREAM_LOOP;
								}

								try {
									Thread.sleep(50);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
									break;
								}
							}

							if (currentItemId != null) {
								if ("message".equals(currentItemType) && isContentPartOpen) {
									OpenAIResponsesHelper.sendTextDone(writer, seq++, responseId, currentItemId,
											outputIndex, contentIndex, currentAccumulator.toString());
									OpenAIResponsesHelper.sendContentPartDone(writer, seq++, responseId, currentItemId,
											outputIndex, contentIndex, currentAccumulator.toString());
								}
								OpenAIResponsesHelper.sendItemDone(writer, seq++, responseId, currentItemId,
										outputIndex, currentItemType, currentAccumulator.toString(), currentToolName);
							}

							Map<String, Object> completedEvent = OpenAIResponsesHelper.createBaseEvent(
									"response.completed", seq++, responseId, FINAL_ENGINE_ID, creationTimestamp);
							completedEvent.put("status", "completed");
							OpenAIResponsesHelper.attachUsage(completedEvent, capturedInputTokens, capturedOutputTokens,
									capturedCachedTokens, capturedReasoningTokens);
							OpenAIResponsesHelper.writeSSEEvent(completedEvent, writer);

						} catch (IOException ioe) {
							final String capturedJobId = jobId;
							if (!WebUtility.handleStreamingException(ioe, classLogger, FINAL_ENGINE_ID, capturedJobId,
									() -> PixelJobManager.getManager().interruptThread(capturedJobId))) {
								classLogger.error("I/O error processing responses streaming for engine '{}'",
										FINAL_ENGINE_ID, ioe);
							}
						} catch (Exception e) {
							classLogger.error("Error processing responses streaming for engine '{}'", FINAL_ENGINE_ID,
									e);
						} finally {
							if (jobId != null) {
								PixelJobManager.getManager().clearJob(jobId);
								PixelJobManager.getManager().removeJob(jobId);
							}
						}
					}
				}).build();
	}

	@POST
	@Path("/v1/images/generations")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "text/event-stream" })
	public Response runV1ImagesGenerations(@Context HttpServletRequest request) {
		return runImagesGenerations(request);
	}

	@POST
	@Path("/images/generations")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "text/event-stream" })
	public Response runImagesGenerationsAlias(@Context HttpServletRequest request) {
		return runImagesGenerations(request);
	}

	private Response runImagesGenerations(@Context HttpServletRequest request) {
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

		StringBuilder requestData = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				requestData.append(line);
			}
		} catch (IOException e) {
			classLogger.error("Failed to read images/generations request body: {}", e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Bad Request: failed to read request body.");
		}

		Map<String, Object> dataMap;
		try {
			dataMap = GSON.fromJson(WebUtility.jsonSanitizer(requestData.toString()),
					new TypeToken<Map<String, Object>>() {
					}.getType());
		} catch (Exception e) {
			return ModelPixelExecutor.errorResponse(400, "Error processing JSON: " + e.getMessage());
		}

		boolean isStreamingRequest = Boolean.parseBoolean(dataMap.getOrDefault("stream", false).toString());
		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400, "Missing required field 'model'.");
		}

		String prompt = WebUtility.inputSanitizer((String) dataMap.remove("prompt"));
		if (prompt == null || prompt.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400, "Missing required field 'prompt'.");
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return ModelPixelExecutor.errorResponse(403,
					"Model " + engineId + " does not exist or user does not have access.");
		}

		IModelEngine engine = Utility.getModel(engineId);

		String insightId = WebUtility.inputSanitizer((String) dataMap.remove("insight_id"));
		if (insightId == null) {
			Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(SESSION_ID);
			if (sessionInsights == null || sessionInsights.isEmpty()) {
				insight = new Insight();
				InsightStore.getInstance().put(insight);
				insightId = insight.getInsightId();
				InsightStore.getInstance().addToSessionHash(SESSION_ID, insightId);
			} else {
				insightId = sessionInsights.iterator().next();
				insight = InsightStore.getInstance().get(insightId);
			}
		} else {
			insight = InsightStore.getInstance().get(insightId);
			InsightStore.getInstance().addToSessionHash(SESSION_ID, insightId);
		}

		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find the Insight with an Insight ID of " + insightId);
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
		insight.setUser(user);

		String roomId = WebUtility.inputSanitizer((String) dataMap.remove("room_id"));
		if (roomId == null) {
			roomId = (String) request.getAttribute("roomId");
		}
		room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);

		ModelPixelExecutor.initializeThreadStore(insight, SESSION_ID, JOB_ID);

		List<Map<String, Object>> messages = new ArrayList<>();
		Map<String, Object> userMsg = new HashMap<>();
		userMsg.put("role", "user");
		userMsg.put("content", prompt);
		messages.add(userMsg);
		dataMap.put(AbstractModelEngine.FULL_PROMPT, messages);

		final String OUTPUT_FORMAT = (String) dataMap.get("output_format");
		final String QUALITY = (String) dataMap.get("quality");
		final String SIZE = (String) dataMap.get("size");

		if (!isStreamingRequest) {
			try {
				AskModelEngineResponse<?> llmResponse = ModelPixelExecutor.askModelSync(engine, insight, room, dataMap);
				long createdAt = Instant.now().getEpochSecond();
				Map<String, Object> responseMap = OpenAIImagesHelper.buildNonStreamingResponse(createdAt, llmResponse);
				return WebUtility.getResponse(responseMap, 200);
			} catch (Exception e) {
				classLogger.error("Images synchronous model call failed for engine '{}'", engineId, e);
				return ModelPixelExecutor.errorResponse(400, e.getMessage());
			}
		} else {
			return handleImagesStreamingResponse(engine, insight, room, dataMap, SESSION_ID, JOB_ID, engineId,
					OUTPUT_FORMAT, QUALITY, SIZE);
		}
	}

	private Response handleImagesStreamingResponse(IModelEngine engine, final Insight FINAL_INSIGHT,
			final Room FINAL_ROOM, final Map<String, Object> FINAL_DATA_MAP, final String FINAL_SESSION_ID,
			final String FINAL_JOB_ID, final String FINAL_ENGINE_ID, final String FINAL_OUTPUT_FORMAT,
			final String FINAL_QUALITY, final String FINAL_SIZE) {
		classLogger.info("Starting images/generations streaming for engine: {}", FINAL_ENGINE_ID);

		return Response.ok().header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive").header("X-Content-Type-Options", "nosniff")
				.entity(new StreamingOutput() {
					@Override
					public void write(OutputStream output) throws IOException, WebApplicationException {
						long creationTimestamp = Instant.now().getEpochSecond();
						String jobId = null;

						try (Writer writer = new BufferedWriter(
								new OutputStreamWriter(output, StandardCharsets.UTF_8))) {

							jobId = ModelPixelExecutor.startAsyncModelRequest(engine, FINAL_INSIGHT, FINAL_ROOM,
									FINAL_DATA_MAP, FINAL_SESSION_ID);

							STREAM_LOOP: while (true) {
								PixelJobRunner jt = PixelJobManager.getManager().getJob(jobId);
								List<Map<String, Object>> partialResponseContent = PixelJobManager.getManager()
										.getStreamOut(jobId);
								PixelJobStatus jobStatus = jt == null ? PixelJobStatus.UNKNOWN_JOB
										: jt.getPixelJobStatus();

								if (partialResponseContent != null && !partialResponseContent.isEmpty()) {
									for (Map<String, Object> streamObj : partialResponseContent) {
										String streamType = (String) streamObj.get("stream_type");
										@SuppressWarnings("unchecked")
										Map<String, Object> streamData = (Map<String, Object>) streamObj.get("data");
										if (streamData == null) {
											continue;
										}

										if ("media".equalsIgnoreCase(streamType)) {
											@SuppressWarnings("unchecked")
											Map<String, Object> mediaInfo = (Map<String, Object>) streamData
													.get("media_info");
											Object partialIdxObj = streamData.get("partial_image_index");

											if (mediaInfo == null) {
												if (streamData.containsKey("finish_reason")) {
													break STREAM_LOOP;
												}
												continue;
											}

											Object b64Obj = mediaInfo.get("base64Data");
											String b64 = (b64Obj instanceof String) ? (String) b64Obj : null;
											if (b64 == null || b64.isEmpty()) {
												continue;
											}

											if (partialIdxObj != null) {
												int partialIdx = ((Number) partialIdxObj).intValue();
												OpenAIImagesHelper.writePartialImageEvent(writer, b64, partialIdx,
														FINAL_ENGINE_ID, creationTimestamp, FINAL_OUTPUT_FORMAT,
														FINAL_QUALITY, FINAL_SIZE);
											} else {
												OpenAIImagesHelper.writeCompletedEvent(writer, b64, FINAL_ENGINE_ID,
														creationTimestamp, FINAL_OUTPUT_FORMAT, FINAL_QUALITY,
														FINAL_SIZE, null, null);
												writer.write("data: [DONE]\n\n");
												writer.flush();
												break STREAM_LOOP;
											}
										}
									}
								}

								if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE) {
									break STREAM_LOOP;
								}

								try {
									Thread.sleep(50);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
									break;
								}
							}

						} catch (IOException ioe) {
							final String capturedJobId = jobId;
							if (!WebUtility.handleStreamingException(ioe, classLogger, FINAL_ENGINE_ID, capturedJobId,
									() -> PixelJobManager.getManager().interruptThread(capturedJobId))) {
								classLogger.error("I/O error processing images/generations streaming for engine '{}'",
										FINAL_ENGINE_ID, ioe);
							}
						} catch (Exception e) {
							classLogger.error("Error processing images/generations streaming for engine '{}'",
									FINAL_ENGINE_ID, e);
						} finally {
							if (jobId != null) {
								PixelJobManager.getManager().clearJob(jobId);
								PixelJobManager.getManager().removeJob(jobId);
							}
						}
					}
				}).build();
	}

	// TODO: move paylaod generation logic into a new OpenAICompletionsHelper

	@POST
	@Path("/completions")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "text/event-stream" })
	public Response runModelCompletion(@Context HttpServletRequest request) {
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

		// Retrieve raw data from the request
		StringBuilder requestData = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
			String line;

			while ((line = reader.readLine()) != null) {
				requestData.append(line);
			}
		} catch (IOException e) {
			classLogger.error("Failed to read completions request body for path '{}': {}", request.getRequestURI(),
					e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Bad Request: The 'data' parameter is missing.");
		}

		// Convert the JSON string to a Map
		Map<String, Object> dataMap;
		try {
			dataMap = GSON.fromJson(WebUtility.jsonSanitizer(requestData.toString()),
					new TypeToken<Map<String, Object>>() {
					}.getType());
		} catch (Exception e) {
			classLogger.error("Failed to parse completions request JSON for path '{}': {}", request.getRequestURI(),
					e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Error processing JSON data: " + e.getMessage());
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400,
					"Bad Request: The 'data' parameter is missing the required 'model' field.");
		}
		IModelEngine engine = Utility.getModel(engineId);

		String question = (String) dataMap.remove("prompt");
		if (question == null) {
			return ModelPixelExecutor.errorResponse(400, "Please provide 'prompt'.");
		}

		boolean isStreamingRequest = false;
		if (dataMap.containsKey("stream")) {
			isStreamingRequest = Boolean.parseBoolean(dataMap.get("stream").toString());
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return ModelPixelExecutor.errorResponse(403,
					"Model " + engineId + " does not exist or user does not have access to this model");
		}

		String insightId = WebUtility.inputSanitizer((String) dataMap.remove("insight_id"));
		if (insightId == null) {
			Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(SESSION_ID);
			if (sessionInsights == null || sessionInsights.isEmpty()) {
				// need to make a new insight here
				insight = new Insight();
				InsightStore.getInstance().put(insight);
				insightId = insight.getInsightId();
				InsightStore.getInstance().addToSessionHash(SESSION_ID, insightId);
			} else {
				// pull the insight id from the session set
				insightId = sessionInsights.iterator().next();
				insight = InsightStore.getInstance().get(insightId);
			}
		} else {
			insight = InsightStore.getInstance().get(insightId);
			// maybe its an insight id from another session
			InsightStore.getInstance().addToSessionHash(SESSION_ID, insightId);
		}

		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find the Insight with an Insight ID of " + insightId);
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
		// set the user
		insight.setUser(user);

		String roomId = WebUtility.inputSanitizer((String) dataMap.remove("room_id"));
		// room name gets updated during parsing of full prompt
		room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);

		ModelPixelExecutor.initializeThreadStore(insight, SESSION_ID, JOB_ID);

		// route through the LLM pixel by carrying the prompt as a full_prompt message
		List<Map<String, Object>> completionMessages = new ArrayList<>();
		Map<String, Object> completionUserMessage = new HashMap<>();
		completionUserMessage.put("role", "user");
		completionUserMessage.put("content", question);
		completionMessages.add(completionUserMessage);
		dataMap.put(AbstractModelEngine.FULL_PROMPT, completionMessages);

		if (!isStreamingRequest) {
			AskModelEngineResponse llmResponse;
			try {
				llmResponse = ModelPixelExecutor.askModelSync(engine, insight, room, dataMap);
			} catch (Exception e) {
				classLogger.error("Model completion synchronous call failed for engine '{}'", engineId, e);
				return ModelPixelExecutor.errorResponse(400, e.getMessage());
			}

			String response = llmResponse.getStringResponse();
			String messageId = llmResponse.getMessageId();
			Integer promptTokens = llmResponse.getNumberOfTokensInPrompt();
			Integer responseTokens = llmResponse.getNumberOfTokensInResponse();
			Integer cacheReadTokens = llmResponse.getNumberOfCacheReadTokens();
			Integer thinkingTokens = llmResponse.getNumberOfThinkingTokens();

			// Get the current UTC time
			ZonedDateTime currentDateTime = Utility.getCurrentZonedDateTimeForUser(user);
			// Convert ZonedDateTime to Instant
			Instant instant = currentDateTime.toInstant();
			// Get the number of seconds since the epoch
			long unixTimestamp = instant.getEpochSecond();

			Map<String, Object> llmResponseMap = new HashMap<>();
			llmResponseMap.put("id", messageId);
			llmResponseMap.put("object", "text_completion");
			llmResponseMap.put("created", unixTimestamp);
			llmResponseMap.put("model", engineId);

			// "choices" array
			List<Map<String, Object>> choicesList = new ArrayList<>();
			Map<String, Object> choice = new HashMap<>();
			choice.put("finish_reason", "length");
			choice.put("index", 0);
			choice.put("logprobs", null);
			choice.put("text", response);

			choicesList.add(choice);
			llmResponseMap.put("choices", choicesList);

			// "usage" object
			Map<String, Object> usage = new HashMap<>();
			if (promptTokens != null) {
				usage.put("prompt_tokens", promptTokens);
			}
			if (responseTokens != null) {
				usage.put("completion_tokens", responseTokens);
			}
			if (promptTokens != null || responseTokens != null) {
				int promptPart = promptTokens != null ? promptTokens : 0;
				int completionPart = responseTokens != null ? responseTokens : 0;
				usage.put("total_tokens", promptPart + completionPart);
			}
			if (cacheReadTokens != null) {
				Map<String, Object> promptTokensDetails = new HashMap<>();
				promptTokensDetails.put("cached_tokens", cacheReadTokens);
				usage.put("prompt_tokens_details", promptTokensDetails);
			}
			if (thinkingTokens != null) {
				Map<String, Object> completionTokensDetails = new HashMap<>();
				completionTokensDetails.put("reasoning_tokens", thinkingTokens);
				usage.put("completion_tokens_details", completionTokensDetails);
			}
			llmResponseMap.put("usage", usage);

			return WebUtility.getResponse(llmResponseMap, 200);
		} else {
			// fake streaming implementation!!
			final String messageId = "chatcmpl-" + GUID.v7().toUUID().toString();
			final long creationTimestamp = Instant.now().getEpochSecond();

			classLogger.info("Starting fake streaming response for model: {}", engineId);

			final Room FINAL_ROOM = room;
			return Response.ok().header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
					.header("Connection", "keep-alive").entity((StreamingOutput) output -> {

						try (Writer writer = new BufferedWriter(
								new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
							// Get full completion from the model in one go through the LLM pixel
							AskModelEngineResponse llmResponse = ModelPixelExecutor.askModelSync(engine, FINAL_INSIGHT,
									FINAL_ROOM, dataMap);
							String completionText = llmResponse.getStringResponse();
							Integer streamPromptTokens = llmResponse.getNumberOfTokensInPrompt();
							Integer streamResponseTokens = llmResponse.getNumberOfTokensInResponse();
							Integer streamCacheReadTokens = llmResponse.getNumberOfCacheReadTokens();
							Integer streamThinkingTokens = llmResponse.getNumberOfThinkingTokens();

							// First (and only) SSE chunk
							Map<String, Object> chunk = new HashMap<>();
							chunk.put("id", messageId);
							chunk.put("object", "text_completion");
							chunk.put("created", creationTimestamp);
							chunk.put("model", engineId);

							List<Map<String, Object>> choices = new ArrayList<>();
							Map<String, Object> choice = new HashMap<>();
							choice.put("index", 0);
							choice.put("text", completionText);
							choice.put("logprobs", null);
							choice.put("finish_reason", "stop");
							choices.add(choice);
							chunk.put("choices", choices);

							Map<String, Object> streamUsage = new HashMap<>();
							if (streamPromptTokens != null) {
								streamUsage.put("prompt_tokens", streamPromptTokens);
							}
							if (streamResponseTokens != null) {
								streamUsage.put("completion_tokens", streamResponseTokens);
							}
							if (streamPromptTokens != null || streamResponseTokens != null) {
								int promptPart = streamPromptTokens != null ? streamPromptTokens : 0;
								int completionPart = streamResponseTokens != null ? streamResponseTokens : 0;
								streamUsage.put("total_tokens", promptPart + completionPart);
							}
							if (streamCacheReadTokens != null) {
								Map<String, Object> promptTokensDetails = new HashMap<>();
								promptTokensDetails.put("cached_tokens", streamCacheReadTokens);
								streamUsage.put("prompt_tokens_details", promptTokensDetails);
							}
							if (streamThinkingTokens != null) {
								Map<String, Object> completionTokensDetails = new HashMap<>();
								completionTokensDetails.put("reasoning_tokens", streamThinkingTokens);
								streamUsage.put("completion_tokens_details", completionTokensDetails);
							}
							chunk.put("usage", streamUsage);

							writer.write("data: " + GSON.toJson(chunk) + "\n\n");
							writer.write("data: [DONE]\n\n");
							writer.flush();

						} catch (IOException ioe) {
							if (!WebUtility.handleStreamingException(ioe, classLogger, engineId, null, null)) {
								classLogger.error("Fake streaming completion response failed for engine '{}': {}",
										engineId, ioe.getMessage(), ioe);
								throw new WebApplicationException(ioe, 500);
							}
						} catch (Exception e) {
							classLogger.error("Fake streaming completion response failed for engine '{}': {}", engineId,
									e.getMessage(), e);
							throw new WebApplicationException(e, 500);
						}
					}).build();
		}
	}

	@POST
	@Path("/embeddings")
	@Consumes({ "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response runModelEmbeddings(@Context HttpServletRequest request) {
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

		// Retrieve raw data from the request
		StringBuilder requestData = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				requestData.append(line);
			}
		} catch (IOException e) {
			classLogger.error("Failed to read embeddings request body for path '{}': {}", request.getRequestURI(),
					e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Bad Request: The 'data' parameter is missing.");
		}

		// Convert the JSON string to a Map
		Map<String, Object> dataMap;
		try {
			dataMap = GSON.fromJson(WebUtility.jsonSanitizer(requestData.toString()),
					new TypeToken<Map<String, Object>>() {
					}.getType());
		} catch (Exception e) {
			classLogger.error("Failed to parse embeddings request JSON for path '{}': {}", request.getRequestURI(),
					e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Error processing JSON data: " + e.getMessage());
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400,
					"Bad Request: The 'data' parameter is missing the required 'model' field.");
		}

		List<String> stringsToEncode = (List<String>) dataMap.remove("input");
		if (stringsToEncode == null || stringsToEncode.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400,
					"Bad Request: The 'data' parameter is missing the required 'input' field.");
		}

		// make sure the user can view the engine
		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return ModelPixelExecutor.errorResponse(403,
					"Model " + engineId + " does not exist or user does not have access to this model");
		}

		String insightId = WebUtility.inputSanitizer((String) dataMap.remove("insight_id"));
		if (insightId == null) {
			Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(SESSION_ID);
			if (sessionInsights == null || sessionInsights.isEmpty()) {
				// need to make a new insight here
				insight = new Insight();
				InsightStore.getInstance().put(insight);
				insightId = insight.getInsightId();
				InsightStore.getInstance().addToSessionHash(SESSION_ID, insightId);
			} else {
				// pull the insight id from the session set
				insightId = sessionInsights.iterator().next();
				insight = InsightStore.getInstance().get(insightId);
			}
		} else {
			insight = InsightStore.getInstance().get(insightId);
			// maybe its an insight id from another session
			InsightStore.getInstance().addToSessionHash(SESSION_ID, insightId);
		}

		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find the Insight with an Insight ID of " + insightId);
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}

		ModelPixelExecutor.initializeThreadStore(insight, SESSION_ID, JOB_ID);

		// set the user
		insight.setUser(user);

		IModelEngine engine = Utility.getModel(engineId);
		EmbeddingsModelEngineResponse embeddingsResponse;
		try {
			embeddingsResponse = engine.embeddings(stringsToEncode, insight, dataMap);
		} catch (Exception e) {
			classLogger.error("Embeddings call failed for engine '{}'", engineId, e);
			return ModelPixelExecutor.errorResponse(400, e.getMessage());
		}

		List<List<Double>> embeddings = embeddingsResponse.getResponse();
		Integer embeddingsPromptTokens = embeddingsResponse.getNumberOfTokensInPrompt();
		Integer embeddingsResponseTokens = embeddingsResponse.getNumberOfTokensInResponse();
		Integer embeddingsCacheReadTokens = embeddingsResponse.getNumberOfCacheReadTokens();

		Map<String, Object> embeddingsResponseMap = new HashMap<>();

		// "choices" array
		List<Map<String, Object>> dataList = new ArrayList<>();
		for (int i = 0; i < embeddings.size(); i++) {
			Map<String, Object> embeddingMap = new HashMap<>();
			embeddingMap.put("embedding", embeddings.get(i));
			embeddingMap.put("index", i);
			embeddingMap.put("object", "embedding");

			dataList.add(embeddingMap);
		}

		embeddingsResponseMap.put("data", dataList);
		embeddingsResponseMap.put("model", engineId);
		embeddingsResponseMap.put("object", "list");

		// "usage" object
		Map<String, Object> embeddingsUsage = new HashMap<>();
		if (embeddingsPromptTokens != null) {
			embeddingsUsage.put("prompt_tokens", embeddingsPromptTokens);
		}
		if (embeddingsResponseTokens != null) {
			embeddingsUsage.put("completion_tokens", embeddingsResponseTokens);
		}
		if (embeddingsPromptTokens != null || embeddingsResponseTokens != null) {
			int promptPart = embeddingsPromptTokens != null ? embeddingsPromptTokens : 0;
			int completionPart = embeddingsResponseTokens != null ? embeddingsResponseTokens : 0;
			embeddingsUsage.put("total_tokens", promptPart + completionPart);
		}
		if (embeddingsCacheReadTokens != null) {
			Map<String, Object> promptTokensDetails = new HashMap<>();
			promptTokensDetails.put("cached_tokens", embeddingsCacheReadTokens);
			embeddingsUsage.put("prompt_tokens_details", promptTokensDetails);
		}
		embeddingsResponseMap.put("usage", embeddingsUsage);
		return WebUtility.getResponse(embeddingsResponseMap, 200);
	}

	@GET
	@Path("/v1/models")
	@Consumes({ "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response listV1Models(@Context HttpServletRequest request) {
		return listModels(request);
	}

	@GET
	@Path("/models")
	@Consumes({ "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response listModels(@Context HttpServletRequest request) {
		// https://platform.openai.com/docs/api-reference/models/list
		HttpSession session = request.getSession(false);
		User user = ModelPixelExecutor.getSessionUser(session);
		if (user == null) {
			return ModelPixelExecutor.invalidSessionResponse(request, session);
		}

		MyEnginesReactor reactor = new MyEnginesReactor();
		reactor.In();
		Insight temp = new Insight();
		temp.setUser(user);
		reactor.setInsight(temp);
		{
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(IEngine.CATALOG_TYPE.MODEL.name(), PixelDataType.CONST_STRING));
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE_TYPE.getKey(), struct);
		}
		{
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(true, PixelDataType.BOOLEAN));
			reactor.getNounStore().addNoun(ReactorKeysEnum.NO_META.getKey(), struct);
		}
		{
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(false, PixelDataType.BOOLEAN));
			reactor.getNounStore().addNoun(ReactorKeysEnum.INCLUDE_USERTRACKING_KEY.getKey(), struct);
		}

		NounMetadata outputNoun = reactor.execute();
		List<Map<String, Object>> openAiResponse = processModelList(outputNoun);
		Map<String, Object> returnObject = new HashMap<>();
		returnObject.put("object", "list");
		returnObject.put("data", openAiResponse);
		return WebUtility.getResponse(returnObject, 200);
	}

	@GET
	@Path("/v1/models/{modelId}")
	@Consumes({ "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response retrieveV1Model(@Context HttpServletRequest request, @PathParam("modelId") String modelId) {
		return retrieveModel(request, modelId);
	}

	@GET
	@Path("/models/{modelId}")
	@Consumes({ "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response retrieveModel(@Context HttpServletRequest request, @PathParam("modelId") String modelId) {
		// https://platform.openai.com/docs/api-reference/models/retrieve
		HttpSession session = request.getSession(false);
		User user = ModelPixelExecutor.getSessionUser(session);
		if (user == null) {
			return ModelPixelExecutor.invalidSessionResponse(request, session);
		}

		MyEnginesReactor reactor = new MyEnginesReactor();
		reactor.In();
		Insight temp = new Insight();
		temp.setUser(user);
		reactor.setInsight(temp);
		{
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(modelId, PixelDataType.CONST_STRING));
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE.getKey(), struct);
		}
		{
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(IEngine.CATALOG_TYPE.MODEL.name(), PixelDataType.CONST_STRING));
			reactor.getNounStore().addNoun(ReactorKeysEnum.ENGINE_TYPE.getKey(), struct);
		}
		{
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(true, PixelDataType.BOOLEAN));
			reactor.getNounStore().addNoun(ReactorKeysEnum.NO_META.getKey(), struct);
		}
		{
			GenRowStruct struct = new GenRowStruct();
			struct.add(new NounMetadata(false, PixelDataType.BOOLEAN));
			reactor.getNounStore().addNoun(ReactorKeysEnum.INCLUDE_USERTRACKING_KEY.getKey(), struct);
		}

		NounMetadata outputNoun = reactor.execute();
		List<Map<String, Object>> openAiResponse = processModelList(outputNoun);
		if (openAiResponse == null || openAiResponse.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400, "Could not find model = '" + modelId + "'");
		}
		return WebUtility.getResponse(openAiResponse.get(0), 200);
	}

	/**
	 * Process the MyEngines output format to OpenAi format
	 * 
	 * @param outputNoun
	 * @return
	 */
	private List<Map<String, Object>> processModelList(NounMetadata outputNoun) {
		List<Map<String, Object>> enginesList = (List<Map<String, Object>>) outputNoun.getValue();
		// we will convert our object to the openai spec
		List<Map<String, Object>> openAiResponse = new ArrayList<>(enginesList.size());
		for (Map<String, Object> engines : enginesList) {
			Map<String, Object> newMap = new HashMap<>();
			newMap.put("object", "model");
			newMap.put("id", engines.get("database_id"));
			newMap.put("alias", engines.get("database_name"));
			newMap.put("owned_by", engines.get("database_created_by"));
			SemossDate dateCreated = (SemossDate) engines.get("database_date_created");
			if (dateCreated != null) {
				ZonedDateTime zdt = dateCreated.getZonedDateTime();
				if (zdt != null) {
					newMap.put("created", zdt.toEpochSecond());
				}
			}
			openAiResponse.add(newMap);
		}
		return openAiResponse;
	}

}
