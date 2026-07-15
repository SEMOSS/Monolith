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

import com.github.f4b6a3.uuid.alt.GUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

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
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.om.nounmeta.NounMetadata;
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

	private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.disableHtmlEscaping().create();

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
		User user = ModelPixelExecutor.getSessionUser(session);
		if (user == null) {
			return ModelPixelExecutor.invalidSessionResponse(request, session);
		}
		// set the user timezone
		ModelPixelExecutor.applyUserTimezone(user, request);

		Map<String, Object> dataMap;
		try {
			dataMap = readRequestData(request);
		} catch (JsonSyntaxException e) {
			classLogger.error("Failed to parse JSON payload for Ollama /chat request: {}", e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Error processing JSON data: " + e.getMessage());
		} catch (IOException e) {
			classLogger.error("Failed to read request body for Ollama /chat endpoint: {}", e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Bad Request: Could not read request body.");
		}

		String engineId = sanitize(dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400, "Bad Request: Missing required field 'model'.");
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return ModelPixelExecutor.errorResponse(403,
					"Model " + engineId + " does not exist or user does not have access to this model");
		}

		IModelEngine engine = Utility.getModel(engineId);
		if (engine == null) {
			return ModelPixelExecutor.errorResponse(404, "Model not found: " + engineId);
		}
		sanitizeProviderSpecificParams(dataMap, engine);

		final String SESSION_ID = session.getId();
		final String JOB_ID = GUID.v7().toUUID().toString();

		Insight insight = resolveInsight(SESSION_ID, sanitize(dataMap.remove("insight_id")));
		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not resolve insight context");
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
		insight.setUser(user);

		String roomId = sanitize(dataMap.remove("room_id"));
		Room room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);

		ModelPixelExecutor.initializeThreadStore(insight, SESSION_ID, JOB_ID);

		Object messagesInput = dataMap.remove("messages");
		if (messagesInput == null) {
			return ModelPixelExecutor.errorResponse(400, "Bad Request: Missing required field 'messages'.");
		}

		List<Map<String, Object>> normalizedMessages = OllamaResponsesHelper.normalizeChatMessages(messagesInput);
		if (normalizedMessages.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400, "Bad Request: 'messages' must contain at least one item.");
		}

		boolean stream = Boolean.parseBoolean(String.valueOf(dataMap.getOrDefault("stream", true)));
		dataMap.remove("stream");

		boolean appendFullPrompt = Boolean
				.parseBoolean(String.valueOf(dataMap.getOrDefault("append_full_prompt", false)));
		dataMap.remove("append_full_prompt");
		dataMap.put(AbstractModelEngine.FULL_PROMPT, normalizedMessages);
		dataMap.put(AbstractModelEngine.APPEND_FULL_PROMPT, appendFullPrompt);

		if (!stream) {
			try {
				AskModelEngineResponse llmResponse = ModelPixelExecutor.askModelSync(engine, insight, room, dataMap);
				Map<String, Object> payload = OllamaResponsesHelper.processFullChatResponse(engineId, llmResponse);
				return WebUtility.getResponse(payload, 200);
			} catch (Exception e) {
				classLogger.error("Synchronous Ollama /chat call failed for engine '{}': {}", engineId, e.getMessage(),
						e);
				return ModelPixelExecutor.errorResponse(400, e.getMessage());
			}
		}

		StreamingOutput output = new StreamingOutput() {
			@Override
			@SuppressWarnings("unchecked")
			public void write(OutputStream rawOutput) throws IOException, WebApplicationException {

				// ollama does not actually stream tool deltas
				// need to aggregate the entire tool
				// and then send
				Map<String, Object> currentToolMap = new HashMap<>();

				String jobId = null;
				try (Writer writer = new BufferedWriter(new OutputStreamWriter(rawOutput, StandardCharsets.UTF_8))) {
					jobId = ModelPixelExecutor.startAsyncModelRequest(engine, insight, room, dataMap, SESSION_ID);

					boolean started = false;

					Integer capturedPromptTokens = null;
					Integer capturedCompletionTokens = null;
					Integer capturedCachedTokens = null;
					Integer capturedReasoningTokens = null;

					// polling streaming endpoint until response complete
					STREAM_COMPLETE_LOOP: while (true) {
						PixelJobRunner jt = PixelJobManager.getManager().getJob(jobId);
						List<Map<String, Object>> partialResponseContent = PixelJobManager.getManager()
								.getStreamOut(jobId);
						PixelJobStatus jobStatus = jt == null ? PixelJobStatus.UNKNOWN_JOB : jt.getPixelJobStatus();

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
										OllamaResponsesHelper.writeFinishReason(finishReason, capturedPromptTokens,
												capturedCompletionTokens, capturedCachedTokens, capturedReasoningTokens,
												writer);
										break STREAM_COMPLETE_LOOP;
									} else {
										String newContent = (String) dataMap.get("content");
										if (newContent != null && !newContent.isEmpty()) {
											OllamaResponsesHelper.writeChatContentChunk(engineId, newContent, writer);
											started = true;
										}
									}
								} else {
									// assuming only other type is tool at the moment
									if (dataMap.containsKey("finish_reason")) {
										// we need to send the aggregated tool chunk
										if (!currentToolMap.isEmpty()) {
											OllamaResponsesHelper.writeChatTool(engineId, currentToolMap, writer);
											currentToolMap = new HashMap<>();
										}
										// send the finish chunk
										String finishReason = (String) dataMap.get("finish_reason");
										OllamaResponsesHelper.writeFinishReason(finishReason, capturedPromptTokens,
												capturedCompletionTokens, capturedCachedTokens, capturedReasoningTokens,
												writer);
										break STREAM_COMPLETE_LOOP;
									} else {
										// we are entering a new tool
										if (!currentToolMap.isEmpty() && dataMap.containsKey("id")) {
											OllamaResponsesHelper.writeChatTool(engineId, currentToolMap, writer);
											currentToolMap = new HashMap<>();
										}
										OllamaResponsesHelper.aggregateToolChunks(currentToolMap, dataMap);
										started = true;
									}
								}
							}
						}

						// if job is complete, we should never hit this if
						// a completion should have been sent
						if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE && started) {
							// send final chunk with empty delta && finish_reason="stop"
							OllamaResponsesHelper.writeFinishReason("stop", capturedPromptTokens,
									capturedCompletionTokens, capturedCachedTokens, capturedReasoningTokens, writer);
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
									OllamaResponsesHelper.writeFullChatToolResponseAsChunk(engineId, response, writer);
								}
								// done reason is still stop
								// unlike open_ai which is tool_calls
								OllamaResponsesHelper.writeFinishReason("stop", capturedPromptTokens,
										capturedCompletionTokens, capturedCachedTokens, capturedReasoningTokens,
										writer);
							} else {
								// Handle regular text response
								String content = null;
								if (resultOutput != null) {
									content = (String) resultOutput.get("response");
									if (content != null && !content.isEmpty()) {
										OllamaResponsesHelper.writeChatContentChunk(engineId, content, writer);
									}
								}

								// send final chunk with empty delta && finish_reason="stop"
								OllamaResponsesHelper.writeFinishReason("stop", capturedPromptTokens,
										capturedCompletionTokens, capturedCachedTokens, capturedReasoningTokens,
										writer);
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
					if (!WebUtility.handleStreamingException(ioe, classLogger, engineId, null, null)) {
						classLogger.error("Streaming Ollama /chat call failed for engine '{}': {}", engineId,
								ioe.getMessage(), ioe);
						throw new WebApplicationException(ioe, 500);
					}
				} catch (Exception e) {
					classLogger.error("Streaming Ollama /chat call failed for engine '{}': {}", engineId,
							e.getMessage(), e);
					throw new WebApplicationException(e, 500);
				}
			}
		};

		return Response.ok().header("Content-Type", "application/x-ndjson").header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive").entity(output).build();
	}

	@POST
	@Path("/api/generate")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "application/x-ndjson" })
	public Response apigenerate(@Context HttpServletRequest request) {
		return generate(request);
	}

	@POST
	@Path("/generate")
	@Consumes({ "application/json" })
	@Produces({ "application/json;charset=utf-8", "application/x-ndjson" })
	public Response generate(@Context HttpServletRequest request) {
		// this is equivalent of legacy OpenAI Text completion

		HttpSession session = request.getSession(false);
		User user = ModelPixelExecutor.getSessionUser(session);
		if (user == null) {
			return ModelPixelExecutor.invalidSessionResponse(request, session);
		}
		// set the user timezone
		ModelPixelExecutor.applyUserTimezone(user, request);

		Map<String, Object> dataMap;
		try {
			dataMap = readRequestData(request);
		} catch (JsonSyntaxException e) {
			classLogger.error("Failed to parse JSON payload for Ollama /generate request: {}", e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Error processing JSON data: " + e.getMessage());
		} catch (IOException e) {
			classLogger.error("Failed to read request body for Ollama /generate endpoint: {}", e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Bad Request: Could not read request body.");
		}

		String engineId = sanitize(dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400, "Bad Request: Missing required field 'model'.");
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return ModelPixelExecutor.errorResponse(403,
					"Model " + engineId + " does not exist or user does not have access to this model");
		}

		IModelEngine engine = Utility.getModel(engineId);
		if (engine == null) {
			return ModelPixelExecutor.errorResponse(404, "Model not found: " + engineId);
		}
		sanitizeProviderSpecificParams(dataMap, engine);

		final String SESSION_ID = session.getId();
		final String JOB_ID = GUID.v7().toUUID().toString();

		Insight insight = resolveInsight(SESSION_ID, sanitize(dataMap.remove("insight_id")));
		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not resolve insight context");
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
		insight.setUser(user);

		String roomId = sanitize(dataMap.remove("room_id"));
		Room room = RoomUtils.createRoomIfNotExists(roomId, insight, engine, null);

		ModelPixelExecutor.initializeThreadStore(insight, SESSION_ID, JOB_ID);

		Object promptInput = dataMap.remove("prompt");
		Object inputFallback = dataMap.remove("input");
		String prompt = OllamaResponsesHelper.extractPrompt(promptInput, inputFallback);
		if (prompt == null || prompt.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400, "Bad Request: Missing required field 'prompt'.");
		}

		boolean stream = Boolean.parseBoolean(String.valueOf(dataMap.getOrDefault("stream", true)));
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
				Map<String, Object> payload = OllamaResponsesHelper.processFullGenerateResponse(engineId, llmResponse);
				return WebUtility.getResponse(payload, 200);
			} catch (Exception e) {
				classLogger.error("Synchronous Ollama /generate call failed for engine '{}': {}", engineId,
						e.getMessage(), e);
				return ModelPixelExecutor.errorResponse(400, e.getMessage());
			}
		}

		StreamingOutput output = new StreamingOutput() {
			@Override
			@SuppressWarnings("unchecked")
			public void write(OutputStream rawOutput) throws IOException, WebApplicationException {
				String jobId = null;
				try (Writer writer = new BufferedWriter(new OutputStreamWriter(rawOutput, StandardCharsets.UTF_8))) {
					jobId = ModelPixelExecutor.startAsyncModelRequest(engine, insight, room, dataMap, SESSION_ID);

					boolean started = false;

					Integer capturedPromptTokens = null;
					Integer capturedCompletionTokens = null;
					Integer capturedCachedTokens = null;
					Integer capturedReasoningTokens = null;

					// polling streaming endpoint until response complete
					STREAM_COMPLETE_LOOP: while (true) {
						PixelJobRunner jt = PixelJobManager.getManager().getJob(jobId);
						List<Map<String, Object>> partialResponseContent = PixelJobManager.getManager()
								.getStreamOut(jobId);
						PixelJobStatus jobStatus = jt == null ? PixelJobStatus.UNKNOWN_JOB : jt.getPixelJobStatus();

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
										OllamaResponsesHelper.writeFinishReason(finishReason, capturedPromptTokens,
												capturedCompletionTokens, capturedCachedTokens, capturedReasoningTokens,
												writer);
										break STREAM_COMPLETE_LOOP;
									} else {
										String newContent = (String) dataMap.get("content");
										if (newContent != null && !newContent.isEmpty()) {
											OllamaResponsesHelper.writeGenerateContentChunk(engineId, newContent,
													writer);
											started = true;
										}
									}
								} else {
									// for future stream types
								}
							}
						}

						// if job is complete, we should never hit this if
						// a completion should have been sent
						if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE && started) {
							// send final chunk with empty delta && finish_reason="stop"
							OllamaResponsesHelper.writeFinishReason("stop", capturedPromptTokens,
									capturedCompletionTokens, capturedCachedTokens, capturedReasoningTokens, writer);
							break STREAM_COMPLETE_LOOP;
						} else if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE && !started) {
							// we didn't start
							// and there is no output
							// lets check the result
							// ... most likely this is a tool output
							PixelRunner finalOutput = PixelJobManager.getManager().getOutput(jobId);
							NounMetadata finalNoun = finalOutput.getResults().get(0);
							Object finalObject = finalNoun.getValue();

							// this is not used, but for future in terms of thinking vs regular content
							String messageType = null;
							Map<String, Object> resultOutput = null;
							if (finalObject instanceof Map) {
								resultOutput = (Map<String, Object>) finalObject;
								messageType = (String) resultOutput.get("messageType");
							}

							// Handle regular text response
							String content = null;
							if (resultOutput != null) {
								content = (String) resultOutput.get("response");
								if (content != null && !content.isEmpty()) {
									OllamaResponsesHelper.writeGenerateContentChunk(engineId, content, writer);
								}
							}

							// send final chunk with empty delta && finish_reason="stop"
							OllamaResponsesHelper.writeFinishReason("stop", capturedPromptTokens,
									capturedCompletionTokens, capturedCachedTokens, capturedReasoningTokens, writer);

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
					if (!WebUtility.handleStreamingException(ioe, classLogger, engineId, null, null)) {
						classLogger.error("Streaming Ollama /chat call failed for engine '{}': {}", engineId,
								ioe.getMessage(), ioe);
						throw new WebApplicationException(ioe, 500);
					}
				} catch (Exception e) {
					classLogger.error("Streaming Ollama /chat call failed for engine '{}': {}", engineId,
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
		User user = ModelPixelExecutor.getSessionUser(session);
		if (user == null) {
			return ModelPixelExecutor.invalidSessionResponse(request, session);
		}
		// set the user timezone
		ModelPixelExecutor.applyUserTimezone(user, request);

		Map<String, Object> dataMap;
		try {
			dataMap = readRequestData(request);
		} catch (JsonSyntaxException e) {
			classLogger.error("Failed to parse JSON payload for Ollama /embeddings request: {}", e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Error processing JSON data: " + e.getMessage());
		} catch (IOException e) {
			classLogger.error("Failed to read request body for Ollama /embeddings endpoint: {}", e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, "Bad Request: Could not read request body.");
		}

		String engineId = sanitize(dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400, "Bad Request: Missing required field 'model'.");
		}

		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			return ModelPixelExecutor.errorResponse(403,
					"Model " + engineId + " does not exist or user does not have access to this model");
		}

		IModelEngine engine = Utility.getModel(engineId);
		if (engine == null) {
			return ModelPixelExecutor.errorResponse(404, "Model not found: " + engineId);
		}
		sanitizeProviderSpecificParams(dataMap, engine);

		final String SESSION_ID = session.getId();
		final String JOB_ID = GUID.v7().toUUID().toString();

		Insight insight = resolveInsight(SESSION_ID, sanitize(dataMap.remove("insight_id")));
		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not resolve insight context");
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
		insight.setUser(user);
		ModelPixelExecutor.initializeThreadStore(insight, SESSION_ID, JOB_ID);

		Object promptInput = dataMap.remove("prompt");
		Object inputInput = dataMap.remove("input");
		List<String> stringsToEncode = OllamaResponsesHelper.extractEmbeddingInputs(promptInput, inputInput);
		if (stringsToEncode == null || stringsToEncode.isEmpty()) {
			return ModelPixelExecutor.errorResponse(400, "Bad Request: Missing required field 'prompt' or 'input'.");
		}

		try {
			EmbeddingsModelEngineResponse embeddingsResponse = engine.embeddings(stringsToEncode, insight, dataMap);
			Map<String, Object> payload = OllamaResponsesHelper.processEmbeddingsResponse(engineId, embeddingsResponse,
					stringsToEncode.size() == 1);
			return WebUtility.getResponse(payload, 200);
		} catch (Exception e) {
			classLogger.error("Ollama /embeddings call failed for engine '{}': {}", engineId, e.getMessage(), e);
			return ModelPixelExecutor.errorResponse(400, e.getMessage());
		}
	}

	private Map<String, Object> readRequestData(HttpServletRequest request) throws IOException {
		StringBuilder requestData = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				requestData.append(line);
			}
		}

		return GSON.fromJson(WebUtility.jsonSanitizer(requestData.toString()), new TypeToken<Map<String, Object>>() {
		}.getType());
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
