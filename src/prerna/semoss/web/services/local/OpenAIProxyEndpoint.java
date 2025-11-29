package prerna.semoss.web.services.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityUserAccessKeyUtils;
import prerna.engine.api.IModelEngine;
import prerna.util.Utility;

/**
 * OpenAI Transparent Proxy Endpoint
 *
 * This endpoint acts as a transparent proxy between Codex (or any OpenAI-compatible client)
 * and OpenAI's API. It:
 *
 * 1. Accepts the Authorization header with Base64-encoded access_key:secret_key (User Access Keys)
 * 2. Validates credentials and retrieves the authenticated User
 * 3. Checks if the User has access to the requested engine
 * 4. Extracts the OpenAI API key from the engine configuration
 * 5. Forwards the request to OpenAI API without modification
 * 6. Returns OpenAI's response without modification
 *
 * This maintains full compatibility with OpenAI's API while adding SEMOSS authentication.
 *
 * To create User Access Keys:
 * - Login to SEMOSS
 * - POST /api/auth/user/createUserAccessKey with tokenName parameter
 * - Use the returned access_key and secret_key
 */
@Path("/openai/proxy")
public class OpenAIProxyEndpoint {

	private static final Logger classLogger = LogManager.getLogger(OpenAIProxyEndpoint.class);
	private static final ObjectMapper objectMapper = new ObjectMapper();

	private static final String OPENAI_BASE_URL = "https://api.openai.com";
	private static final int BUFFER_SIZE = 8192;

	/**
	 * Test endpoint to verify proxy is accessible
	 */
	@GET
	@Path("/health")
	@Produces(MediaType.APPLICATION_JSON)
	public Response health() {
		Map<String, Object> response = new HashMap<>();
		response.put("status", "ok");
		response.put("service", "openai-proxy");
		response.put("version", "1.0");
		response.put("timestamp", System.currentTimeMillis());
		try {
			return Response.ok()
					.entity(objectMapper.writeValueAsString(response))
					.type(MediaType.APPLICATION_JSON)
					.header("X-SEMOSS-Proxy", "OpenAI-Proxy-v1")
					.build();
		} catch (Exception e) {
			return Response.ok("{\"status\":\"ok\"}").build();
		}
	}

	/**
	 * Proxy endpoint for /v1/chat/completions
	 * Transparently forwards requests to OpenAI while handling SEMOSS authentication
	 */
	@POST
	@Path("/v1/chat/completions")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response proxyChatCompletions(@Context HttpServletRequest request) {
		return proxyRequest(request, "/v1/chat/completions");
	}

	/**
	 * Proxy endpoint for /v1/completions
	 * Transparently forwards requests to OpenAI while handling SEMOSS authentication
	 */
	@POST
	@Path("/v1/completions")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response proxyCompletions(@Context HttpServletRequest request) {
		return proxyRequest(request, "/v1/completions");
	}

	/**
	 * Proxy endpoint for /v1/embeddings
	 * Transparently forwards requests to OpenAI while handling SEMOSS authentication
	 */
	@POST
	@Path("/v1/embeddings")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response proxyEmbeddings(@Context HttpServletRequest request) {
		return proxyRequest(request, "/v1/embeddings");
	}

	/**
	 * Core proxy logic that handles all requests
	 *
	 * @param request The incoming HTTP request
	 * @param endpoint The OpenAI endpoint to proxy to (e.g., "/v1/chat/completions")
	 * @return Response with OpenAI's response or error
	 */
	private Response proxyRequest(HttpServletRequest request, String endpoint) {
		try {
			// Step 1: Extract and validate Authorization header
			String authHeader = request.getHeader("Authorization");
			if (authHeader == null) {
				authHeader = request.getHeader("authorization");
			}

			if (authHeader == null || authHeader.trim().isEmpty()) {
				return errorResponse("Missing Authorization header", 401);
			}

		// Decode credentials from Authorization header
		AuthCredentials credentials = extractCredentials(authHeader);
		if (credentials == null) {
			return errorResponse("Invalid Authorization header format. Expected 'Bearer base64(access_key:secret_key)'", 401);
		}

		// Step 3: Validate User Access Key credentials and get User object
		classLogger.info("=== AUTHENTICATION START ===");
		classLogger.info("Validating access_key: {}", credentials.clientId);

		User user = null;
		try {
			// Validate User Access Key and get the authenticated User
			// This uses SecurityUserAccessKeyUtils.validateKeysAndReturnUser()
			// which is the same method used by OpenAIFilter
			user = SecurityUserAccessKeyUtils.validateKeysAndReturnUser(credentials.clientId, credentials.secretKey);
			classLogger.info("Successfully authenticated access_key: {}", credentials.clientId);
			classLogger.info("User: {}", user.getPrimaryLoginToken().getId());
		} catch (IllegalAccessException e) {
			classLogger.warn("Invalid User Access Key credentials for access_key: {}", credentials.clientId);
			classLogger.error("===========================================");
			classLogger.error("AUTHENTICATION FAILED");
			classLogger.error("Access_key: {}", credentials.clientId);
			classLogger.error("===========================================");
			classLogger.error("Possible issues:");
			classLogger.error("1. User Access Key does not exist");
			classLogger.error("2. Wrong access_key or secret_key");
			classLogger.error("3. Credentials were not created via /api/auth/user/createUserAccessKey");
			classLogger.error("===========================================");
			classLogger.error("To create User Access Keys:");
			classLogger.error("1. Login to SEMOSS");
			classLogger.error("2. POST /api/auth/user/createUserAccessKey with tokenName parameter");
			classLogger.error("3. Use the returned access_key and secret_key");
			classLogger.error("===========================================");
			return errorResponse("Invalid credentials. User Access Key not found or credentials are incorrect.", 401);
		}

		// Step 4: Read request body
			String requestBody = readRequestBody(request);
			if (requestBody == null || requestBody.isEmpty()) {
				return errorResponse("Missing request body", 400);
			}

		// Step 5: Extract model/engine ID from request
		Map<String, Object> requestJson = parseJson(requestBody);
		String engineId = (String) requestJson.get("model");
		if (engineId == null || engineId.isEmpty()) {
			return errorResponse("Missing 'model' field in request", 400);
		}

		classLogger.info("Processing request for engine: {}", engineId);

		// Step 6: Check if user has access to the engine
		if (!SecurityEngineUtils.userCanViewEngine(user, engineId)) {
			classLogger.warn("User {} does not have access to engine: {}", credentials.clientId, engineId);
			return errorResponse("Model " + engineId + " does not exist or user does not have access to this model", 403);
		}

		classLogger.info("User has access to engine: {}", engineId);

		// Step 7: Get engine configuration (API key and model name)
		Map<String, String> openAiConfig = getOpenAiConfigFromEngine(engineId);
		if (openAiConfig == null) {
			return errorResponse("Could not retrieve OpenAI configuration for engine: " + engineId, 500);
		}

		String openAiApiKey = openAiConfig.get("apiKey");
		String openAiModelName = openAiConfig.get("modelName");

		// Step 8: Replace the SEMOSS engine ID with the actual OpenAI model name in the request
		requestJson.put("model", openAiModelName);
		classLogger.info("Replaced engine ID {} with OpenAI model name: {}", engineId, openAiModelName);

		// Convert back to JSON string with the updated model name
		try {
			requestBody = objectMapper.writeValueAsString(requestJson);
			classLogger.info("Updated request body with OpenAI model name");
		} catch (Exception e) {
			classLogger.error("Error updating request body", e);
			return errorResponse("Error preparing request for OpenAI", 500);
		}

		// Step 9: Determine if streaming is requested
		boolean isStreaming = false;
		if (requestJson.containsKey("stream")) {
			Object streamValue = requestJson.get("stream");
			isStreaming = Boolean.TRUE.equals(streamValue) || "true".equals(String.valueOf(streamValue));
		}

		// Step 10: Proxy to OpenAI
			if (isStreaming) {
				return proxyStreamingRequest(endpoint, requestBody, openAiApiKey);
			} else {
				return proxyNonStreamingRequest(endpoint, requestBody, openAiApiKey);
			}

		} catch (Exception e) {
			classLogger.error("Error in proxy request", e);
			return errorResponse("Internal server error: " + e.getMessage(), 500);
		}
	}

	/**
	 * Extract access_key and secret_key from Authorization header
	 * Supports both "Bearer base64(access_key:secret_key)" and "Basic base64(access_key:secret_key)"
	 * Also handles tokens with "sk-" prefix (e.g., "sk-base64encoded")
	 */
	private AuthCredentials extractCredentials(String authHeader) {
		try {
			String encodedCredentials;

		if (authHeader.startsWith("Bearer ")) {
			encodedCredentials = authHeader.substring(7).trim();
		} else if (authHeader.startsWith("Basic ")) {
			encodedCredentials = authHeader.substring(6).trim();
		} else {
			// Try to decode as-is
			encodedCredentials = authHeader.trim();
		}

		// Remove "sk-" prefix if present (OpenAI convention)
		if (encodedCredentials.startsWith("sk-")) {
			encodedCredentials = encodedCredentials.substring(3);
		}

		// Decode from Base64
			byte[] decodedBytes = Base64.getDecoder().decode(encodedCredentials);
			String decoded = new String(decodedBytes, StandardCharsets.UTF_8);

			// Split into client_id:secret_key
			String[] parts = decoded.split(":", 2);
			if (parts.length != 2) {
				classLogger.warn("Invalid credential format after decoding: {}", decoded);
				return null;
			}

			AuthCredentials creds = new AuthCredentials();
			// UUIDs are safe - no need to sanitize
			creds.clientId = parts[0].trim();
			creds.secretKey = parts[1].trim();

			classLogger.info("=== CREDENTIAL DEBUG ===");
			classLogger.info("Client_id: {}", creds.clientId);
			classLogger.info("Secret_key length: {}", creds.secretKey.length());
			classLogger.info("========================");

			return creds;

		} catch (Exception e) {
			classLogger.error("Error extracting credentials", e);
			return null;
		}
	}

	/**
	 * Get OpenAI API key and model name from engine configuration
	 * Returns a map with "apiKey" and "modelName"
	 */
	private Map<String, String> getOpenAiConfigFromEngine(String engineId) {
		try {
			IModelEngine engine = Utility.getModel(engineId);
			if (engine == null) {
				classLogger.warn("Engine not found: {}", engineId);
				return null;
			}

			// Get the OpenAI API key from engine's SMSS file (returns Properties)
			java.util.Properties engineProps = engine.getSmssProp();

			// Try different possible key names (matching AbstractModelEngine.OPEN_AI_KEY)
			String apiKey = engineProps.getProperty("OPEN_AI_KEY");
			if (apiKey == null) {
				apiKey = engineProps.getProperty("OPENAI_KEY");
			}
			if (apiKey == null) {
				apiKey = engineProps.getProperty("openaiKey");
			}
			if (apiKey == null) {
				apiKey = engineProps.getProperty("api_key");
			}
			if (apiKey == null) {
				apiKey = engineProps.getProperty("apiKey");
			}

			// Get the actual OpenAI model name (like gpt-4, gpt-3.5-turbo, etc.)
			String modelName = engineProps.getProperty("MODEL");
			if (modelName == null) {
				modelName = engineProps.getProperty("model");
			}
			if (modelName == null) {
				modelName = engineProps.getProperty("MODEL_NAME");
			}
			if (modelName == null) {
				modelName = engineProps.getProperty("modelName");
			}

			classLogger.info("Checking engine properties for OpenAI configuration...");
			classLogger.info("OPEN_AI_KEY present: {}", engineProps.getProperty("OPEN_AI_KEY") != null);
			classLogger.info("MODEL present: {}", engineProps.getProperty("MODEL") != null);

			if (apiKey == null || apiKey.isEmpty()) {
				classLogger.error("No OpenAI API key found in engine configuration for: {}", engineId);
				return null;
			}

			if (modelName == null || modelName.isEmpty()) {
				classLogger.error("No MODEL name found in engine configuration for: {}", engineId);
				return null;
			}

			classLogger.info("Successfully retrieved OpenAI configuration for engine: {}", engineId);
			classLogger.info("API key starts with: {}", apiKey.substring(0, Math.min(7, apiKey.length())));
			classLogger.info("Model name: {}", modelName);

			Map<String, String> config = new HashMap<>();
			config.put("apiKey", apiKey);
			config.put("modelName", modelName);
			return config;

		} catch (Exception e) {
			classLogger.error("Error retrieving OpenAI configuration from engine: {}", engineId, e);
			return null;
		}
	}

	/**
	 * Proxy a non-streaming request to OpenAI
	 */
	private Response proxyNonStreamingRequest(String endpoint, String requestBody, String openAiApiKey) {
		HttpURLConnection connection = null;
		try {
			classLogger.info("=== PROXYING TO OPENAI ===");
			classLogger.info("Endpoint: {}{}", OPENAI_BASE_URL, endpoint);
			classLogger.info("Request body length: {}", requestBody.length());

			// Create connection to OpenAI
			URL url = new URL(OPENAI_BASE_URL + endpoint);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("Authorization", "Bearer " + openAiApiKey);
			connection.setDoOutput(true);
			connection.setDoInput(true);

			// Send request
			try (OutputStream os = connection.getOutputStream()) {
				byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
				os.write(input, 0, input.length);
			}

			classLogger.info("Request sent to OpenAI, waiting for response...");

			// Read response
			int statusCode = connection.getResponseCode();
			classLogger.info("OpenAI response status code: {}", statusCode);

			String responseBody;

			if (statusCode >= 200 && statusCode < 300) {
				responseBody = readStream(connection.getInputStream());
				classLogger.info("OpenAI success response length: {}", responseBody.length());
				classLogger.info("OpenAI response preview: {}", responseBody.substring(0, Math.min(200, responseBody.length())));
			} else {
				responseBody = readStream(connection.getErrorStream());
				classLogger.error("OpenAI error response: {}", responseBody);
			}

		// Return OpenAI's response as-is with explicit headers to prevent redirects
		classLogger.info("Returning response to client with status: {}", statusCode);
		return Response.status(statusCode)
				.entity(responseBody)
				.type(MediaType.APPLICATION_JSON)
				.header("Content-Type", "application/json; charset=utf-8")
				.header("Cache-Control", "no-cache, no-store, must-revalidate")
				.header("X-Content-Type-Options", "nosniff")
				.header("X-SEMOSS-Proxy", "OpenAI-Proxy-v1")
				.build();

		} catch (Exception e) {
			classLogger.error("Error proxying request to OpenAI", e);
			return errorResponse("Error connecting to OpenAI: " + e.getMessage(), 500);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * Proxy a streaming request to OpenAI
	 */
	private Response proxyStreamingRequest(String endpoint, String requestBody, String openAiApiKey) {
		StreamingOutput stream = new StreamingOutput() {
			@Override
			public void write(OutputStream output) throws IOException {
				HttpURLConnection connection = null;
				try {
					// Create connection to OpenAI
					URL url = new URL(OPENAI_BASE_URL + endpoint);
					connection = (HttpURLConnection) url.openConnection();
					connection.setRequestMethod("POST");
					connection.setRequestProperty("Content-Type", "application/json");
					connection.setRequestProperty("Authorization", "Bearer " + openAiApiKey);
					connection.setDoOutput(true);
					connection.setDoInput(true);

					// Send request
					try (OutputStream os = connection.getOutputStream()) {
						byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
						os.write(input, 0, input.length);
					}

					// Stream response
					int statusCode = connection.getResponseCode();

					if (statusCode >= 200 && statusCode < 300) {
						try (InputStream is = connection.getInputStream()) {
							byte[] buffer = new byte[BUFFER_SIZE];
							int bytesRead;
							while ((bytesRead = is.read(buffer)) != -1) {
								output.write(buffer, 0, bytesRead);
								output.flush();
							}
						}
					} else {
						// Error response
						String errorBody = readStream(connection.getErrorStream());
						output.write(errorBody.getBytes(StandardCharsets.UTF_8));
						output.flush();
					}

				} catch (Exception e) {
					classLogger.error("Error in streaming proxy", e);
					String errorJson = "{\"error\": {\"message\": \"" + e.getMessage() + "\", \"type\": \"proxy_error\"}}";
					output.write(errorJson.getBytes(StandardCharsets.UTF_8));
					output.flush();
				} finally {
					if (connection != null) {
						connection.disconnect();
					}
				}
			}
		};

		return Response.ok()
				.entity(stream)
				.header("Content-Type", "text/event-stream")
				.header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive")
				.build();
	}

	/**
	 * Read request body from HttpServletRequest
	 */
	private String readRequestBody(HttpServletRequest request) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
		}
		return sb.toString();
	}

	/**
	 * Read entire stream into string
	 */
	private String readStream(InputStream is) throws IOException {
		if (is == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
		}
		return sb.toString();
	}

	/**
	 * Parse JSON string into Map
	 */
	private Map<String, Object> parseJson(String json) {
		try {
			TypeReference<Map<String, Object>> typeRef = new TypeReference<Map<String, Object>>() {};
			return objectMapper.readValue(json, typeRef);
		} catch (Exception e) {
			classLogger.error("Error parsing JSON", e);
			return new HashMap<>();
		}
	}

	/**
	 * Create error response
	 */
	private Response errorResponse(String message, int statusCode) {
		Map<String, Object> errorMap = new HashMap<>();
		Map<String, Object> error = new HashMap<>();
		error.put("message", message);
		error.put("type", "invalid_request_error");
		errorMap.put("error", error);

		try {
			String json = objectMapper.writeValueAsString(errorMap);
			return Response.status(statusCode)
					.entity(json)
					.type(MediaType.APPLICATION_JSON)
					.build();
		} catch (Exception e) {
			return Response.status(statusCode)
					.entity("{\"error\": {\"message\": \"" + message + "\"}}")
					.type(MediaType.APPLICATION_JSON)
					.build();
		}
	}

	/**
	 * Helper class to hold credentials
	 */
	private static class AuthCredentials {
		String clientId;
		String secretKey;
	}
}

