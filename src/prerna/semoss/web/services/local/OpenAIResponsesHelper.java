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

/**
 * Helper class for formatting OpenAI Responses API responses.
 * Simplified for raw passthrough mode - just forwards OpenAI events directly.
 */
public final class OpenAIResponsesHelper {

	private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.disableHtmlEscaping().create();

	private static ObjectMapper mapper = new ObjectMapper();

	/**
	 * Write SSE event directly from OpenAI without transformation.
	 * This serializes the event JSON and forwards it as-is.
	 *
	 * @param rawEvent The event dict from OpenAI (converted from Python)
	 * @param writer
	 * @throws IOException
	 * @throws JsonProcessingException
	 */
	public static void writeSSEEvent(Map<String, Object> rawEvent, Writer writer) throws JsonProcessingException, IOException {
	    String eventType = (String) rawEvent.get("type");
	    if (eventType != null) {
	        writer.write("event: " + eventType + "\n");
	    }
	    
	    String eventJson = mapper.writeValueAsString(rawEvent);
	    writer.write("data: " + eventJson + "\n\n");
	    writer.flush();
	}

	/**
	 * Process AskModelEngineResponse into native OpenAI Responses API format
	 * Used for non-streaming responses only.
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

		Map<String, Object> responsesMap = new HashMap<>();
		responsesMap.put("id", messageId);
		responsesMap.put("model", engineId);
		responsesMap.put("object", "response");

		long unixTimestamp = Instant.now().getEpochSecond();
		responsesMap.put("created_at", unixTimestamp);

		Map<String, Object> usage = new HashMap<>();
		if (promptTokens != null) {
			usage.put("input_tokens", promptTokens);
		}
		if (responseTokens != null) {
			usage.put("output_tokens", responseTokens);
		}
		if (promptTokens != null && responseTokens != null) {
			usage.put("total_tokens", promptTokens + responseTokens);
		}
		responsesMap.put("usage", usage);

		List<Map<String, Object>> output = new ArrayList<>();

		if (AskModelEngineResponse.TOOL.equals(llmResponse.getMessageType())) {
			AskToolModelEngineResponse toolResponse = (AskToolModelEngineResponse) llmResponse;
			List<ToolResponse> tools = toolResponse.getTools();

			for (ToolResponse t : tools) {
				Map<String, Object> functionCall = new HashMap<>();
				functionCall.put("type", "function_call");
				functionCall.put("call_id", t.getId());
				functionCall.put("name", t.getName());
				functionCall.put("arguments", GSON.toJson(t.getArguments()));
				output.add(functionCall);
			}

			responsesMap.put("status", "completed");
		} else {
			String response = llmResponse.getStringResponse();

			Map<String, Object> textOutput = new HashMap<>();
			textOutput.put("type", "text");
			textOutput.put("text", response);
			output.add(textOutput);

			responsesMap.put("status", "completed");
		}

		responsesMap.put("output", output);

		if (llmResponse.getThinking() != null && !llmResponse.getThinking().isEmpty()) {
			Map<String, Object> reasoning = new HashMap<>();
			reasoning.put("type", "reasoning");
			List<Map<String, Object>> summaries = new ArrayList<>();
			Map<String, Object> summary = new HashMap<>();
			summary.put("text", llmResponse.getThinking());
			summaries.add(summary);
			reasoning.put("summary", summaries);
			output.add(reasoning);
		}

		return responsesMap;
	}

	private OpenAIResponsesHelper() {
	}
}