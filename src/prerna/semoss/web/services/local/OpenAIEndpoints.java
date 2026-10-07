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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.f4b6a3.uuid.alt.GUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.AbstractModelEngine;
import prerna.engine.impl.model.ModelPixelInvoker;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.model.openai.OpenAIChatCompletionsHelper;
import prerna.engine.impl.model.openai.OpenAIEmbeddingsHelper;
import prerna.engine.impl.model.openai.OpenAIImagesHelper;
import prerna.engine.impl.model.openai.OpenAIModelsHelper;
import prerna.engine.impl.model.openai.OpenAIResponsesHelper;
import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.responses.EmbeddingsModelEngineResponse;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.comm.PixelJobManager;
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

	private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.disableHtmlEscaping().create();

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

		dataMap.remove("client_metadata");

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

		ModelPixelInvoker.initializeThreadStore(insight, SESSION_ID, JOB_ID);

		final Insight FINAL_INSIGHT = insight;
		final Room FINAL_ROOM = room;

		dataMap.put(AbstractModelEngine.FULL_PROMPT, fullPrompt);
		dataMap.put(AbstractModelEngine.APPEND_FULL_PROMPT, appendFullPrompt);

		if (!isStreamingRequest) {
			AskModelEngineResponse llmResponse;
			try {
				llmResponse = ModelPixelInvoker.askModelSync(engine, FINAL_INSIGHT, FINAL_ROOM, dataMap);
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
								jobId = ModelPixelInvoker.startAsyncModelRequest(engine, FINAL_INSIGHT, FINAL_ROOM,
										dataMap, SESSION_ID);
								OpenAIChatCompletionsHelper.streamJobToWriter(engineId, messageId, creationTimestamp,
										jobId, writer);
							} catch (IOException ioe) {
								final String capturedJobId = jobId;
								if (!WebUtility.handleStreamingException(ioe, classLogger, engineId, capturedJobId,
										() -> PixelJobManager.getManager().interruptThread(capturedJobId))) {
									classLogger.error(
											"Streaming chat completions response failed for engine '{}', job '{}': {}",
											engineId, jobId, ioe.getMessage(), ioe);
									throw new WebApplicationException(ioe, 500);
								}
							} catch (Exception e) {
								classLogger.error(
										"Streaming chat completions response failed for engine '{}', job '{}': {}",
										engineId, jobId, e.getMessage(), e);
								throw new WebApplicationException(e, 500);
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

		dataMap.remove("client_metadata");

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

		ModelPixelInvoker.initializeThreadStore(insight, SESSION_ID, JOB_ID);

		dataMap.put(AbstractModelEngine.FULL_PROMPT, messages);

		if (!isStreamingRequest) {
			try {
				AskModelEngineResponse llmResponse = ModelPixelInvoker.askModelSync(engine, insight, room, dataMap);

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

						try (Writer writer = new BufferedWriter(
								new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
							jobId = ModelPixelInvoker.startAsyncModelRequest(engine, FINAL_INSIGHT, FINAL_ROOM,
									FINAL_DATA_MAP, FINAL_SESSION_ID);
							OpenAIResponsesHelper.streamJobToWriter(FINAL_ENGINE_ID, responseId, creationTimestamp,
									jobId, writer);
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

		ModelPixelInvoker.initializeThreadStore(insight, SESSION_ID, JOB_ID);

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
				AskModelEngineResponse<?> llmResponse = ModelPixelInvoker.askModelSync(engine, insight, room, dataMap);
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
							jobId = ModelPixelInvoker.startAsyncModelRequest(engine, FINAL_INSIGHT, FINAL_ROOM,
									FINAL_DATA_MAP, FINAL_SESSION_ID);
							OpenAIImagesHelper.streamJobToWriter(FINAL_ENGINE_ID, creationTimestamp, jobId,
									FINAL_OUTPUT_FORMAT, FINAL_QUALITY, FINAL_SIZE, writer);
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
						}
					}
				}).build();
	}

	// TODO: move payload generation logic into a new OpenAICompletionsHelper in
	// prerna.engine.impl.model.openai, next to the other format helpers, so the
	// OpenAIPassthroughReactor can serve this path too

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

		ModelPixelInvoker.initializeThreadStore(insight, SESSION_ID, JOB_ID);

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
				llmResponse = ModelPixelInvoker.askModelSync(engine, insight, room, dataMap);
			} catch (Exception e) {
				classLogger.error("Model completion synchronous call failed for engine '{}'", engineId, e);
				return ModelPixelExecutor.errorResponse(400, e.getMessage());
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

						try (Writer writer = new BufferedWriter(
								new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
							// Get full completion from the model in one go through the LLM pixel
							AskModelEngineResponse llmResponse = ModelPixelInvoker.askModelSync(engine, FINAL_INSIGHT,
									FINAL_ROOM, dataMap);
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
	@Path("/v1/embeddings")
	@Consumes({ "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response runV1ModelEmbeddings(@Context HttpServletRequest request) {
		return runModelEmbeddings(request);
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

		ModelPixelInvoker.initializeThreadStore(insight, SESSION_ID, JOB_ID);

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

		return WebUtility.getResponse(OpenAIEmbeddingsHelper.processEmbeddingsResponse(engineId, embeddingsResponse),
				200);
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

		return WebUtility.getResponse(OpenAIModelsHelper.listModels(user), 200);
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

		Map<String, Object> model = OpenAIModelsHelper.retrieveModel(user, modelId);
		if (model == null) {
			return ModelPixelExecutor.errorResponse(400, "Could not find model = '" + modelId + "'");
		}
		return WebUtility.getResponse(model, 200);
	}

}
