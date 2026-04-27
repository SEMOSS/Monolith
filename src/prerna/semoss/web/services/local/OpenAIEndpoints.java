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
import java.time.ZoneId;
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.alt.GUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.date.SemossDate;
import prerna.engine.api.IEngine;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.AbstractModelEngine;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.model.message.InputMessage;
import prerna.engine.impl.model.message.ResponseMessage;
import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.responses.EmbeddingsModelEngineResponse;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
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
		User user = null;

		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		}
		// how did you even get past the no user in session filter?
		if (user == null) {
			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		final String SESSION_ID = session.getId();
		final String JOB_ID = GUID.v7().toUUID().toString();
		Insight insight = null;
		Room room = null;
		ObjectMapper objectMapper = new ObjectMapper();

		// set the user timezone
		ZoneId zoneId = null;
		String strTz = WebUtility.inputSanitizer(request.getParameter("tz"));
		if (strTz == null || (strTz = strTz.trim()).isEmpty()) {
			zoneId = ZoneId.of(Utility.getApplicationZoneId());
		} else {
			try {
				zoneId = ZoneId.of(strTz);
			} catch (Exception e) {
				classLogger.warn("Invalid timezone value '{}', falling back to application default", strTz, e);
				zoneId = ZoneId.of(Utility.getApplicationZoneId());
			}
		}
		// need null check if security is off
		if (user != null) {
			user.setZoneId(zoneId);
		}

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
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing.");
			return WebUtility.getResponse(errorMap, 400);
		}

		classLogger.info("Chat completion request data: {}", requestData);

		// Convert the JSON string to a Map
		TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {
		};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(WebUtility.jsonSanitizer(requestData.toString()), mapType);
		} catch (JsonProcessingException e) {
			classLogger.error("Failed to parse chat completions request JSON for path '{}': {}",
					request.getRequestURI(), e.getOriginalMessage(), e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error processing JSON data: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		boolean isStreamingRequest = false;
		if (dataMap.containsKey("stream")) {
			isStreamingRequest = Boolean.parseBoolean(dataMap.get("stream").toString());
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"Bad Request: The 'data' parameter is missing the required 'model' field.");
			return WebUtility.getResponse(errorMap, 400);
		}
		IModelEngine engine = Utility.getModel(engineId);

		Object fullPrompt = dataMap.remove("messages");
		if (fullPrompt == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Please provide 'messages'.");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"Model " + engineId + " does not exist or user does not have access to this model");
			return WebUtility.getResponse(errorMap, 403);
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

		ThreadStore.setInsightId(insight.getInsightId());
		ThreadStore.setSessionId(SESSION_ID);
		ThreadStore.setJobId(JOB_ID);
		ThreadStore.setUser(insight.getUser());

		final Insight finalInsight = insight;
		final Room finalRoom = room;

		dataMap.put(AbstractModelEngine.FULL_PROMPT, fullPrompt);
		dataMap.put(AbstractModelEngine.APPEND_FULL_PROMPT, appendFullPrompt);

		if (!isStreamingRequest) {
			AskModelEngineResponse llmResponse;
			try {
				InputMessage msg = InputMessage.builder(room).withModelType(engine.getModelType()).withParamMap(dataMap)
						.build();
				ResponseMessage response = room.ask(msg, engine);
				llmResponse = response.getModelEngineResponse();
			} catch (Exception e) {
				classLogger.error("Chat completions synchronous model call failed for engine '{}'", engineId, e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 400);
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
								jobId = startAsyncModelRequest(engine, finalInsight, finalRoom, dataMap, SESSION_ID);

								boolean started = false;

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
											if (streamType.equalsIgnoreCase("content")) {
												if (dataMap.containsKey("finish_reason")) {
													String finishReason = (String) dataMap.get("finish_reason");
													// this is a map only on finish reason
													OpenAIChatCompletionsHelper.writeFinishReason(engineId, messageId,
															creationTimestamp, finishReason, writer);
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
											} else if ("media".equalsIgnoreCase(streamType)) {
												// Image/media chunks from the Python image tier (partial + final).
												// OpenAI chat-completions has no native image delta, so this emits
												// a SEMOSS-proprietary delta.images[] payload.
												if (dataMap.containsKey("finish_reason")) {
													String finishReason = (String) dataMap.get("finish_reason");
													OpenAIChatCompletionsHelper.writeFinishReason(engineId, messageId,
															creationTimestamp, finishReason, writer);
													break STREAM_COMPLETE_LOOP;
												}
												OpenAIChatCompletionsHelper.writeMediaChunk(engineId, messageId,
														creationTimestamp, dataMap, started, writer);
												started = true;
											} else {
												// assuming only other type is tool at the moment
												if (dataMap.containsKey("finish_reason")) {
													// send the finish chunk
													String finishReason = (String) dataMap.get("finish_reason");
													OpenAIChatCompletionsHelper.writeFinishReason(engineId, messageId,
															creationTimestamp, finishReason, writer);
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
												creationTimestamp, "stop", writer);
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
													creationTimestamp, "tool_calls", writer);
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
													creationTimestamp, "stop", writer);
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
		User user = null;

		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		}

		if (user == null) {
			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		final String SESSION_ID = session.getId();
		final String JOB_ID = GUID.v7().toUUID().toString();
		Insight insight = null;
		Room room = null;
		ObjectMapper objectMapper = new ObjectMapper();

		ZoneId zoneId = null;
		String strTz = WebUtility.inputSanitizer(request.getParameter("tz"));
		if (strTz == null || (strTz = strTz.trim()).isEmpty()) {
			zoneId = ZoneId.of(Utility.getApplicationZoneId());
		} else {
			try {
				zoneId = ZoneId.of(strTz);
			} catch (Exception e) {
				classLogger.error(
						"Invalid timezone value '{}' for /responses request; falling back to application default '{}': {}",
						strTz, Utility.getApplicationZoneId(), e.getMessage(), e);
				zoneId = ZoneId.of(Utility.getApplicationZoneId());
			}
		}
		if (user != null) {
			user.setZoneId(zoneId);
		}

		StringBuilder requestData = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				requestData.append(line);
			}
		} catch (IOException e) {
			classLogger.error("Failed to read responses request body for path '{}': {}", request.getRequestURI(),
					e.getMessage(), e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: Data parameter missing.");
			return WebUtility.getResponse(errorMap, 400);
		}

		TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {
		};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(WebUtility.jsonSanitizer(requestData.toString()), mapType);
		} catch (JsonProcessingException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error processing JSON: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		boolean isStreamingRequest = Boolean.parseBoolean(dataMap.getOrDefault("stream", false).toString());
		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));

		if (engineId == null || engineId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Missing 'model' field.");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Model " + engineId + " inaccessible.");
			return WebUtility.getResponse(errorMap, 403);
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
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Insight not found: " + insightId);
			return WebUtility.getResponse(errorMap, 400);
		}
		insight.setUser(user);

		// Room ID from JSON body, or from bearer token 3rd segment
		// (GitHubCopilotManager)
		String roomId = WebUtility.inputSanitizer((String) dataMap.remove("room_id"));
		if (roomId == null) {
			roomId = (String) request.getAttribute("roomId");
		}
		room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);

		ThreadStore.setInsightId(insight.getInsightId());
		ThreadStore.setSessionId(SESSION_ID);
		ThreadStore.setJobId(JOB_ID);
		ThreadStore.setUser(insight.getUser());

		dataMap.put(AbstractModelEngine.FULL_PROMPT, messages);

		if (!isStreamingRequest) {
			try {
				InputMessage msg = InputMessage.builder(room).withModelType(engine.getModelType()).withParamMap(dataMap)
						.build();
				ResponseMessage response = room.ask(msg, engine);
				AskModelEngineResponse llmResponse = response.getModelEngineResponse();

				Map<String, Object> processedResponse = OpenAIResponsesHelper.processAskModelEngineResponse(engineId,
						llmResponse);
				return WebUtility.getResponse(processedResponse, 200);
			} catch (Exception e) {
				classLogger.error("Responses synchronous model call failed for engine '{}'", engineId, e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}
		} else {
			return handleStreamingResponse(engine, insight, room, dataMap, SESSION_ID, JOB_ID, engineId);
		}
	}

	private Response handleStreamingResponse(IModelEngine engine, Insight finalInsight, Room finalRoom,
			Map<String, Object> dataMap, String SESSION_ID, String JOB_ID, String engineId) {
		classLogger.info("Starting responses streaming for engine: {}", engineId);

		return Response.ok().header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive").header("X-Content-Type-Options", "nosniff")
				.entity(new StreamingOutput() {
					@Override
					public void write(OutputStream output) throws IOException, WebApplicationException {
						String responseId = "resp_" + JOB_ID;
						long creationTimestamp = Instant.now().getEpochSecond();
						String jobId = null;
						int seq = 0;

						// --- STATE TRACKING ---
						// These variables persist across chunks
						String currentItemId = null;
						String currentItemType = null;
						String currentToolName = null;
						boolean isContentPartOpen = false;

						int outputIndex = 0;
						int contentIndex = 0;
						StringBuilder currentAccumulator = new StringBuilder();

						try (Writer writer = new BufferedWriter(
								new OutputStreamWriter(output, StandardCharsets.UTF_8))) {

							// 1. Initial Handshake Events
							OpenAIResponsesHelper.writeSSEEvent(OpenAIResponsesHelper.createBaseEvent(
									"response.created", seq++, responseId, engineId, creationTimestamp), writer);
							OpenAIResponsesHelper.writeSSEEvent(OpenAIResponsesHelper.createBaseEvent(
									"response.in_progress", seq++, responseId, engineId, creationTimestamp), writer);

							jobId = startAsyncModelRequest(engine, finalInsight, finalRoom, dataMap, SESSION_ID);

							// 2. MAIN POLLING LOOP
							STREAM_LOOP: while (true) {
								PixelJobRunner jt = PixelJobManager.getManager().getJob(jobId);
								List<Map<String, Object>> partialResponseContent = PixelJobManager.getManager()
										.getStreamOut(jobId);
								PixelJobStatus jobStatus = jt == null ? PixelJobStatus.UNKNOWN_JOB
										: jt.getPixelJobStatus();

								if (partialResponseContent != null && !partialResponseContent.isEmpty()) {
									for (Map<String, Object> streamObj : partialResponseContent) {
										classLogger.info("Stream chunk received: {}",
												new ObjectMapper().writeValueAsString(streamObj));

										String streamType = (String) streamObj.get("stream_type");
										Map<String, Object> streamData = (Map<String, Object>) streamObj.get("data");

										// Media (image) chunks: flush any in-progress text/tool item, then emit
										// a self-contained image_generation_call triplet (added → partial or
										// completed → done). Reset item state so the next non-media chunk
										// opens a fresh item.
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

											String imgItemId = "img_" + GUID.v7().toUUID().toString();
											Map<String, Object> mediaInfo = (Map<String, Object>) streamData
													.get("media_info");
											Object partialIdx = streamData.get("partial_image_index");

											Map<String, Object> imgItem = new HashMap<>();
											imgItem.put("id", imgItemId);
											imgItem.put("type", "image_generation_call");
											imgItem.put("status", partialIdx == null ? "completed" : "in_progress");
											Map<String, Object> addedEvent = new HashMap<>();
											addedEvent.put("type", "response.output_item.added");
											addedEvent.put("sequence_number", seq++);
											addedEvent.put("response_id", responseId);
											addedEvent.put("output_index", outputIndex);
											addedEvent.put("item", imgItem);
											OpenAIResponsesHelper.writeSSEEvent(addedEvent, writer);

											if (partialIdx != null) {
												OpenAIResponsesHelper.sendImageGenerationPartialImage(writer, seq++,
														responseId, imgItemId, outputIndex, mediaInfo, partialIdx);
											} else {
												OpenAIResponsesHelper.sendImageGenerationCompleted(writer, seq++,
														responseId, imgItemId, outputIndex, mediaInfo);
											}

											Map<String, Object> doneItem = new HashMap<>(imgItem);
											doneItem.put("status", "completed");
											Map<String, Object> doneEvent = new HashMap<>();
											doneEvent.put("type", "response.output_item.done");
											doneEvent.put("sequence_number", seq++);
											doneEvent.put("response_id", responseId);
											doneEvent.put("output_index", outputIndex);
											doneEvent.put("item", doneItem);
											OpenAIResponsesHelper.writeSSEEvent(doneEvent, writer);
											outputIndex++;

											if (streamData.containsKey("finish_reason")) {
												break STREAM_LOOP;
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
														: new ObjectMapper().writeValueAsString(argsObj);
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
									"response.completed", seq++, responseId, engineId, creationTimestamp);
							completedEvent.put("status", "completed");
							OpenAIResponsesHelper.writeSSEEvent(completedEvent, writer);

						} catch (Exception e) {
							classLogger.error("Error processing responses streaming for engine '{}'", engineId, e);
						} finally {
							if (jobId != null) {
								PixelJobManager.getManager().clearJob(jobId);
								PixelJobManager.getManager().removeJob(jobId);
							}
						}
					}
				}).build();
	}

	/**
	 * Start an asynchronous model request and return the job ID
	 * 
	 * @param engine
	 * @param insight
	 * @param dataMap
	 * @return
	 */
	private String startAsyncModelRequest(IModelEngine engine, Insight insight, Room room, Map<String, Object> dataMap,
			String sessionId) {
		try {
			// start async job
			PixelJobManager manager = PixelJobManager.getManager();
			PixelJobRunner jobRunner = manager.makeJob(insight, sessionId, null);
			String jobId = jobRunner.getJobId();

			String modelPixel = "LLM(engine='" + engine.getEngineId() + "',roomId='" + room.getId()
					+ "',command='<encode>ignore</encode>'"
					// this should have the full_prompt
					+ ",paramValues=[" + GSON.toJson(dataMap) + "]);";
			classLogger.info("Dispatching async model pixel: {}", modelPixel);
			jobRunner.addPixel(modelPixel);
			Thread.ofVirtual().start(jobRunner);
			return jobId;
		} catch (Exception e) {
			classLogger.error("Failed to start async model request for engine '{}': {}",
					engine == null ? "unknown" : engine.getEngineId(), e.getMessage(), e);
			throw new IllegalArgumentException(e.getMessage());
		}
	}

	// TODO: move paylaod generation logic into a new OpenAICompletionsHelper

	@POST
	@Path("/completions")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "text/event-stream" })
	public Response runModelCompletion(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		User user = null;

		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		}
		// how did you even get past the no user in session filter?
		if (user == null) {
			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		final String SESSION_ID = session.getId();
		final String JOB_ID = GUID.v7().toUUID().toString();
		Insight insight = null;
		Room room = null;
		ObjectMapper objectMapper = new ObjectMapper();

		// set the user timezone
		ZoneId zoneId = null;
		String strTz = request.getParameter("tz");
		if (strTz == null || (strTz = strTz.trim()).isEmpty()) {
			zoneId = ZoneId.of(Utility.getApplicationZoneId());
		} else {
			try {
				zoneId = ZoneId.of(strTz);
			} catch (Exception e) {
				classLogger.warn("Invalid timezone value '{}', falling back to application default", strTz, e);
				zoneId = ZoneId.of(Utility.getApplicationZoneId());
			}
		}
		// need null check if security is off
		if (user != null) {
			user.setZoneId(zoneId);
		}

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
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing.");
			return WebUtility.getResponse(errorMap, 400);
		}

		// Convert the JSON string to a Map
		TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {
		};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(WebUtility.jsonSanitizer(requestData.toString()), mapType);
		} catch (JsonProcessingException e) {
			classLogger.error("Failed to parse completions request JSON for path '{}': {}", request.getRequestURI(),
					e.getOriginalMessage(), e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error processing JSON data: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"Bad Request: The 'data' parameter is missing the required 'model' field.");
			return WebUtility.getResponse(errorMap, 400);
		}
		IModelEngine engine = Utility.getModel(engineId);

		String question = (String) dataMap.remove("prompt");
		if (question == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Please provide 'prompt'.");
			return WebUtility.getResponse(errorMap, 400);
		}

		boolean isStreamingRequest = false;
		if (dataMap.containsKey("stream")) {
			isStreamingRequest = Boolean.parseBoolean(dataMap.get("stream").toString());
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"Model " + engineId + " does not exist or user does not have access to this model");
			return WebUtility.getResponse(errorMap, 403);
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

		ThreadStore.setInsightId(insight.getInsightId());
		ThreadStore.setSessionId(SESSION_ID);
		ThreadStore.setJobId(JOB_ID);
		ThreadStore.setUser(insight.getUser());

		if (!isStreamingRequest) {
			AskModelEngineResponse llmResponse;
			try {
				InputMessage msg = InputMessage.builder(room).withModelType(engine.getModelType())
						.withText(question, question).withParamMap(dataMap).build();
				ResponseMessage response = room.ask(msg, engine);
				llmResponse = response.getModelEngineResponse();
			} catch (Exception e) {
				classLogger.error("Model completion synchronous call failed for engine '{}'", engineId, e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}

			String response = llmResponse.getStringResponse();
			String messageId = llmResponse.getMessageId();
			Integer promptTokens = llmResponse.getNumberOfTokensInPrompt();
			Integer responseTokens = llmResponse.getNumberOfTokensInResponse();

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

			return WebUtility.getResponse(llmResponseMap, 200);
		} else {
			// fake streaming implementation!!
			final String messageId = "chatcmpl-" + GUID.v7().toUUID().toString();
			final long creationTimestamp = Instant.now().getEpochSecond();

			classLogger.info("Starting fake streaming response for model: {}", engineId);

			final Insight FINAL_INSIGHT = insight;
			final Room FINAL_ROOM = room;
			return Response.ok().header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
					.header("Connection", "keep-alive").entity((StreamingOutput) output -> {
						ObjectMapper mapper = new ObjectMapper();
						try (Writer writer = new BufferedWriter(
								new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
							// Get full completion from your model in one go
							InputMessage msg = InputMessage.builder(FINAL_ROOM).withModelType(engine.getModelType())
									.withText(question, question).withParamMap(dataMap).build();
							AskModelEngineResponse llmResponse = engine.askRoom(question, FINAL_ROOM, msg, dataMap);
							String completionText = llmResponse.getStringResponse();
							Integer promptTokens = llmResponse.getNumberOfTokensInPrompt();
							Integer responseTokens = llmResponse.getNumberOfTokensInResponse();

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

							Map<String, Object> usage = new HashMap<>();
							if (promptTokens != null) {
								usage.put("prompt_tokens", promptTokens);
							}
							if (responseTokens != null) {
								usage.put("completion_tokens", responseTokens);
							}
							if (promptTokens != null && responseTokens != null) {
								usage.put("total_tokens", promptTokens + responseTokens);
							}
							chunk.put("usage", usage);

							writer.write("data: " + mapper.writeValueAsString(chunk) + "\n\n");
							writer.write("data: [DONE]\n\n");
							writer.flush();

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
		User user = null;

		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		}
		// how did you even get past the no user in session filter?
		if (user == null) {
			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		final String SESSION_ID = session.getId();
		final String JOB_ID = GUID.v7().toUUID().toString();
		Insight insight = null;
		ObjectMapper objectMapper = new ObjectMapper();

		// set the user timezone
		ZoneId zoneId = null;
		String strTz = request.getParameter("tz");
		if (strTz == null || (strTz = strTz.trim()).isEmpty()) {
			zoneId = ZoneId.of(Utility.getApplicationZoneId());
		} else {
			try {
				zoneId = ZoneId.of(strTz);
			} catch (Exception e) {
				classLogger.warn("Invalid timezone value '{}', falling back to application default", strTz, e);
				zoneId = ZoneId.of(Utility.getApplicationZoneId());
			}
		}
		// need null check if security is off
		if (user != null) {
			user.setZoneId(zoneId);
		}

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
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing.");
			return WebUtility.getResponse(errorMap, 400);
		}

		// Convert the JSON string to a Map
		TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {
		};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(WebUtility.jsonSanitizer(requestData.toString()), mapType);
		} catch (JsonProcessingException e) {
			classLogger.error("Failed to parse embeddings request JSON for path '{}': {}", request.getRequestURI(),
					e.getOriginalMessage(), e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error processing JSON data: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"Bad Request: The 'data' parameter is missing the required 'model' field.");
			return WebUtility.getResponse(errorMap, 400);
		}

		List<String> stringsToEncode = (List<String>) dataMap.remove("input");
		if (stringsToEncode == null || stringsToEncode.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"Bad Request: The 'data' parameter is missing the required 'input' field.");
			return WebUtility.getResponse(errorMap, 400);
		}

		// make sure the user can view the engine
		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE,
					"Model " + engineId + " does not exist or user does not have access to this model");
			return WebUtility.getResponse(errorMap, 403);
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

		ThreadStore.setInsightId(insight.getInsightId());
		ThreadStore.setSessionId(SESSION_ID);
		ThreadStore.setJobId(JOB_ID);
		ThreadStore.setUser(insight.getUser());

		// set the user
		insight.setUser(user);

		IModelEngine engine = Utility.getModel(engineId);
		EmbeddingsModelEngineResponse embeddingsResponse;
		try {
			embeddingsResponse = engine.embeddings(stringsToEncode, insight, dataMap);
		} catch (Exception e) {
			classLogger.error("Embeddings call failed for engine '{}'", engineId, e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		List<List<Double>> embeddings = embeddingsResponse.getResponse();
		Integer promptTokens = embeddingsResponse.getNumberOfTokensInPrompt();
		Integer responseTokens = embeddingsResponse.getNumberOfTokensInResponse();

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
		Map<String, Object> usage = new HashMap<>();

		if (promptTokens != null && responseTokens != null) {
			usage.put("prompt_tokens", promptTokens);
			usage.put("total_tokens", promptTokens + responseTokens);
		} else {
			usage.put("prompt_tokens", promptTokens);
		}

		embeddingsResponseMap.put("usage", usage);
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
		User user = null;
		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		}
		// how did you even get past the no user in session filter?
		if (user == null) {
			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
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
		User user = null;
		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		}
		// how did you even get past the no user in session filter?
		if (user == null) {
			if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
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
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find model = '" + modelId + "'");
			return WebUtility.getResponse(errorMap, 400);
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
