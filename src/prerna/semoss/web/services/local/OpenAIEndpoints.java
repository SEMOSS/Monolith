package prerna.semoss.web.services.local;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.date.SemossDate;
import prerna.engine.api.IEngine;
import prerna.engine.api.IModelEngine;
import prerna.engine.impl.model.responses.AskModelEngineResponse;
import prerna.engine.impl.model.responses.EmbeddingsModelEngineResponse;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.reactor.security.MyEnginesReactor;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.sablecc2.comm.PixelJobThread;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/model/openai")
@PermitAll
@SecurityRequirement(name = "basicAuth")
public class OpenAIEndpoints {

	private static final Logger classLogger = LogManager.getLogger(NameServer.class);

	private static final String ERROR_TYPE = "errorType";
	private static final String INSIGHT_NOT_FOUND = "INSIGHT_NOT_FOUND";

	@POST
	@Path("/chat/completions")
	@Produces("application/json;charset=utf-8")
	public Response runModelChatCompletion(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		User user = null;

		if (session != null) {
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		}
		// how did you even get past the no user in session filter?
		if (user == null) {
			if(session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		final String SESSION_ID = session.getId();
		Insight insight = null;
		ObjectMapper objectMapper = new ObjectMapper();
		
		// set the user timezone
		ZoneId zoneId = null;
		String strTz = WebUtility.inputSanitizer(request.getParameter("tz"));
		if(strTz == null || (strTz=strTz.trim()).isEmpty()) {
			zoneId = ZoneId.of(Utility.getApplicationZoneId());
		} else {
			try {
				zoneId = ZoneId.of(strTz);
			} catch(Exception e) {
				classLogger.warn("Error parsing out users timezone value: " + strTz);
				classLogger.error(Constants.STACKTRACE, e);
				zoneId = ZoneId.of(Utility.getApplicationZoneId());
			}
		}
		// need null check if security is off
		if(user != null) {
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
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing.");
			return WebUtility.getResponse(errorMap, 400);
		}

		classLogger.info("Chat completion request data: " + requestData.toString());

		// Convert the JSON string to a Map
		TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(WebUtility.jsonSanitizer(requestData.toString()), mapType);
		} catch (JsonProcessingException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error processing JSON data: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		boolean isStreamingRequest = false;
		if (dataMap.containsKey("stream")) {
			isStreamingRequest = Boolean.parseBoolean(dataMap.get("stream").toString());
			dataMap.remove("stream");
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing the required 'model' field.");
			return WebUtility.getResponse(errorMap, 400);
		}

		Object fullPrompt = dataMap.remove("messages");
		if (fullPrompt == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Please provide 'messages'.");
			return WebUtility.getResponse(errorMap, 400);
		}

		if(!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Model " + engineId + " does not exist or user does not have access to this model");
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
			InsightStore.getInstance().addToSessionHash(SESSION_ID, insightId); // maybe its an insight id from another session?
		}

		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find the Insight with an Insight ID of " + insightId);
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}

		final Insight finalInsight = insight;

		// set the user
		insight.setUser(user);		

		dataMap.put("full_prompt", fullPrompt);

		IModelEngine engine = Utility.getModel(engineId);
		if (!isStreamingRequest) {
			AskModelEngineResponse llmResponse;
			try {
				llmResponse = engine.ask(null, null, insight, dataMap);
			} catch (Exception e){
				classLogger.error(Constants.STACKTRACE, e);
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
				return WebUtility.getResponse(errorMap, 400);
			}

			String response = llmResponse.getStringResponse();
			String messageId = llmResponse.getMessageId();
			Integer promptTokens = llmResponse.getNumberOfTokensInPrompt();
			Integer responseTokens = llmResponse.getNumberOfTokensInResponse();

			Map<String, Object> llmResponseMap = new HashMap<>();

			// "choices" array
			List<Map<String, Object>> choicesList = new ArrayList<>();
			Map<String, Object> choice = new HashMap<>();
			choice.put("finish_reason", "stop");
			choice.put("index", 0);

			// "message" object within "choices"
			Map<String, Object> message = new HashMap<>();
			message.put("content", response);
			message.put("role", "assistant");

			choice.put("message", message);

			choicesList.add(choice);

			llmResponseMap.put("choices", choicesList);

			// Get the current UTC time
			ZonedDateTime currentDateTime = Utility.getCurrentZonedDateTimeForUser(user);
			// Convert ZonedDateTime to Instant
			Instant instant = currentDateTime.toInstant();
			// Get the number of seconds since the epoch
			long unixTimestamp = instant.getEpochSecond();

			llmResponseMap.put("created", unixTimestamp);
			llmResponseMap.put("id", messageId);
			llmResponseMap.put("model", engineId);
			llmResponseMap.put("object", "chat.completion");

			// "usage" object
			Map<String, Object> usage = new HashMap<>();

			if (promptTokens!= null && responseTokens != null) {
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
			// Streaming implementation!!
			final String messageId = "chatcmpl-" + UUID.randomUUID().toString();
			final long creationTimestamp = Instant.now().getEpochSecond();

			classLogger.info("Starting streaming response for model: " + engineId);

			return Response
					.ok()
					.header("Content-Type", "text/event-stream")
					.header("Cache-Control", "no-cache")
					.header("Connection", "keep-alive")
					.entity(new StreamingOutput() {
						@Override
						public void write(OutputStream output) throws IOException, WebApplicationException {
							ObjectMapper mapper = new ObjectMapper();
							String jobId = null;
							try (Writer writer = new BufferedWriter(new OutputStreamWriter(output))){
								// Execute model request but get job ID so can poll for partial responses
								jobId = startAsyncModelRequest(engine, finalInsight, dataMap, SESSION_ID);

								boolean started = false;
								boolean completionSent = false;

								// polling partial endpoint until response complete
								while (true) {
									PixelJobThread jt = PixelJobManager.getManager().getJob(jobId);
									Map<String, String> partialResponseContent = PixelJobManager.getManager().getPartial(jobId);
									PixelJobStatus jobStatus = jt == null ? PixelJobStatus.UNKNOWN_JOB : jt.getPixelJobStatus();

									if (partialResponseContent != null && partialResponseContent.size() > 0) {
										String newContent = partialResponseContent.get("new");

										if (newContent != null && !newContent.isEmpty()) {
											// formatting as OpenAI streaming chunk
											Map<String, Object> chunk = new HashMap<>();
											chunk.put("id", messageId);
											chunk.put("object", "chat.completion.chunk");
											chunk.put("created", creationTimestamp);
											chunk.put("model", engineId);

											List<Map<String, Object>> choices = new ArrayList<>();
											Map<String, Object> choice = new HashMap<>();
											choice.put("index", 0);

											Map<String, Object> delta = new HashMap<>();

											// if first chunk include role
											if (!started) {
												delta.put("role", "assistant");
												started = true;
											}

											delta.put("content", newContent);
											choice.put("delta", delta);
											choice.put("finish_reason", null);

											choices.add(choice);
											chunk.put("choices", choices);

											// sending chunk as SSE event
											writer.write("data: " + mapper.writeValueAsString(chunk) + "\n\n");
											writer.flush();
										}
									}

									// Check job complete to send completion
									if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE && started && !completionSent) {
										// send final chumk with empty delta && finish_reason="stop"
										Map<String, Object> finalChunk = new HashMap<>();
										finalChunk.put("id", messageId);
										finalChunk.put("object", "chat.completion.chunk");
										finalChunk.put("created", creationTimestamp);
										finalChunk.put("model", engineId);

										List<Map<String, Object>> choices = new ArrayList<>();
										Map<String, Object> choice = new HashMap<>();
										choice.put("index", 0);

										Map<String, Object> delta = new HashMap<>();

										choice.put("delta", delta);
										choice.put("finish_reason", "stop");

										choices.add(choice);
										finalChunk.put("choices", choices);

										writer.write("data: " + mapper.writeValueAsString(finalChunk) + "\n\n");

										writer.write("data: [DONE]\n\n");
										writer.flush();

										completionSent = true;
										break;
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
										if(finalObject instanceof Map) {
											resultOutput = (Map<String, Object>) finalObject;
											messageType = (String) resultOutput.get("messageType");
										}

										if ("TOOL".equals(messageType)) {
										    // this is a function call request
										    List<Map<String, Object>> response = (List<Map<String, Object>>) resultOutput.get("response");
										    
										    if (response != null && !response.isEmpty()) {
										        Map<String, Object> toolCall = response.get(0);
										        
										        // first chunk - start the assistant response with tool calls
										        Map<String, Object> chunk = new HashMap<>();
										        chunk.put("id", messageId);
										        chunk.put("object", "chat.completion.chunk");
										        chunk.put("created", creationTimestamp);
										        chunk.put("model", engineId);
										        
										        List<Map<String, Object>> choices = new ArrayList<>();
										        Map<String, Object> choice = new HashMap<>();
										        choice.put("index", 0);
										        
										        Map<String, Object> delta = new HashMap<>();
										        delta.put("role", "assistant");
										        delta.put("content", null);
										        
										        // add tool calls to delta
										        List<Map<String, Object>> toolCalls = new ArrayList<>();
										        Map<String, Object> toolCallDelta = new HashMap<>();
										        toolCallDelta.put("index", 0);
										        toolCallDelta.put("id", toolCall.get("id"));
										        toolCallDelta.put("type", toolCall.get("type"));
										        
										        Map<String, Object> function = new HashMap<>();
										        function.put("name", toolCall.get("name"));
										        function.put("arguments", toolCall.get("arguments"));
										        toolCallDelta.put("function", function);
										        
										        toolCalls.add(toolCallDelta);
										        delta.put("tool_calls", toolCalls);
										        
										        choice.put("delta", delta);
										        choice.put("finish_reason", null);
										        
										        choices.add(choice);
										        chunk.put("choices", choices);
										        
										        writer.write("data: " + mapper.writeValueAsString(chunk) + "\n\n");
										        
										        // final chunk for function call
										        Map<String, Object> finalChunk = new HashMap<>();
										        finalChunk.put("id", messageId);
										        finalChunk.put("object", "chat.completion.chunk");
										        finalChunk.put("created", creationTimestamp);
										        finalChunk.put("model", engineId);
										        
										        List<Map<String, Object>> finalChoices = new ArrayList<>();
										        Map<String, Object> finalChoice = new HashMap<>();
										        finalChoice.put("index", 0);
										        finalChoice.put("delta", new HashMap<>());
										        finalChoice.put("finish_reason", "tool_calls");
										        
										        finalChoices.add(finalChoice);
										        finalChunk.put("choices", finalChoices);
										        
										        writer.write("data: " + mapper.writeValueAsString(finalChunk) + "\n\n");
										        writer.write("data: [DONE]\n\n");
										        writer.flush();
										    }
										} else {
										    // Handle regular text response
										    String content = null;
										    if(resultOutput != null) {
										    	content = (String) resultOutput.get("response"); 
										    }
										    
										    if (content != null && !content.isEmpty()) {
										        // stream content in chunks (optional - can send all at once)
										        Map<String, Object> chunk = new HashMap<>();
										        chunk.put("id", messageId);
										        chunk.put("object", "chat.completion.chunk");
										        chunk.put("created", creationTimestamp);
										        chunk.put("model", engineId);
										        
										        List<Map<String, Object>> choices = new ArrayList<>();
										        Map<String, Object> choice = new HashMap<>();
										        choice.put("index", 0);
										        
										        Map<String, Object> delta = new HashMap<>();
										        delta.put("role", "assistant");
										        delta.put("content", content);
										        choice.put("delta", delta);
										        choice.put("finish_reason", null);
										        
										        choices.add(choice);
										        chunk.put("choices", choices);
										        
										        writer.write("data: " + mapper.writeValueAsString(chunk) + "\n\n");
										    }
										    
										    // final chunk for regular response
										    Map<String, Object> finalChunk = new HashMap<>();
										    finalChunk.put("id", messageId);
										    finalChunk.put("object", "chat.completion.chunk");
										    finalChunk.put("created", creationTimestamp);
										    finalChunk.put("model", engineId);
										    
										    List<Map<String, Object>> finalChoices = new ArrayList<>();
										    Map<String, Object> finalChoice = new HashMap<>();
										    finalChoice.put("index", 0);
										    finalChoice.put("delta", new HashMap<>());
										    finalChoice.put("finish_reason", "stop");
										    
										    finalChoices.add(finalChoice);
										    finalChunk.put("choices", finalChoices);
										    
										    writer.write("data: " + mapper.writeValueAsString(finalChunk) + "\n\n");
										    writer.write("data: [DONE]\n\n");
										    writer.flush();
										}
										break;
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
								classLogger.error("Error in streaming response", e);
								throw new WebApplicationException(e, 500);
							} finally {
								if(jobId != null) {
									PixelJobManager.getManager().clearJob(jobId);
									PixelJobManager.getManager().removeJob(jobId);
								}
							}
						}
					}).build();
		}
	}

	/**
	 * Start an asynchronous model request and return the job ID
	 * @param engine
	 * @param insight
	 * @param dataMap
	 * @return
	 */
	private String startAsyncModelRequest(IModelEngine engine, Insight insight, Map<String, Object> dataMap, String sessionId) {
		try {
			// start async job
			PixelJobManager manager = PixelJobManager.getManager();
			PixelJobThread jt = manager.makeJob(insight, sessionId, null);
			String jobId = jt.getJobId();

			Gson gson = new GsonBuilder().disableHtmlEscaping().create();
			String modelPixel = "LLM(engine='"+engine.getEngineId()+"', command='<encode>ignore</encode>'"
					// this should have the full_prompt
					+ ",paramValues=["+gson.toJson(dataMap)+"]);";
			jt.addPixel(modelPixel);
			jt.start();
			return jobId;
		} catch (Exception e) {
			classLogger.warn("Failed to start async job");
			classLogger.error(Constants.STACKTRACE, e);
			throw new IllegalArgumentException(e.getMessage());
		}
	}

	@POST
	@Path("/completions")
	@Produces("application/json;charset=utf-8")
	public Response runModelCompletion(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		String sessionId = null;
		User user = null;
		Insight insight = null;
		ObjectMapper objectMapper = new ObjectMapper();

		if (session != null) {
			sessionId = session.getId();
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		}

		// how did you even get past the no user in session filter?
		if (user == null) {
			if(session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		// set the user timezone
		ZoneId zoneId = null;
		String strTz = request.getParameter("tz");
		if(strTz == null || (strTz=strTz.trim()).isEmpty()) {
			zoneId = ZoneId.of(Utility.getApplicationZoneId());
		} else {
			try {
				zoneId = ZoneId.of(strTz);
			} catch(Exception e) {
				classLogger.warn("Error parsing out users timezone value: " + strTz);
				classLogger.error(Constants.STACKTRACE, e);
				zoneId = ZoneId.of(Utility.getApplicationZoneId());
			}
		}
		// need null check if security is off
		if(user != null) {
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
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing.");
			return WebUtility.getResponse(errorMap, 400);
		}

		// Convert the JSON string to a Map
		TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(WebUtility.jsonSanitizer(requestData.toString()), mapType);
		} catch (JsonProcessingException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error processing JSON data: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing the required 'model' field.");
			return WebUtility.getResponse(errorMap, 400);
		}

		String question = (String) dataMap.remove("prompt");
		if (question == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Please provide 'prompt'.");
			return WebUtility.getResponse(errorMap, 400);
		}

		boolean isStreamingRequest = false;
		if (dataMap.containsKey("stream")) {
			isStreamingRequest = Boolean.parseBoolean(dataMap.get("stream").toString());
			dataMap.remove("stream");
		}
		
		if(!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Model " + engineId + " does not exist or user does not have access to this model");
			return WebUtility.getResponse(errorMap, 403);
		}

		String insightId = WebUtility.inputSanitizer((String) dataMap.remove("insight_id"));
		if (insightId == null) {
			Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(sessionId);
			if (sessionInsights == null || sessionInsights.isEmpty()) {
				// need to make a new insight here
				insight = new Insight();
				InsightStore.getInstance().put(insight);
				insightId = insight.getInsightId();
				InsightStore.getInstance().addToSessionHash(sessionId, insightId);
			} else {
				// pull the insight id from the session set
				insightId = sessionInsights.iterator().next();
				insight = InsightStore.getInstance().get(insightId);
			}		
		} else {
			insight = InsightStore.getInstance().get(insightId);
			InsightStore.getInstance().addToSessionHash(sessionId, insightId); // maybe its an insight id from another session?
		}

		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find the Insight with an Insight ID of " + insightId);
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}

		// set the user
		insight.setUser(user);		

		IModelEngine engine = Utility.getModel(engineId);
		
		if (!isStreamingRequest) {
			AskModelEngineResponse llmResponse;
			try {
				llmResponse = engine.ask(question, null, insight, dataMap);
			} catch (Exception e){
				classLogger.error(Constants.STACKTRACE, e);
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

			if (promptTokens!= null && responseTokens != null) {
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
			final String messageId = "chatcmpl-" + UUID.randomUUID().toString();
			final long creationTimestamp = Instant.now().getEpochSecond();

		    classLogger.info("Starting fake streaming response for model: " + engineId);
		    
			final Insight FINAL_INSIGHT = insight;
			return Response.ok().header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
					.header("Connection", "keep-alive").entity((StreamingOutput) output -> {
						ObjectMapper mapper = new ObjectMapper();
						try (Writer writer = new BufferedWriter(new OutputStreamWriter(output))){
							// Get full completion from your model in one go
							AskModelEngineResponse llmResponse = engine.ask(question, null, FINAL_INSIGHT, dataMap);
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
							if (promptTokens != null)
								usage.put("prompt_tokens", promptTokens);
							if (responseTokens != null)
								usage.put("completion_tokens", responseTokens);
							if (promptTokens != null && responseTokens != null) {
								usage.put("total_tokens", promptTokens + responseTokens);
							}
							chunk.put("usage", usage);

							writer.write("data: " + mapper.writeValueAsString(chunk) + "\n\n");
							writer.write("data: [DONE]\n\n");
							writer.flush();

						} catch (Exception e) {
							classLogger.error("Error in fake streaming response", e);
							throw new WebApplicationException(e, 500);
						}
					}).build();
		}
	}

	@POST
	@Path("/embeddings")
	@Produces("application/json;charset=utf-8")
	public Response runModelEmbeddings(@Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		String sessionId = null;
		User user = null;
		Insight insight = null;
		ObjectMapper objectMapper = new ObjectMapper();

		if (session != null) {
			sessionId = session.getId();
			user = ((User) session.getAttribute(Constants.SESSION_USER));
		}

		// how did you even get past the no user in session filter?
		if (user == null) {
			if(session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
				session.invalidate();
			}
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		// set the user timezone
		ZoneId zoneId = null;
		String strTz = request.getParameter("tz");
		if(strTz == null || (strTz=strTz.trim()).isEmpty()) {
			zoneId = ZoneId.of(Utility.getApplicationZoneId());
		} else {
			try {
				zoneId = ZoneId.of(strTz);
			} catch(Exception e) {
				classLogger.warn("Error parsing out users timezone value: " + strTz);
				classLogger.error(Constants.STACKTRACE, e);
				zoneId = ZoneId.of(Utility.getApplicationZoneId());
			}
		}
		// need null check if security is off
		if(user != null) {
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
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing.");
			return WebUtility.getResponse(errorMap, 400);
		}

		// Convert the JSON string to a Map
		TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(WebUtility.jsonSanitizer(requestData.toString()), mapType);
		} catch (JsonProcessingException e) {
			classLogger.error(Constants.STACKTRACE, e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error processing JSON data: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		String engineId = WebUtility.inputSanitizer((String) dataMap.remove("model"));
		if (engineId == null || engineId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing the required 'model' field.");
			return WebUtility.getResponse(errorMap, 400);
		}

		List<String> stringsToEncode = (List<String>) dataMap.remove("input");
		if (stringsToEncode == null || stringsToEncode.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing the required 'input' field.");
			return WebUtility.getResponse(errorMap, 400);
		}

		// make sure the user can view the engine
		if(!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Model " + engineId + " does not exist or user does not have access to this model");
			return WebUtility.getResponse(errorMap, 403);
		}

		String insightId = WebUtility.inputSanitizer((String) dataMap.remove("insight_id"));
		if (insightId == null) {
			Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(sessionId);
			if (sessionInsights == null || sessionInsights.isEmpty()) {
				// need to make a new insight here
				insight = new Insight();
				InsightStore.getInstance().put(insight);
				insightId = insight.getInsightId();
				InsightStore.getInstance().addToSessionHash(sessionId, insightId);
			} else {
				// pull the insight id from the session set
				insightId = sessionInsights.iterator().next();
				insight = InsightStore.getInstance().get(insightId);
			}		
		} else {
			insight = InsightStore.getInstance().get(insightId);
			InsightStore.getInstance().addToSessionHash(sessionId, insightId); // maybe its an insight id from another session?
		}

		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find the Insight with an Insight ID of " + insightId);
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}

		// set the user
		insight.setUser(user);		

		IModelEngine engine = Utility.getModel(engineId);
		EmbeddingsModelEngineResponse embeddingsResponse;
		try {
			embeddingsResponse = engine.embeddings(stringsToEncode, insight, dataMap);
		} catch (Exception e){
			classLogger.error(Constants.STACKTRACE, e);
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

		if (promptTokens!= null && responseTokens != null) {
			usage.put("prompt_tokens", promptTokens);
			usage.put("total_tokens", promptTokens + responseTokens);
		} else {
			usage.put("prompt_tokens", promptTokens);
		}

		embeddingsResponseMap.put("usage", usage);
		return WebUtility.getResponse(embeddingsResponseMap, 200);
	}
	
	@GET
	@Path("/models")
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
			if(session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
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
	@Path("/models/{modelId}")
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
			if(session != null && (session.isNew() || request.isRequestedSessionIdValid())) {
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
		if(openAiResponse == null || openAiResponse.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find model = '" + modelId + "'");
			return WebUtility.getResponse(errorMap, 400);
		}
		return WebUtility.getResponse(openAiResponse.get(0), 200);
	}
	
	/**
	 * Process the MyEngines output format to OpenAi format
	 * @param outputNoun
	 * @return
	 */
	private List<Map<String, Object>> processModelList(NounMetadata outputNoun) {
		List<Map<String, Object>> enginesList = (List<Map<String, Object>>) outputNoun.getValue();
		// we will convert our object to the openai spec
		List<Map<String, Object>> openAiResponse = new ArrayList<>(enginesList.size());
		for(Map<String, Object> engines : enginesList) {
			Map<String, Object> newMap = new HashMap<>();
			newMap.put("object", "model");
			newMap.put("id", engines.get("database_id"));
			newMap.put("alias", engines.get("database_name"));
			newMap.put("owned_by", engines.get("database_created_by"));
			SemossDate dateCreated = (SemossDate) engines.get("database_date_created");
			if(dateCreated != null) {
				ZonedDateTime zdt = dateCreated.getZonedDateTime();
				if(zdt != null) {
					newMap.put("created", zdt.toEpochSecond());
				}
			}
			openAiResponse.add(newMap);
		}
		return openAiResponse;
	}
	
	
}
