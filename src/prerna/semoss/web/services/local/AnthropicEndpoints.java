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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;

import javax.ws.rs.Path;
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
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.AbstractModelEngine;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.model.message.InputMessage;
import prerna.engine.impl.model.message.ResponseMessage;
import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.comm.PixelJobThread;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

/**
 * Anthropic Messages API compatible endpoints.
 * Allows connections from Claude Code and other Anthropic SDK clients.
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

	private static final String ERROR_TYPE = "errorType";
	private static final String INSIGHT_NOT_FOUND = "INSIGHT_NOT_FOUND";

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	

	/**
	 * Main Messages API endpoint - handles both streaming and non-streaming requests.
	 * Compatible with Anthropic's /v1/messages endpoint.
	 * 
	 * Request format:
	 * {
	 *   "model": "engine-id",
	 *   "max_tokens": 1024,
	 *   "messages": [{"role": "user", "content": "Hello"}],
	 *   "system": "optional system prompt",
	 *   "stream": false,
	 *   "tools": [...],
	 *   "temperature": 0.7
	 * }
	 */
	@POST
	@Path("/v1/messages")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "text/event-stream" })
	public Response createMessage(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		User user = null;

		if (session != null) {
			user = (User) session.getAttribute(Constants.SESSION_USER);
		}
		
		if (user == null) {
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse(
					"authentication_error", "User is not authenticated");
			return WebUtility.getResponse(errorMap, 401);
		}

		final String SESSION_ID = session.getId();
		final String JOB_ID = GUID.v7().toUUID().toString();
		Insight insight = null;
		Room room = null;
		ObjectMapper objectMapper = new ObjectMapper();

		// Set the user timezone
		ZoneId zoneId = null;
		String strTz = WebUtility.inputSanitizer(request.getParameter("tz"));
		if (strTz == null || (strTz = strTz.trim()).isEmpty()) {
			zoneId = ZoneId.systemDefault();
		} else {
			try {
				zoneId = ZoneId.of(strTz);
			} catch (Exception e) {
				classLogger.warn("Invalid timezone provided: " + strTz + ", using system default");
				zoneId = ZoneId.systemDefault();
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
			classLogger.error("Error reading request body", e);
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse(
					"invalid_request_error", "Failed to read request body");
			return WebUtility.getResponse(errorMap, 400);
		}

		classLogger.info("Anthropic Messages API request: " + requestData.toString());

		TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(requestData.toString(), mapType);
		} catch (JsonProcessingException e) {
			classLogger.error("Error parsing request JSON", e);
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse(
					"invalid_request_error", "Invalid JSON in request body");
			return WebUtility.getResponse(errorMap, 400);
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse(
					"invalid_request_error", "model is required");
			return WebUtility.getResponse(errorMap, 400);
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse(
					"permission_error", "User does not have access to model: " + engineId);
			return WebUtility.getResponse(errorMap, 403);
		}

		IModelEngine engine = Utility.getModel(engineId);
		if (engine == null) {
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse(
					"not_found_error", "Model not found: " + engineId);
			return WebUtility.getResponse(errorMap, 404);
		}

		Object messages = dataMap.remove("messages");
		Object systemPrompt = dataMap.remove("system");
		
		if (messages == null) {
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse(
					"invalid_request_error", "messages is required");
			return WebUtility.getResponse(errorMap, 400);
		}

		List<Map<String, Object>> normalizedMessages = AnthropicMessagesHelper.normalizeMessages(messages, systemPrompt);

		Object tools = dataMap.remove("tools");
		if (tools != null) {
			List<Map<String, Object>> normalizedTools = AnthropicMessagesHelper.normalizeTools(tools);
			if (normalizedTools != null && !normalizedTools.isEmpty()) {
				dataMap.put("tools", normalizedTools);
			}
		}

		boolean isStreamingRequest = Boolean.parseBoolean(dataMap.getOrDefault("stream", false).toString());
		dataMap.remove("stream");

		String insightId = WebUtility.inputSanitizer((String) dataMap.remove("insight_id"));
		if (insightId == null) {
			insight = new Insight();
			InsightStore.getInstance().put(insight);
			insightId = insight.getInsightId();
		} else {
			insight = InsightStore.getInstance().get(insightId);
		}

		if (insight == null) {
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse(
					"invalid_request_error", "Invalid insight_id provided");
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
		insight.setUser(user);

		String roomId = WebUtility.inputSanitizer((String) dataMap.remove("room_id"));
		room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);

		ThreadStore.setInsightId(insight.getInsightId());
		ThreadStore.setSessionId(SESSION_ID);
		ThreadStore.setJobId(JOB_ID);
		ThreadStore.setUser(insight.getUser());

		dataMap.put(AbstractModelEngine.FULL_PROMPT, normalizedMessages);

		final Insight finalInsight = insight;
		final Room finalRoom = room;

		if (!isStreamingRequest) {
			return handleNonStreamingRequest(engine, finalInsight, finalRoom, dataMap, engineId);
		} else {
			return handleStreamingRequest(engine, finalInsight, finalRoom, dataMap, SESSION_ID, JOB_ID, engineId);
		}
	}

	/**
	 * Handle non-streaming message request.
	 */
	private Response handleNonStreamingRequest(IModelEngine engine, Insight insight, Room room,
			Map<String, Object> dataMap, String engineId) {
		AskModelEngineResponse llmResponse;
		try {
			InputMessage msg = InputMessage.builder(room)
					.withModelType(engine.getModelType())
					.withParamMap(dataMap)
					.build();
			ResponseMessage response = room.ask(msg, engine);
			llmResponse = response.getModelEngineResponse();
		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, Object> errorMap = AnthropicMessagesHelper.createErrorResponse(
					"api_error", "Error processing request: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}

		Map<String, Object> responseMap = AnthropicMessagesHelper.processAskModelEngineResponse(engineId, llmResponse);
		return WebUtility.getResponse(responseMap, 200);
	}

	/**
	 * Handle streaming message request.
	 * Returns SSE stream with Anthropic-compatible events.
	 */
	private Response handleStreamingRequest(IModelEngine engine, Insight finalInsight, Room finalRoom,
			Map<String, Object> dataMap, String sessionId, String jobId, String engineId) {

		classLogger.info("Starting Anthropic streaming response for engine: " + engineId);

		return Response.ok()
				.header("Content-Type", "text/event-stream")
				.header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive")
				.header("X-Content-Type-Options", "nosniff")
				.entity(new StreamingOutput() {
					@Override
					@SuppressWarnings("unchecked")
					public void write(OutputStream output) throws IOException, WebApplicationException {
						String messageId = "msg_" + GUID.v7().toUUID().toString().replace("-", "");
						String asyncJobId = null;

						try (Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
							asyncJobId = startAsyncModelRequest(engine, finalInsight, finalRoom, dataMap, sessionId);

							boolean started = false;
							int contentBlockIndex = 0;

							STREAM_COMPLETE_LOOP: while (true) {
								PixelJobThread jt = PixelJobManager.getManager().getJob(asyncJobId);
								List<Map<String, Object>> partialResponseContent = PixelJobManager.getManager()
										.getStreamOut(asyncJobId);
								PixelJobStatus jobStatus = jt == null ? PixelJobStatus.UNKNOWN_JOB
										: jt.getPixelJobStatus();

								if (partialResponseContent != null && partialResponseContent.size() > 0) {
									for (Map<String, Object> streamObj : partialResponseContent) {
										String streamType = (String) streamObj.get("stream_type");
										Map<String, Object> streamData = (Map<String, Object>) streamObj.get("data");

										if ("content".equalsIgnoreCase(streamType)) {
											if (streamData.containsKey("finish_reason")) {
												String finishReason = (String) streamData.get("finish_reason");
												
												if (started) {
													AnthropicMessagesHelper.writeContentBlockStop(contentBlockIndex, writer);
												}
												
												String stopReason = mapFinishReasonToStopReason(finishReason);
												AnthropicMessagesHelper.writeMessageDelta(stopReason, null, writer);
												AnthropicMessagesHelper.writeMessageStop(writer);
												break STREAM_COMPLETE_LOOP;
											} else {
												String newContent = (String) streamData.get("content");
												if (newContent != null && !newContent.isEmpty()) {
													if (!started) {
														AnthropicMessagesHelper.writeMessageStart(messageId, engineId, 0, writer);
														AnthropicMessagesHelper.writeTextContentBlockStart(contentBlockIndex, writer);
														started = true;
													}
													AnthropicMessagesHelper.writeTextDelta(contentBlockIndex, newContent, writer);
												}
											}
										} else {
											if (streamData.containsKey("finish_reason")) {
												String finishReason = (String) streamData.get("finish_reason");
												
												if (started) {
													AnthropicMessagesHelper.writeContentBlockStop(contentBlockIndex, writer);
												}
												
												String stopReason = mapFinishReasonToStopReason(finishReason);
												AnthropicMessagesHelper.writeMessageDelta(stopReason, null, writer);
												AnthropicMessagesHelper.writeMessageStop(writer);
												break STREAM_COMPLETE_LOOP;
											} else {
												if (!started) {
													AnthropicMessagesHelper.writeMessageStart(messageId, engineId, 0, writer);
													started = true;
												}
												
												String toolId = (String) streamData.get("id");
												String toolName = null;
												Map<String, Object> functionMap = (Map<String, Object>) streamData.get("function");
												if (functionMap != null) {
													toolName = (String) functionMap.get("name");
													String args = (String) functionMap.get("arguments");
													
													if (toolId != null && toolName != null) {
														AnthropicMessagesHelper.writeToolUseContentBlockStart(
																contentBlockIndex, toolId, toolName, writer);
													}
													if (args != null && !args.isEmpty()) {
														AnthropicMessagesHelper.writeInputJsonDelta(
																contentBlockIndex, args, writer);
													}
												}
											}
										}
									}
								}

								if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE && started) {
									AnthropicMessagesHelper.writeContentBlockStop(contentBlockIndex, writer);
									AnthropicMessagesHelper.writeMessageDelta("end_turn", null, writer);
									AnthropicMessagesHelper.writeMessageStop(writer);
									break STREAM_COMPLETE_LOOP;
								} else if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE && !started) {
									PixelRunner finalOutput = PixelJobManager.getManager().getOutput(asyncJobId);
									NounMetadata finalNoun = finalOutput.getResults().get(0);
									Object finalObject = finalNoun.getValue();
									
									String messageType = null;
									Map<String, Object> resultOutput = null;
									if (finalObject instanceof Map) {
										resultOutput = (Map<String, Object>) finalObject;
										messageType = (String) resultOutput.get("messageType");
									}

									AnthropicMessagesHelper.writeMessageStart(messageId, engineId, 0, writer);

									if ("TOOL".equals(messageType)) {
										List<Map<String, Object>> toolResponses = (List<Map<String, Object>>) resultOutput.get("response");
										if (toolResponses != null) {
											for (int i = 0; i < toolResponses.size(); i++) {
												Map<String, Object> toolResp = toolResponses.get(i);
												String toolId = (String) toolResp.get("id");
												String toolName = (String) toolResp.get("name");
												Object toolArgs = toolResp.get("arguments");
												String argsJson = toolArgs instanceof String ? 
														(String) toolArgs : GSON.toJson(toolArgs);
												
												AnthropicMessagesHelper.writeToolUseContentBlockStart(i, toolId, toolName, writer);
												AnthropicMessagesHelper.writeInputJsonDelta(i, argsJson, writer);
												AnthropicMessagesHelper.writeContentBlockStop(i, writer);
											}
										}
										AnthropicMessagesHelper.writeMessageDelta("tool_use", null, writer);
									} else {
										String content = resultOutput != null ? 
												(String) resultOutput.get("response") : "";
										
										AnthropicMessagesHelper.writeTextContentBlockStart(0, writer);
										if (content != null && !content.isEmpty()) {
											AnthropicMessagesHelper.writeTextDelta(0, content, writer);
										}
										AnthropicMessagesHelper.writeContentBlockStop(0, writer);
										AnthropicMessagesHelper.writeMessageDelta("end_turn", null, writer);
									}

									AnthropicMessagesHelper.writeMessageStop(writer);
									break STREAM_COMPLETE_LOOP;
								}

								try {
									Thread.sleep(100);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
									break;
								}
							}
						} catch (Exception e) {
							classLogger.error("Error in streaming response", e);
							throw new WebApplicationException(e, 500);
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

	/**
	 * Start an asynchronous model request and return the job ID.
	 */
	private String startAsyncModelRequest(IModelEngine engine, Insight insight, Room room, 
			Map<String, Object> dataMap, String sessionId) {
		try {
			PixelJobManager manager = PixelJobManager.getManager();
			PixelJobThread jt = manager.makeJob(insight, sessionId, null);
			String jobId = jt.getJobId();

			String modelPixel = "LLM(engine='" + engine.getEngineId() + "',roomId='" + room.getId()
					+ "',command='<encode>ignore</encode>'"
					+ ",paramValues=[" + GSON.toJson(dataMap) + "]);";
			classLogger.info(modelPixel);
			jt.addPixel(modelPixel);
			jt.start();
			return jobId;
		} catch (Exception e) {
			classLogger.warn("Failed to start async job");
			classLogger.error(Constants.STACKTRACE, e);
			throw new IllegalArgumentException(e.getMessage());
		}
	}

}
