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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.AbstractModelEngine;
import prerna.engine.impl.model.Room;
import prerna.engine.impl.model.RoomUtils;
import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.responses.EmbeddingsModelEngineResponse;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.ThreadStore;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.ModelPixelExecutor;
import prerna.web.services.util.WebUtility;

@Path("/model/ollama")
@PermitAll
public class OllamaEndpoints {

	private static final Logger classLogger = LogManager.getLogger(OllamaEndpoints.class);

	private static final String ERROR_TYPE = "errorType";
	private static final String INSIGHT_NOT_FOUND = "INSIGHT_NOT_FOUND";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@POST
	@Path("/generate")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "application/x-ndjson" })
	public Response generate(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		User user = getSessionUser(session);
		if (user == null) {
			return invalidSessionResponse(request, session);
		}

		applyUserTimezone(user, request);

		Map<String, Object> dataMap;
		try {
			dataMap = readRequestData(request);
		} catch (JsonProcessingException e) {
			classLogger.error("Failed to parse JSON payload for Ollama /generate request: {}", e.getOriginalMessage(),
					e);
			return errorResponse(400, "Error processing JSON data: " + e.getMessage());
		} catch (IOException e) {
			classLogger.error("Failed to read request body for Ollama /generate endpoint: {}", e.getMessage(), e);
			return errorResponse(400, "Bad Request: Could not read request body.");
		}

		String engineId = sanitize(dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			return errorResponse(400, "Bad Request: Missing required field 'model'.");
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return errorResponse(403,
					"Model " + engineId + " does not exist or user does not have access to this model");
		}

		IModelEngine engine = Utility.getModel(engineId);
		if (engine == null) {
			return errorResponse(404, "Model not found: " + engineId);
		}
		sanitizeProviderSpecificParams(dataMap, engine);

		String sessionId = session.getId();
		String jobId = GUID.v7().toUUID().toString();

		Insight insight = resolveInsight(sessionId, sanitize(dataMap.remove("insight_id")));
		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not resolve insight context");
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
		insight.setUser(user);

		String roomId = sanitize(dataMap.remove("room_id"));
		Room room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);

		initializeThreadStore(insight, sessionId, jobId);

		Object promptInput = dataMap.remove("prompt");
		Object inputFallback = dataMap.remove("input");
		String prompt = OllamaResponsesHelper.extractPrompt(promptInput, inputFallback);
		if (prompt == null || prompt.isEmpty()) {
			return errorResponse(400, "Bad Request: Missing required field 'prompt'.");
		}

		boolean stream = Boolean.parseBoolean(String.valueOf(dataMap.getOrDefault("stream", false)));
		dataMap.remove("stream");

		// route through the LLM pixel by carrying the prompt as a full_prompt message
		List<Map<String, Object>> generateMessages = new ArrayList<>();
		Map<String, Object> generateUserMessage = new HashMap<>();
		generateUserMessage.put("role", "user");
		generateUserMessage.put("content", prompt);
		generateMessages.add(generateUserMessage);
		dataMap.put(AbstractModelEngine.FULL_PROMPT, generateMessages);

		if (!stream) {
			try {
				AskModelEngineResponse llmResponse = ModelPixelExecutor.askModelSync(engine, insight, room, dataMap);
				Map<String, Object> payload = OllamaResponsesHelper.processGenerateResponse(engineId, llmResponse);
				return WebUtility.getResponse(payload, 200);
			} catch (Exception e) {
				classLogger.error("Synchronous Ollama /generate call failed for engine '{}': {}", engineId,
						e.getMessage(), e);
				return errorResponse(400, e.getMessage());
			}
		}

		final IModelEngine finalEngine = engine;
		final Insight finalInsight = insight;
		final Room finalRoom = room;
		final Map<String, Object> finalDataMap = dataMap;
		final String finalEngineId = engineId;

		StreamingOutput output = new StreamingOutput() {
			@Override
			public void write(OutputStream rawOutput) throws IOException, WebApplicationException {
				try (Writer writer = new BufferedWriter(new OutputStreamWriter(rawOutput, StandardCharsets.UTF_8))) {
					AskModelEngineResponse llmResponse = ModelPixelExecutor.askModelSync(finalEngine, finalInsight,
							finalRoom, finalDataMap);

					Map<String, Object> full = OllamaResponsesHelper.processGenerateResponse(finalEngineId,
							llmResponse);
					String responseText = (String) full.get("response");
					String doneReason = (String) full.get("done_reason");

					if (responseText != null && !responseText.isEmpty()) {
						Map<String, Object> partial = OllamaResponsesHelper.createGenerateStreamChunk(finalEngineId,
								responseText, false, null, null);
						OllamaResponsesHelper.writeJsonLine(partial, writer);
					}

					Map<String, Object> done = OllamaResponsesHelper.createGenerateStreamChunk(finalEngineId, "", true,
							doneReason, llmResponse);
					OllamaResponsesHelper.writeJsonLine(done, writer);
				} catch (IOException ioe) {
					if (!WebUtility.handleStreamingException(ioe, classLogger, finalEngineId, null, null)) {
						classLogger.error("Streaming Ollama /generate call failed for engine '{}': {}", finalEngineId,
								ioe.getMessage(), ioe);
						throw new WebApplicationException(ioe, 500);
					}
				} catch (Exception e) {
					classLogger.error("Streaming Ollama /generate call failed for engine '{}': {}", finalEngineId,
							e.getMessage(), e);
					throw new WebApplicationException(e, 500);
				}
			}
		};

		return Response.ok().header("Content-Type", "application/x-ndjson").header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive").entity(output).build();
	}

	@POST
	@Path("/api/chat")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "application/x-ndjson" })
	public Response apiChat(@Context HttpServletRequest request) {
		return chat(request);
	}

	@POST
	@Path("/chat")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "application/x-ndjson" })
	public Response chat(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		User user = getSessionUser(session);
		if (user == null) {
			return invalidSessionResponse(request, session);
		}

		applyUserTimezone(user, request);

		Map<String, Object> dataMap;
		try {
			dataMap = readRequestData(request);
		} catch (JsonProcessingException e) {
			classLogger.error("Failed to parse JSON payload for Ollama /chat request: {}", e.getOriginalMessage(), e);
			return errorResponse(400, "Error processing JSON data: " + e.getMessage());
		} catch (IOException e) {
			classLogger.error("Failed to read request body for Ollama /chat endpoint: {}", e.getMessage(), e);
			return errorResponse(400, "Bad Request: Could not read request body.");
		}

		String engineId = sanitize(dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			return errorResponse(400, "Bad Request: Missing required field 'model'.");
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return errorResponse(403,
					"Model " + engineId + " does not exist or user does not have access to this model");
		}

		IModelEngine engine = Utility.getModel(engineId);
		if (engine == null) {
			return errorResponse(404, "Model not found: " + engineId);
		}
		sanitizeProviderSpecificParams(dataMap, engine);

		String sessionId = session.getId();
		String jobId = GUID.v7().toUUID().toString();

		Insight insight = resolveInsight(sessionId, sanitize(dataMap.remove("insight_id")));
		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not resolve insight context");
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
		insight.setUser(user);

		String roomId = sanitize(dataMap.remove("room_id"));
		Room room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);

		initializeThreadStore(insight, sessionId, jobId);

		Object messagesInput = dataMap.remove("messages");
		if (messagesInput == null) {
			return errorResponse(400, "Bad Request: Missing required field 'messages'.");
		}

		List<Map<String, Object>> normalizedMessages = OllamaResponsesHelper.normalizeChatMessages(messagesInput);
		if (normalizedMessages.isEmpty()) {
			return errorResponse(400, "Bad Request: 'messages' must contain at least one item.");
		}

		boolean stream = Boolean.parseBoolean(String.valueOf(dataMap.getOrDefault("stream", false)));
		dataMap.remove("stream");

		boolean appendFullPrompt = Boolean
				.parseBoolean(String.valueOf(dataMap.getOrDefault("append_full_prompt", false)));
		dataMap.remove("append_full_prompt");
		dataMap.put(AbstractModelEngine.FULL_PROMPT, normalizedMessages);
		dataMap.put(AbstractModelEngine.APPEND_FULL_PROMPT, appendFullPrompt);

		if (!stream) {
			try {
				AskModelEngineResponse llmResponse = ModelPixelExecutor.askModelSync(engine, insight, room, dataMap);
				Map<String, Object> payload = OllamaResponsesHelper.processChatResponse(engineId, llmResponse);
				return WebUtility.getResponse(payload, 200);
			} catch (Exception e) {
				classLogger.error("Synchronous Ollama /chat call failed for engine '{}': {}", engineId, e.getMessage(),
						e);
				return errorResponse(400, e.getMessage());
			}
		}

		final IModelEngine finalEngine = engine;
		final Insight finalInsight = insight;
		final Room finalRoom = room;
		final Map<String, Object> finalDataMap = dataMap;
		final String finalEngineId = engineId;

		StreamingOutput output = new StreamingOutput() {
			@Override
			@SuppressWarnings("unchecked")
			public void write(OutputStream rawOutput) throws IOException, WebApplicationException {
				try (Writer writer = new BufferedWriter(new OutputStreamWriter(rawOutput, StandardCharsets.UTF_8))) {
					AskModelEngineResponse llmResponse = ModelPixelExecutor.askModelSync(finalEngine, finalInsight,
							finalRoom, finalDataMap);

					Map<String, Object> full = OllamaResponsesHelper.processChatResponse(finalEngineId, llmResponse);
					Map<String, Object> message = (Map<String, Object>) full.get("message");
					String content = message == null ? "" : (String) message.get("content");
					List<Map<String, Object>> toolCalls = message == null ? null
							: (List<Map<String, Object>>) message.get("tool_calls");
					String doneReason = (String) full.get("done_reason");

					if ((content != null && !content.isEmpty()) || (toolCalls != null && !toolCalls.isEmpty())) {
						Map<String, Object> partial = OllamaResponsesHelper.createChatStreamChunk(finalEngineId,
								content, toolCalls, false, null, null);
						OllamaResponsesHelper.writeJsonLine(partial, writer);
					}

					Map<String, Object> done = OllamaResponsesHelper.createChatStreamChunk(finalEngineId, "", null,
							true, doneReason, llmResponse);
					OllamaResponsesHelper.writeJsonLine(done, writer);
				} catch (IOException ioe) {
					if (!WebUtility.handleStreamingException(ioe, classLogger, finalEngineId, null, null)) {
						classLogger.error("Streaming Ollama /chat call failed for engine '{}': {}", finalEngineId,
								ioe.getMessage(), ioe);
						throw new WebApplicationException(ioe, 500);
					}
				} catch (Exception e) {
					classLogger.error("Streaming Ollama /chat call failed for engine '{}': {}", finalEngineId,
							e.getMessage(), e);
					throw new WebApplicationException(e, 500);
				}
			}
		};

		return Response.ok().header("Content-Type", "application/x-ndjson").header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive").entity(output).build();
	}

	@POST
	@Path("/embeddings")
	@Consumes({ "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response embeddings(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		User user = getSessionUser(session);
		if (user == null) {
			return invalidSessionResponse(request, session);
		}

		applyUserTimezone(user, request);

		Map<String, Object> dataMap;
		try {
			dataMap = readRequestData(request);
		} catch (JsonProcessingException e) {
			classLogger.error("Failed to parse JSON payload for Ollama /embeddings request: {}", e.getOriginalMessage(),
					e);
			return errorResponse(400, "Error processing JSON data: " + e.getMessage());
		} catch (IOException e) {
			classLogger.error("Failed to read request body for Ollama /embeddings endpoint: {}", e.getMessage(), e);
			return errorResponse(400, "Bad Request: Could not read request body.");
		}

		String engineId = sanitize(dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			return errorResponse(400, "Bad Request: Missing required field 'model'.");
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return errorResponse(403,
					"Model " + engineId + " does not exist or user does not have access to this model");
		}

		IModelEngine engine = Utility.getModel(engineId);
		if (engine == null) {
			return errorResponse(404, "Model not found: " + engineId);
		}
		sanitizeProviderSpecificParams(dataMap, engine);

		String sessionId = session.getId();
		String jobId = GUID.v7().toUUID().toString();

		Insight insight = resolveInsight(sessionId, sanitize(dataMap.remove("insight_id")));
		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not resolve insight context");
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
		insight.setUser(user);
		initializeThreadStore(insight, sessionId, jobId);

		Object promptInput = dataMap.remove("prompt");
		Object inputInput = dataMap.remove("input");
		List<String> stringsToEncode = OllamaResponsesHelper.extractEmbeddingInputs(promptInput, inputInput);
		if (stringsToEncode == null || stringsToEncode.isEmpty()) {
			return errorResponse(400, "Bad Request: Missing required field 'prompt' or 'input'.");
		}

		try {
			EmbeddingsModelEngineResponse embeddingsResponse = engine.embeddings(stringsToEncode, insight, dataMap);
			Map<String, Object> payload = OllamaResponsesHelper.processEmbeddingsResponse(engineId, embeddingsResponse,
					stringsToEncode.size() == 1);
			return WebUtility.getResponse(payload, 200);
		} catch (Exception e) {
			classLogger.error("Ollama /embeddings call failed for engine '{}': {}", engineId, e.getMessage(), e);
			return errorResponse(400, e.getMessage());
		}
	}

	private Map<String, Object> readRequestData(HttpServletRequest request)
			throws IOException, JsonProcessingException {
		StringBuilder requestData = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				requestData.append(line);
			}
		}

		TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {
		};
		return MAPPER.readValue(WebUtility.jsonSanitizer(requestData.toString()), mapType);
	}

	private Insight resolveInsight(String sessionId, String insightId) {
		Insight insight;
		if (insightId == null || insightId.isEmpty()) {
			Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(sessionId);
			if (sessionInsights == null || sessionInsights.isEmpty()) {
				insight = new Insight();
				InsightStore.getInstance().put(insight);
				InsightStore.getInstance().addToSessionHash(sessionId, insight.getInsightId());
			} else {
				String existingId = sessionInsights.iterator().next();
				insight = InsightStore.getInstance().get(existingId);
			}
		} else {
			insight = InsightStore.getInstance().get(insightId);
			InsightStore.getInstance().addToSessionHash(sessionId, insightId);
		}
		return insight;
	}

	private void initializeThreadStore(Insight insight, String sessionId, String jobId) {
		ThreadStore.setInsightId(insight.getInsightId());
		ThreadStore.setSessionId(sessionId);
		ThreadStore.setJobId(jobId);
		ThreadStore.setUser(insight.getUser());
	}

	private void applyUserTimezone(User user, HttpServletRequest request) {
		ZoneId zoneId;
		String strTz = WebUtility.inputSanitizer(request.getParameter("tz"));
		if (strTz == null || (strTz = strTz.trim()).isEmpty()) {
			zoneId = ZoneId.of(Utility.getApplicationZoneId());
		} else {
			try {
				zoneId = ZoneId.of(strTz);
			} catch (Exception e) {
				classLogger.warn(
						"Invalid timezone value '{}' for Ollama request; falling back to application default '{}': {}",
						strTz, Utility.getApplicationZoneId(), e.getMessage(), e);
				zoneId = ZoneId.of(Utility.getApplicationZoneId());
			}
		}
		user.setZoneId(zoneId);
	}

	private User getSessionUser(HttpSession session) {
		if (session == null) {
			return null;
		}
		return (User) session.getAttribute(Constants.SESSION_USER);
	}

	private Response invalidSessionResponse(HttpServletRequest request, HttpSession session) {
		if (session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
			session.invalidate();
		}
		return errorResponse(401, "User session is invalid");
	}

	private Response errorResponse(int statusCode, String message) {
		Map<String, String> errorMap = new HashMap<>();
		errorMap.put(Constants.ERROR_MESSAGE, message);
		return WebUtility.getResponse(errorMap, statusCode);
	}

	private String sanitize(Object input) {
		if (input == null) {
			return null;
		}
		return WebUtility.inputSanitizer(input.toString());
	}

	private void sanitizeProviderSpecificParams(Map<String, Object> dataMap, IModelEngine engine) {
		String modelType = null;
		try {
			modelType = engine.getModelType() == null ? null : engine.getModelType().toString();
		} catch (Exception e) {
			classLogger.warn("Unable to determine model type for provider-specific param sanitization: {}",
					e.getMessage(), e);
		}

		boolean isOllamaModel = modelType != null && modelType.toUpperCase().contains("OLLAMA");
		if (!isOllamaModel) {
			dataMap.remove("options");
			dataMap.remove("think");
			dataMap.remove("keep_alive");
		}
	}
}
