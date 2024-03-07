package prerna.semoss.web.services.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.engine.api.IModelEngine;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/model/openai")
public class OpenAIEndpoints {

	private static final Logger classLogger = LogManager.getLogger(NameServer.class);

	private static final String ERROR_TYPE = "errorType";
	private static final String INSIGHT_NOT_FOUND = "INSIGHT_NOT_FOUND";
	
	@POST
	@Path("/chat/completions")
	@Produces("application/json;charset=utf-8")
	public Response runModelChatCompletion(@Context HttpServletRequest request) {
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
		TimeZone tz = null;
		String strTz = request.getParameter("tz");
		if(strTz == null || (strTz=strTz.trim()).isEmpty()) {
			tz = TimeZone.getTimeZone(Utility.getApplicationTimeZoneId());
		} else {
			try {
				tz = TimeZone.getTimeZone(strTz);
			} catch(Exception e) {
				classLogger.warn("Error parsing out users timezone value: " + strTz);
				classLogger.error(Constants.STACKTRACE, e);
				tz = TimeZone.getTimeZone(Utility.getApplicationTimeZoneId());
			}
		}
		// need null check if security is off
		if(user != null) {
			user.setTimeZone(tz);
		}
	    
	    // Retrieve raw data from the request
        StringBuilder requestData = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
            String line;
            
			while ((line = reader.readLine()) != null) {
			    requestData.append(line);
			}
			
        } catch (IOException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing.");
			return WebUtility.getResponse(errorMap, 400);
		}
        
        // Convert the JSON string to a Map
        TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(requestData.toString(), mapType);
		} catch (JsonProcessingException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error processing JSON data: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
        
        String insightId = (String) dataMap.remove("insight_id");
        if (insightId == null || insightId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing the required 'insight_id' field.");
			return WebUtility.getResponse(errorMap, 400);
        }
        
        String engineId = (String) dataMap.remove("model");
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
        
        dataMap.put("full_prompt", fullPrompt);

		insight = InsightStore.getInstance().get(insightId);
		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find the Insight with an Insight ID of " + insightId);
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
	
		InsightStore.getInstance().addToSessionHash(sessionId, insightId);
		// set the user
		insight.setUser(user);
		
		IModelEngine engine = Utility.getModel(engineId);
        
        Map<String, Object> llmResponseMap;
        try {
        	llmResponseMap = engine.ask(engineId, null, insight, dataMap).toMap();
        } catch (Exception e){
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
        }
        
        Object response = llmResponseMap.remove("response");
        Object messageId = llmResponseMap.remove("messageId");
        Integer promptTokens = getTokensAsInt(llmResponseMap.remove("numberOfTokensInPrompt"));
        Integer responseTokens = getTokensAsInt(llmResponseMap.remove("numberOfTokensInResponse"));
        
        // empty the map
        llmResponseMap.remove("roomId");

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
		TimeZone tz = null;
		String strTz = request.getParameter("tz");
		if(strTz == null || (strTz=strTz.trim()).isEmpty()) {
			tz = TimeZone.getTimeZone(Utility.getApplicationTimeZoneId());
		} else {
			try {
				tz = TimeZone.getTimeZone(strTz);
			} catch(Exception e) {
				classLogger.warn("Error parsing out users timezone value: " + strTz);
				classLogger.error(Constants.STACKTRACE, e);
				tz = TimeZone.getTimeZone(Utility.getApplicationTimeZoneId());
			}
		}
		// need null check if security is off
		if(user != null) {
			user.setTimeZone(tz);
		}
	    
	    // Retrieve raw data from the request
        StringBuilder requestData = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
            String line;
            
			while ((line = reader.readLine()) != null) {
			    requestData.append(line);
			}
			
        } catch (IOException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing.");
			return WebUtility.getResponse(errorMap, 400);
		}
        
        // Convert the JSON string to a Map
        TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(requestData.toString(), mapType);
		} catch (JsonProcessingException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error processing JSON data: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
        
        String insightId = (String) dataMap.remove("insight_id");
        if (insightId == null || insightId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing the required 'insight_id' field.");
			return WebUtility.getResponse(errorMap, 400);
        }
        
        String engineId = (String) dataMap.remove("model");
        if (insightId == null || insightId.isEmpty()) {
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
        
        if(!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Model " + engineId + " does not exist or user does not have access to this model");
			return WebUtility.getResponse(errorMap, 403);
		}

		insight = InsightStore.getInstance().get(insightId);
		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find the Insight with an Insight ID of " + insightId);
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
	
		InsightStore.getInstance().addToSessionHash(sessionId, insightId);
		// set the user
		insight.setUser(user);
		
		IModelEngine engine = Utility.getModel(engineId);
        
        Map<String, Object> llmResponseMap;
        try {
        	llmResponseMap = engine.ask(question, null, insight, dataMap).toMap();
        } catch (Exception e){
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
        }
        
        Object response = llmResponseMap.remove("response");
        Object messageId = llmResponseMap.remove("messageId");
        Integer promptTokens = getTokensAsInt(llmResponseMap.remove("numberOfTokensInPrompt"));
        Integer responseTokens = getTokensAsInt(llmResponseMap.remove("numberOfTokensInResponse"));
        
        // empty the map
        llmResponseMap.remove("roomId");

        // "choices" array
        List<Map<String, Object>> choicesList = new ArrayList<>();
        Map<String, Object> choice = new HashMap<>();
        choice.put("finish_reason", "stop");
        choice.put("index", 0);
        choice.put("logprobs", null);
        choice.put("text", response);

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
        llmResponseMap.put("object", "text_completion");

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
		TimeZone tz = null;
		String strTz = request.getParameter("tz");
		if(strTz == null || (strTz=strTz.trim()).isEmpty()) {
			tz = TimeZone.getTimeZone(Utility.getApplicationTimeZoneId());
		} else {
			try {
				tz = TimeZone.getTimeZone(strTz);
			} catch(Exception e) {
				classLogger.warn("Error parsing out users timezone value: " + strTz);
				classLogger.error(Constants.STACKTRACE, e);
				tz = TimeZone.getTimeZone(Utility.getApplicationTimeZoneId());
			}
		}
		// need null check if security is off
		if(user != null) {
			user.setTimeZone(tz);
		}
	    
	    // Retrieve raw data from the request
        StringBuilder requestData = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()))) {
            String line;
            
			while ((line = reader.readLine()) != null) {
			    requestData.append(line);
			}
			
        } catch (IOException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing.");
			return WebUtility.getResponse(errorMap, 400);
		}
        
        // Convert the JSON string to a Map
        TypeReference<Map<String, Object>> mapType = new TypeReference<Map<String, Object>>() {};
		Map<String, Object> dataMap;
		try {
			dataMap = objectMapper.readValue(requestData.toString(), mapType);
		} catch (JsonProcessingException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Error processing JSON data: " + e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}
        
        String insightId = (String) dataMap.remove("insight_id");
        if (insightId == null || insightId.isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Bad Request: The 'data' parameter is missing the required 'insight_id' field.");
			return WebUtility.getResponse(errorMap, 400);
        }
        
        String engineId = (String) dataMap.remove("model");
        if (insightId == null || insightId.isEmpty()) {
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
        
		insight = InsightStore.getInstance().get(insightId);
		if (insight == null) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Could not find the Insight with an Insight ID of " + insightId);
			errorMap.put(ERROR_TYPE, INSIGHT_NOT_FOUND);
			return WebUtility.getResponse(errorMap, 400);
		}
	
		InsightStore.getInstance().addToSessionHash(sessionId, insightId);
		// set the user
		insight.setUser(user);
		
		IModelEngine engine = Utility.getModel(engineId);
        Map<String, Object> embeddingsResponse;
		try {
			embeddingsResponse = (Map<String, Object>) engine.embeddings(stringsToEncode, insight, dataMap);
        } catch (Exception e){
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
        }
        
        List<Object> embeddings = (List<Object>) embeddingsResponse.remove("response");
        Integer promptTokens = getTokensAsInt(embeddingsResponse.remove("numberOfTokensInPrompt"));
        Integer responseTokens = getTokensAsInt(embeddingsResponse.remove("numberOfTokensInResponse"));
        
        // "choices" array
        List<Map<String, Object>> dataList = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            Map<String, Object> embeddingMap = new HashMap<>();
            embeddingMap.put("embedding", embeddings.get(i));
            embeddingMap.put("index", i);
            embeddingMap.put("object", "embedding");

            dataList.add(embeddingMap);
        }

        embeddingsResponse.put("data", dataList);
        
        embeddingsResponse.put("model", engineId);
        embeddingsResponse.put("object", "list");

        // "usage" object
        Map<String, Object> usage = new HashMap<>();

        usage.put("prompt_tokens", promptTokens);
        usage.put("total_tokens", promptTokens + responseTokens);

        embeddingsResponse.put("usage", usage);
        
		return WebUtility.getResponse(embeddingsResponse, 200);
	}
	
	private Integer getTokensAsInt(Object numTokens) {
		if (numTokens instanceof Integer) {
			return (Integer) numTokens;
		} else if (numTokens instanceof Long) {
			return ((Long) numTokens).intValue();
		} else if (numTokens instanceof Double) {
			return ((Double) numTokens).intValue();
		} else if (numTokens instanceof String){
			return Integer.valueOf((String) numTokens);
		} else {
			return null;
		}
	}
}
