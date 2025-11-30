package prerna.semoss.web.services.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Codex Backend API Implementation for SEMOSS
 *
 * This endpoint implements the Codex Backend API that sits between Codex CLI and model providers.
 * Flow: Codex CLI ? This Backend ? OpenAI (or other model providers)
 *
 * Codex CLI sends requests with fields like:
 * - instructions: System prompt
 * - input: User message (can be array or string)
 * - tools: Function calling definitions
 * - reasoning: Reasoning mode
 * - store: Conversation storage
 * - etc.
 *
 * This backend must:
 * 1. Accept Codex CLI's custom API format
 * 2. Authenticate using SEMOSS User Access Keys
 * 3. Transform to OpenAI's standard format
 * 4. Forward to OpenAI
 * 5. Transform response back to Codex format
 *
 * Reference: https://github.com/openai/codex/blob/main/docs/config.md#model_providers
 *
 * Authentication:
 * - Uses SEMOSS User Access Keys (Base64-encoded access_key:secret_key)
 * - Create keys via: POST /api/auth/user/createUserAccessKey
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
	 * Debug endpoint - logs ALL request details without authentication
	 * Use this to see exactly what Codex is sending
	 * DO NOT USE IN PRODUCTION - NO AUTHENTICATION!
	 */
	@POST
	@Path("/debug/auth")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response debugAuth(@Context HttpServletRequest request) {
		Map<String, Object> debugInfo = new HashMap<>();

		try {
			classLogger.info("========================================");
			classLogger.info("DEBUG ENDPOINT - Logging all request details");
			classLogger.info("========================================");

			// Log all headers
			Map<String, String> headers = new HashMap<>();
			java.util.Enumeration<String> headerNames = request.getHeaderNames();
			while (headerNames.hasMoreElements()) {
				String headerName = headerNames.nextElement();
				String headerValue = request.getHeader(headerName);
				headers.put(headerName, headerValue);
				classLogger.info("Header: {} = {}", headerName, headerValue);
			}
			debugInfo.put("headers", headers);

			// Read request body
			String requestBody = readRequestBody(request);
			classLogger.info("Request Body: {}", requestBody);
			debugInfo.put("requestBody", requestBody);

			// Try to extract credentials
			String authHeader = request.getHeader("Authorization");
			if (authHeader != null) {
				classLogger.info("========================================");
				classLogger.info("ATTEMPTING CREDENTIAL EXTRACTION");
				classLogger.info("========================================");
				AuthCredentials creds = extractCredentials(authHeader);
				if (creds != null) {
					debugInfo.put("extractionSuccess", true);
					debugInfo.put("accessKey", creds.clientId);
					debugInfo.put("secretKeyLength", creds.secretKey.length());
					classLogger.info("SUCCESS: Extracted access_key: {}", creds.clientId);
				} else {
					debugInfo.put("extractionSuccess", false);
					debugInfo.put("error", "Failed to extract credentials");
					classLogger.error("FAILED: Could not extract credentials");
				}
			} else {
				debugInfo.put("error", "No Authorization header found");
				classLogger.error("NO AUTHORIZATION HEADER");
			}

			debugInfo.put("timestamp", System.currentTimeMillis());
			debugInfo.put("message", "Check server logs for detailed analysis");

			return Response.ok()
					.entity(objectMapper.writeValueAsString(debugInfo))
					.type(MediaType.APPLICATION_JSON)
					.build();

		} catch (Exception e) {
			classLogger.error("Error in debug endpoint", e);
			debugInfo.put("error", e.getMessage());
			try {
				return Response.status(500)
						.entity(objectMapper.writeValueAsString(debugInfo))
						.build();
			} catch (Exception e2) {
				return Response.status(500).entity("{\"error\":\"Internal error\"}").build();
			}
		}
	}

	/**
	 * Proxy endpoint for /v1/chat/completions
	 * Transparently forwards requests to OpenAI while handling SEMOSS authentication
	 * Supports both streaming (text/event-stream) and non-streaming (application/json) responses
	 */
	@POST
	@Path("/v1/chat/completions")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces({MediaType.APPLICATION_JSON, "text/event-stream"})
	public Response proxyChatCompletions(@Context HttpServletRequest request) {
		return proxyRequest(request, "/v1/chat/completions");
	}

	/**
	 * Proxy endpoint for /v1/responses
	 * Used by Codex CLI - just pass through to OpenAI's /v1/chat/completions
	 */
	@POST
	@Path("/v1/responses")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces("text/event-stream")
	public Response proxyResponses(@Context HttpServletRequest request) {
		// Codex CLI uses /v1/responses endpoint but sends standard OpenAI format
		// Just map it to /v1/chat/completions and pass through transparently
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
			// Log all incoming request details for debugging
			classLogger.info("========================================");
			classLogger.info("INCOMING REQUEST TO OPENAI PROXY");
			classLogger.info("========================================");
			classLogger.info("Endpoint: {}", endpoint);
			classLogger.info("Method: {}", request.getMethod());
			classLogger.info("URL: {}", request.getRequestURL().toString());
			classLogger.info("Query String: {}", request.getQueryString());
			classLogger.info("Content-Type: {}", request.getContentType());
			classLogger.info("Content-Length: {}", request.getContentLength());

			// Log all headers
			classLogger.info("--- REQUEST HEADERS ---");
			java.util.Enumeration<String> headerNames = request.getHeaderNames();
			while (headerNames.hasMoreElements()) {
				String headerName = headerNames.nextElement();
				String headerValue = request.getHeader(headerName);
				// Mask sensitive data but show format
				if (headerName.equalsIgnoreCase("Authorization")) {
					if (headerValue.length() > 50) {
						classLogger.info("  {}: {}...{}", headerName,
							headerValue.substring(0, 30),
							headerValue.substring(headerValue.length() - 10));
					} else {
						classLogger.info("  {}: {}", headerName, headerValue);
					}
				} else {
					classLogger.info("  {}: {}", headerName, headerValue);
				}
		}
		classLogger.info("========================================");

		// Read request body first for logging
		String requestBody = readRequestBody(request);
		classLogger.info("=== REQUEST BODY ===");
		classLogger.info("Request body length: {}", requestBody.length());
		if (requestBody.length() < 5000) {
			classLogger.info("Request body: {}", requestBody);
		} else {
			classLogger.info("Request body (first 1000 chars): {}", requestBody.substring(0, 1000));
			classLogger.info("Request body (last 500 chars): {}", requestBody.substring(requestBody.length() - 500));
		}
		classLogger.info("========================================");

		// Step 1: Extract and validate Authorization header
			String authHeader = request.getHeader("Authorization");
			if (authHeader == null) {
				authHeader = request.getHeader("authorization");
			}

			if (authHeader == null || authHeader.trim().isEmpty()) {
				classLogger.error("MISSING AUTHORIZATION HEADER - Request rejected");
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

		// Step 4: Validate request body (already read earlier for logging)
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

		// Step 8.5: Sanitize messages array to fix Codex CLI tool call issues
		classLogger.info("=== CHECKING FOR MESSAGES TO SANITIZE ===");
		classLogger.info("Request JSON contains 'messages': {}", requestJson.containsKey("messages"));

		if (requestJson.containsKey("messages")) {
			Object messagesObj = requestJson.get("messages");
			classLogger.info("Messages object type: {}", messagesObj != null ? messagesObj.getClass().getName() : "null");
			classLogger.info("Is List: {}", messagesObj instanceof java.util.List);

			if (messagesObj instanceof java.util.List) {
				try {
					java.util.List<?> messages = (java.util.List<?>) messagesObj;
					classLogger.info("About to sanitize {} messages", messages.size());
					java.util.List<Object> sanitizedMessages = sanitizeMessages(messages);
					requestJson.put("messages", sanitizedMessages);
					classLogger.info("Successfully sanitized messages array");
				} catch (Exception e) {
					classLogger.error("Error sanitizing messages", e);
					// Continue with original messages if sanitization fails
				}
			} else {
				classLogger.warn("Messages is not a List, skipping sanitization");
			}
		} else {
			classLogger.warn("No 'messages' field in request, skipping sanitization");
		}

		// Convert back to JSON string with the updated model name and sanitized messages
		try {
			requestBody = objectMapper.writeValueAsString(requestJson);
			classLogger.info("Updated request body with OpenAI model name and sanitized messages");
			classLogger.info("=== FINAL REQUEST BODY BEING SENT TO OPENAI ===");
			classLogger.info("Request body length: {}", requestBody.length());
			if (requestBody.length() < 5000) {
				classLogger.info(requestBody);
			} else {
				classLogger.info("(first 2000 chars): {}", requestBody.substring(0, 2000));
				classLogger.info("(last 1000 chars): {}", requestBody.substring(requestBody.length() - 1000));
			}
			classLogger.info("=== END FINAL REQUEST BODY ===");
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
	 * Supports multiple formats:
	 * 1. "Bearer sk-base64(access_key:secret_key)"
	 * 2. "Bearer base64(access_key:secret_key)"
	 * 3. "Bearer access_key:secret_key" (plain text)
	 * 4. "Basic base64(access_key:secret_key)"
	 * 5. "Bearer <anything>" - tries multiple decoding strategies
	 */
	private AuthCredentials extractCredentials(String authHeader) {
		try {
			classLogger.info("=== AUTHENTICATION ATTEMPT ===");
			classLogger.info("Full Authorization header length: {}", authHeader.length());
			classLogger.info("Authorization header prefix: {}", authHeader.substring(0, Math.min(30, authHeader.length())));
			classLogger.info("Authorization header suffix: {}", authHeader.length() > 30 ? "..." + authHeader.substring(authHeader.length() - 20) : "");

			String encodedCredentials;
			String authType = "unknown";

			if (authHeader.startsWith("Bearer ")) {
				encodedCredentials = authHeader.substring(7).trim();
				authType = "Bearer";
			} else if (authHeader.startsWith("bearer ")) {
				encodedCredentials = authHeader.substring(7).trim();
				authType = "bearer";
			} else if (authHeader.startsWith("Basic ")) {
				encodedCredentials = authHeader.substring(6).trim();
				authType = "Basic";
			} else {
				// Try to decode as-is
				encodedCredentials = authHeader.trim();
				authType = "raw";
			}

			classLogger.info("Auth type detected: {}", authType);
			classLogger.info("Credential string length after prefix removal: {}", encodedCredentials.length());
			classLogger.info("Credential string starts with: {}", encodedCredentials.substring(0, Math.min(20, encodedCredentials.length())));

			// Remove "sk-" prefix if present (OpenAI convention)
			boolean hadSkPrefix = false;
			if (encodedCredentials.startsWith("sk-")) {
				encodedCredentials = encodedCredentials.substring(3);
				hadSkPrefix = true;
				classLogger.info("Removed 'sk-' prefix from credentials");
				classLogger.info("After sk- removal, length: {}", encodedCredentials.length());
			}

			String decoded = null;
			String decodingMethod = "unknown";

			// Try multiple decoding strategies

			// Strategy 1: Try Base64 decoding
			try {
				byte[] decodedBytes = Base64.getDecoder().decode(encodedCredentials);
				decoded = new String(decodedBytes, StandardCharsets.UTF_8);
				decodingMethod = "base64";
				classLogger.info("Successfully decoded using Base64");
				classLogger.info("Decoded string length: {}", decoded.length());
				classLogger.info("Decoded string preview: {}", decoded.substring(0, Math.min(50, decoded.length())));
			} catch (IllegalArgumentException e) {
				classLogger.info("Base64 decoding failed: {}", e.getMessage());
			}

			// Strategy 2: If Base64 failed, try treating as plain text
			if (decoded == null) {
				decoded = encodedCredentials;
				decodingMethod = "plaintext";
				classLogger.info("Using credentials as plain text (no decoding)");
			}

			// Strategy 3: If we had sk- prefix and decoding failed, try decoding the original with sk-
			if (hadSkPrefix && !decodingMethod.equals("base64")) {
				try {
					String withSkPrefix = "sk-" + encodedCredentials;
					byte[] decodedBytes = Base64.getDecoder().decode(withSkPrefix);
					String tempDecoded = new String(decodedBytes, StandardCharsets.UTF_8);
					if (tempDecoded.contains(":")) {
						decoded = tempDecoded;
						decodingMethod = "base64_with_sk_prefix";
						classLogger.info("Successfully decoded with sk- prefix included");
					}
				} catch (Exception e) {
					classLogger.info("Decoding with sk- prefix failed");
				}
			}

			// Split into client_id:secret_key
			classLogger.info("Attempting to split decoded string into access_key:secret_key");
			classLogger.info("Looking for ':' character in decoded string");

			// Check if colon exists
			if (!decoded.contains(":")) {
				classLogger.error("=== CREDENTIAL FORMAT ERROR ===");
				classLogger.error("Decoded string does NOT contain ':' separator");
				classLogger.error("Decoded string length: {}", decoded.length());
				classLogger.error("First 100 chars: {}", decoded.substring(0, Math.min(100, decoded.length())));
				classLogger.error("Character inspection (first 20 chars):");
				for (int i = 0; i < Math.min(20, decoded.length()); i++) {
					char c = decoded.charAt(i);
					classLogger.error("  [{}] = '{}' (ASCII: {})", i, c, (int)c);
				}
				classLogger.error("=================================");
				return null;
			}

			String[] parts = decoded.split(":", 2);
			if (parts.length != 2) {
				classLogger.error("Invalid credential format after decoding. Expected 'access_key:secret_key', got: {}",
					decoded.length() > 50 ? decoded.substring(0, 50) + "..." : decoded);
				classLogger.error("Split resulted in {} parts instead of 2", parts.length);
				return null;
			}

			AuthCredentials creds = new AuthCredentials();
			// UUIDs are safe - no need to sanitize
			creds.clientId = parts[0].trim();
			creds.secretKey = parts[1].trim();

			classLogger.info("=== CREDENTIAL EXTRACTION SUCCESS ===");
			classLogger.info("Decoding method used: {}", decodingMethod);
			classLogger.info("Client_id: {}", creds.clientId);
			classLogger.info("Secret_key length: {}", creds.secretKey.length());
			classLogger.info("Client_id length: {}", creds.clientId.length());
			classLogger.info("====================================");

			return creds;

		} catch (Exception e) {
			classLogger.error("=== EXCEPTION IN CREDENTIAL EXTRACTION ===");
			classLogger.error("Error extracting credentials from Authorization header", e);
			classLogger.error("Stack trace:", e);
			classLogger.error("==========================================");
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
			classLogger.info("=== PROXYING TO OPENAI (NON-STREAMING) ===");
			classLogger.info("Endpoint: {}{}", OPENAI_BASE_URL, endpoint);

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
		classLogger.info("=== STREAMING REQUEST TO OPENAI ===");
		classLogger.info("Endpoint: {}{}", OPENAI_BASE_URL, endpoint);

		StreamingOutput stream = new StreamingOutput() {
			@Override
			public void write(OutputStream output) throws IOException {
				HttpURLConnection connection = null;
				try {
					classLogger.info("Opening connection to OpenAI for streaming...");

					// Create connection to OpenAI
					URL url = new URL(OPENAI_BASE_URL + endpoint);
					connection = (HttpURLConnection) url.openConnection();
					connection.setRequestMethod("POST");
					connection.setRequestProperty("Content-Type", "application/json");
					connection.setRequestProperty("Authorization", "Bearer " + openAiApiKey);
					connection.setRequestProperty("Accept", "text/event-stream");
					connection.setDoOutput(true);
					connection.setDoInput(true);

					// Don't timeout on read - streaming can be slow
					connection.setReadTimeout(0);
					connection.setConnectTimeout(30000); // 30 seconds connect timeout

					classLogger.info("Sending request to OpenAI...");

					// Send request
					try (OutputStream os = connection.getOutputStream()) {
						byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
						os.write(input, 0, input.length);
						os.flush();
					}

					// Stream response
					int statusCode = connection.getResponseCode();
					classLogger.info("OpenAI streaming response status: {}", statusCode);

					if (statusCode >= 200 && statusCode < 300) {
						classLogger.info("Starting to stream response from OpenAI...");

						try (InputStream is = connection.getInputStream()) {
							byte[] buffer = new byte[BUFFER_SIZE];
							int bytesRead;
							int totalBytes = 0;

							while ((bytesRead = is.read(buffer)) != -1) {
								output.write(buffer, 0, bytesRead);
								output.flush(); // Flush immediately for SSE
								totalBytes += bytesRead;
							}

							classLogger.info("Streaming complete. Total bytes streamed: {}", totalBytes);
						}
					} else {
						classLogger.error("OpenAI streaming error. Status: {}", statusCode);

						// Error response
						String errorBody = readStream(connection.getErrorStream());
						classLogger.error("OpenAI error response: {}", errorBody);

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
					classLogger.info("Streaming connection closed");
				}
			}
		};

		return Response.ok()
				.entity(stream)
				.header("Content-Type", "text/event-stream")
				.header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive")
				.header("X-Accel-Buffering", "no") // Disable nginx buffering if behind nginx
				.build();
	}

	/**
	 * Sanitize messages array to handle Codex CLI tool call issues
	 * OpenAI requires that assistant messages with tool_calls are IMMEDIATELY followed by tool responses
	 *
	 * Instead of removing messages (losing history), this method:
	 * 1. Identifies which tool call sequences are valid (complete and consecutive)
	 * 2. Converts incomplete tool call sequences into regular assistant messages (strips tool_calls)
	 * 3. Removes only orphaned tool responses (no matching tool call)
	 *
	 * This preserves ALL conversation history while preventing 400 errors from OpenAI
	 */
	private java.util.List<Object> sanitizeMessages(java.util.List<?> messages) {
		classLogger.info("=== SANITIZING MESSAGES ===");
		classLogger.info("Total messages: {}", messages.size());

		java.util.List<Object> sanitized = new java.util.ArrayList<>();
		java.util.Set<String> validToolCallIds = new java.util.HashSet<>();
		java.util.Map<Integer, java.util.Set<String>> assistantToolCalls = new java.util.HashMap<>();

		// First pass: identify which tool calls have complete sequences
		for (int i = 0; i < messages.size(); i++) {
			if (!(messages.get(i) instanceof Map)) continue;

			Map<?, ?> msg = (Map<?, ?>) messages.get(i);
			String role = (String) msg.get("role");

			classLogger.debug("Message[{}]: role={}, has_tool_calls={}, has_tool_call_id={}",
				i, role, msg.containsKey("tool_calls"), msg.containsKey("tool_call_id"));

			if ("assistant".equals(role) && msg.containsKey("tool_calls")) {
				Object toolCallsObj = msg.get("tool_calls");
				if (toolCallsObj instanceof java.util.List) {
					java.util.List<?> toolCalls = (java.util.List<?>) toolCallsObj;
					java.util.Set<String> expectedIds = new java.util.HashSet<>();

					// Collect tool call IDs
					for (Object tcObj : toolCalls) {
						if (tcObj instanceof Map) {
							Map<?, ?> tc = (Map<?, ?>) tcObj;
							if (tc.containsKey("id")) {
								expectedIds.add(String.valueOf(tc.get("id")));
							}
						}
					}

					assistantToolCalls.put(i, expectedIds);
					classLogger.info("Message[{}]: Assistant with {} tool calls: {}", i, expectedIds.size(), expectedIds);

					// Check if next consecutive messages are tool responses
					java.util.Set<String> foundIds = new java.util.HashSet<>();
					boolean sequenceBroken = false;

					for (int j = i + 1; j < messages.size(); j++) {
						if (!(messages.get(j) instanceof Map)) {
							sequenceBroken = true;
							break;
						}

						Map<?, ?> nextMsg = (Map<?, ?>) messages.get(j);
						String nextRole = (String) nextMsg.get("role");

						if ("tool".equals(nextRole) && nextMsg.containsKey("tool_call_id")) {
							String toolCallId = String.valueOf(nextMsg.get("tool_call_id"));
							if (expectedIds.contains(toolCallId)) {
								foundIds.add(toolCallId);
								classLogger.debug("  Message[{}]: Found tool response for {}", j, toolCallId);
							} else {
								// Tool response for different tool call - sequence broken
								sequenceBroken = true;
								break;
							}
						} else if (foundIds.size() < expectedIds.size()) {
							// Non-tool message before sequence complete
							classLogger.warn("  Message[{}]: Found {} message before all tool responses received", j, nextRole);
							sequenceBroken = true;
							break;
						} else {
							// All tool responses found, next message is fine
							break;
						}

						if (foundIds.size() == expectedIds.size()) {
							break;
						}
					}

					// Mark as valid only if complete and not broken
					if (!sequenceBroken && foundIds.equals(expectedIds)) {
						validToolCallIds.addAll(expectedIds);
						classLogger.info("  ? Valid complete tool sequence");
					} else {
						classLogger.warn("  ? Incomplete/broken tool sequence - will convert to regular message");
						classLogger.warn("    Expected: {}, Found: {}, Broken: {}", expectedIds.size(), foundIds.size(), sequenceBroken);
					}
				}
			}
		}

		// Second pass: build sanitized list
		int conversionsCount = 0;
		int orphanedToolsCount = 0;
		int removedEmptyCount = 0;

		for (int i = 0; i < messages.size(); i++) {
			Object msgObj = messages.get(i);

			if (!(msgObj instanceof Map)) {
				sanitized.add(msgObj);
				continue;
			}

			@SuppressWarnings("unchecked")
			Map<String, Object> msg = new java.util.HashMap<>((Map<String, Object>) msgObj);
			String role = (String) msg.get("role");

			if ("assistant".equals(role) && msg.containsKey("tool_calls")) {
				java.util.Set<String> toolCallIds = assistantToolCalls.get(i);

				if (toolCallIds != null && !validToolCallIds.containsAll(toolCallIds)) {
					// Incomplete sequence - need to convert to regular assistant message
					// Remove tool_calls field
					msg.remove("tool_calls");

					// Check if message has content
					Object content = msg.get("content");
					if (content == null || (content instanceof String && ((String) content).trim().isEmpty())) {
						// No content and we removed tool_calls - skip this message entirely
						// Can't have an assistant message with null/empty content
						removedEmptyCount++;
						classLogger.warn("Message[{}]: ? Removed assistant with broken tool_calls and no content", i);
						continue; // Skip this message
					} else {
						// Has content, safe to keep as regular message
						conversionsCount++;
						classLogger.info("Message[{}]: ? Converted assistant with broken tool_calls to regular message", i);
					}
				}

				sanitized.add(msg);

			} else if ("tool".equals(role) && msg.containsKey("tool_call_id")) {
				String toolCallId = String.valueOf(msg.get("tool_call_id"));

				if (validToolCallIds.contains(toolCallId)) {
					// Valid tool response - keep it
					sanitized.add(msg);
					classLogger.debug("Message[{}]: Kept valid tool response", i);
				} else {
					// Orphaned tool response - skip it
					orphanedToolsCount++;
					classLogger.warn("Message[{}]: ? Removed orphaned tool response for: {}", i, toolCallId);
				}

			} else {
				// Regular message - keep it
				sanitized.add(msg);
			}
		}

		classLogger.info("=== SANITIZATION COMPLETE ===");
		classLogger.info("Original messages: {}", messages.size());
		classLogger.info("Sanitized messages: {}", sanitized.size());
		classLogger.info("Tool sequences converted to regular messages: {}", conversionsCount);
		classLogger.info("Empty assistant messages removed: {}", removedEmptyCount);
		classLogger.info("Orphaned tool responses removed: {}", orphanedToolsCount);
		classLogger.info("? All valid conversation history preserved");

		return sanitized;
	}

	/**
	 * Read request body from HttpServletRequest
	 * Handles large payloads properly
	 */
	private String readRequestBody(HttpServletRequest request) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
			char[] buffer = new char[8192];
			int bytesRead;
			while ((bytesRead = reader.read(buffer)) != -1) {
				sb.append(buffer, 0, bytesRead);
			}
		}
		classLogger.debug("Read request body: {} bytes", sb.length());
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
			classLogger.debug("Parsing JSON, length: {}", json != null ? json.length() : 0);
			TypeReference<Map<String, Object>> typeRef = new TypeReference<Map<String, Object>>() {};
			Map<String, Object> result = objectMapper.readValue(json, typeRef);
			classLogger.debug("Parsed JSON successfully, keys: {}", result.keySet());
			return result;
		} catch (Exception e) {
			classLogger.error("Error parsing JSON: {}", e.getMessage());
			classLogger.error("JSON content (first 500 chars): {}", json != null ? json.substring(0, Math.min(500, json.length())) : "null");
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

