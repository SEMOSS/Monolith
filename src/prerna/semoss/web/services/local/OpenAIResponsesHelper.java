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
 * This is a parallel implementation to OpenAIChatCompletionsHelper but for the /v1/responses endpoint.
 *
 * CRITICAL: The Responses API has a different response schema than Chat Completions.
 * We must return native OpenAI Responses API format for Codex compatibility.
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
	public static void writeSSEEvent(Map<String, Object> rawEvent, Writer writer)
			throws JsonProcessingException, IOException {
		String eventJson = mapper.writeValueAsString(rawEvent);
		writer.write("data: " + eventJson + "\n\n");
		writer.flush();
	}

	/**
	 * Write finish reason for streaming responses
	 * For Responses API, we use different event structure than Chat Completions
	 *
	 * @param engineId
	 * @param messageId
	 * @param creationTimestamp
	 * @param finishReason
	 * @param writer
	 * @throws IOException
	 * @throws JsonProcessingException
	 */
	public static void writeFinishReason(String engineId, String messageId, long creationTimestamp, String finishReason,
			Writer writer) throws JsonProcessingException, IOException {

		// Send response.output_text.done event (OpenAI sends this before response.completed)
		Map<String, Object> textDoneEvent = new HashMap<>();
		textDoneEvent.put("type", "response.output_text.done");
		textDoneEvent.put("content_index", 0);
		textDoneEvent.put("item_id", messageId);
		textDoneEvent.put("output_index", 0);
		textDoneEvent.put("sequence_number", chunkSequenceNumber++);
		writer.write("data: " + mapper.writeValueAsString(textDoneEvent) + "\n\n");

		// Send response.content_part.done event
		Map<String, Object> contentPartDoneEvent = new HashMap<>();
		contentPartDoneEvent.put("type", "response.content_part.done");
		contentPartDoneEvent.put("content_index", 0);
		contentPartDoneEvent.put("item_id", messageId);
		contentPartDoneEvent.put("output_index", 0);
		contentPartDoneEvent.put("sequence_number", chunkSequenceNumber++);
		writer.write("data: " + mapper.writeValueAsString(contentPartDoneEvent) + "\n\n");

		// Send response.output_item.done event
		Map<String, Object> outputItemDoneEvent = new HashMap<>();
		outputItemDoneEvent.put("type", "response.output_item.done");
		outputItemDoneEvent.put("output_index", 0);
		outputItemDoneEvent.put("sequence_number", chunkSequenceNumber++);
		writer.write("data: " + mapper.writeValueAsString(outputItemDoneEvent) + "\n\n");

		// Reset sequence number for next request
		chunkSequenceNumber = 0;

		// For Responses API streaming, send completion event matching OpenAI's format
		Map<String, Object> completionEvent = new HashMap<>();
		completionEvent.put("type", "response.completed");
		completionEvent.put("sequence_number", chunkSequenceNumber);

		// Build the response object to match OpenAI's Response structure
		Map<String, Object> response = new HashMap<>();
		response.put("id", messageId);
		response.put("object", "response");
		response.put("status", finishReason);
		response.put("created_at", creationTimestamp);
		response.put("model", engineId);

		completionEvent.put("response", response);

		String completionJson = mapper.writeValueAsString(completionEvent);
		System.out.println("[RESPONSES-WRITE-FINISH] Writing completion event: " + completionJson);
		writer.write("data: " + completionJson + "\n\n");
		writer.write("data: [DONE]\n\n");
		writer.flush();
		System.out.println("[RESPONSES-WRITE-FINISH] Flushed completion and [DONE] to stream");
	}

	/**
	 * Write content chunk for streaming text responses
	 * Responses API uses different delta format
	 *
	 * @param engineId
	 * @param messageId
	 * @param creationTimestamp
	 * @param newContent
	 * @param firstChunk
	 * @param writer
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	private static int chunkSequenceNumber = 0;

	public static void writeContentChunk(String engineId, String messageId, long creationTimestamp, String newContent,
			boolean firstChunk, Writer writer) throws JsonProcessingException, IOException {

		// Match actual OpenAI Responses API streaming format exactly
		// Based on: ResponseTextDeltaEvent(content_index=0, delta='...', item_id='msg_...', output_index=0, sequence_number=..., type='response.output_text.delta')
		Map<String, Object> event = new HashMap<>();
		event.put("type", "response.output_text.delta");
		event.put("delta", newContent);
		event.put("content_index", 0);
		event.put("item_id", messageId);
		event.put("output_index", 0);
		event.put("sequence_number", chunkSequenceNumber++);

		// sending chunk as SSE event
		String eventJson = mapper.writeValueAsString(event);
		System.out.println("[RESPONSES-WRITE-CHUNK] Writing content chunk (length=" + newContent.length() + "): " + newContent.substring(0, Math.min(50, newContent.length())));
		writer.write("data: " + eventJson + "\n\n");
		writer.flush();
	}

	/**
	 * Write thinking/reasoning chunk for streaming responses
	 * This is specific to Responses API which supports reasoning content
	 *
	 * @param engineId
	 * @param messageId
	 * @param creationTimestamp
	 * @param thinkingContent
	 * @param firstChunk
	 * @param writer
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	public static void writeThinkingChunk(String engineId, String messageId, long creationTimestamp, String thinkingContent,
			boolean firstChunk, Writer writer) throws JsonProcessingException, IOException {

		// Responses API reasoning/thinking format
		Map<String, Object> chunk = new HashMap<>();
		chunk.put("type", "response.reasoning_summary_text.delta");
		chunk.put("delta", thinkingContent);
		chunk.put("response_id", messageId);

		// sending chunk as SSE event
		writer.write("data: " + mapper.writeValueAsString(chunk) + "\n\n");
		writer.flush();
	}

	/**
	 * Write tool/function call chunk for streaming
	 *
	 * @param engineId
	 * @param messageId
	 * @param creationTimestamp
	 * @param dataMap
	 * @param firstChunk
	 * @param writer
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	public static void writeToolChunk(String engineId, String messageId, long creationTimestamp,
			Map<String, Object> dataMap, boolean firstChunk, Writer writer)
			throws JsonProcessingException, IOException {

		Long curToolIndex = ((Number) dataMap.get("index")).longValue();

		// Responses API function call streaming format
		Map<String, Object> chunk = new HashMap<>();
		chunk.put("type", "response.output_item.done");
		chunk.put("output_index", curToolIndex);
		chunk.put("response_id", messageId);

		Map<String, Object> item = new HashMap<>();
		item.put("type", "function_call");

		if (dataMap.containsKey("id")) {
			item.put("id", dataMap.get("id"));
		}
		if (dataMap.containsKey("name")) {
			item.put("name", dataMap.get("name"));
		}
		if (dataMap.containsKey("arguments")) {
			item.put("arguments", dataMap.get("arguments"));
		}

		chunk.put("item", item);

		// sending chunk as SSE event
		writer.write("data: " + mapper.writeValueAsString(chunk) + "\n\n");
		writer.flush();
	}

	/**
	 * Write full tool response as chunks (for non-streamed tools)
	 *
	 * @param engineId
	 * @param messageId
	 * @param creationTimestamp
	 * @param toolsResponseList
	 * @param writer
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	public static void writeFullToolResponseAsChunk(String engineId, String messageId, long creationTimestamp,
			List<Map<String, Object>> toolsResponseList, Writer writer) throws JsonProcessingException, IOException {

		// there might be multiple tools
		// loop through and send each one as a chunk
		long index = 0;
		for (Map<String, Object> toolResponseMap : toolsResponseList) {
			Map<String, Object> dataMap = new HashMap<>();
			dataMap.put("index", index);
			dataMap.put("id", toolResponseMap.get(AskToolModelEngineResponse.ID_KEY));
			dataMap.put("name", toolResponseMap.get(AskToolModelEngineResponse.NAME_KEY));
			dataMap.put("arguments", toolResponseMap.get(AskToolModelEngineResponse.ARGUMENTS_KEY));

			writeToolChunk(engineId, messageId, creationTimestamp, dataMap, true, writer);
			index++;
		}
	}

	/**
	 * Process AskModelEngineResponse into native OpenAI Responses API format
	 * IMPORTANT: Unlike Chat Completions, we return the NATIVE Responses API schema
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

		// Get the number of seconds since the epoch
		long unixTimestamp = Instant.now().getEpochSecond();
		responsesMap.put("created_at", unixTimestamp);

		// usage object - Responses API uses different field names
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

		// Build output array
		List<Map<String, Object>> output = new ArrayList<>();

		// Handle tool calls
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
			// Regular text response
			String response = llmResponse.getStringResponse();

			Map<String, Object> textOutput = new HashMap<>();
			textOutput.put("type", "text");
			textOutput.put("text", response);
			output.add(textOutput);

			responsesMap.put("status", "completed");
		}

		responsesMap.put("output", output);

		// Add thinking/reasoning if present
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
		// Private constructor to prevent instantiation
	}
}
