package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityRoomTokenUtils;
import prerna.engine.impl.model.inferencetracking.ModelInferenceLogsUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/roomtoken")
@PermitAll
public class RoomTokenAuthorizationResource {

	private static final Logger classLogger = LogManager.getLogger(RoomTokenAuthorizationResource.class);

	@GET
	@Produces("application/json")
	@Path("getRoomTokenLimits")
	public Response getRoomTokenLimits(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		if (!SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User must be an admin to perform this function");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			List<Map<String, Object>> limits = SecurityRoomTokenUtils.getAllRoomTokenLimits();
			return WebUtility.getResponse(limits, 200);
		} catch (Exception e) {
			classLogger.error("Failed to get room token limits", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@POST
	@Consumes("application/x-www-form-urlencoded")
	@Produces("application/json")
	@Path("setRoomTokenLimit")
	public Response setRoomTokenLimit(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		if (!SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User must be an admin to perform this function");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			String userId = WebUtility.inputSanitizer(form.getFirst("userId"));
			if (userId != null && userId.trim().isEmpty()) {
				userId = null;
			}

			long maxTokens = parseLong(form.getFirst("maxTokens"), -1);
			long maxInputTokens = parseLong(form.getFirst("maxInputTokens"), -1);
			long maxOutputTokens = parseLong(form.getFirst("maxOutputTokens"), -1);
			boolean isActive = parseBoolean(form.getFirst("isActive"), true);

			String createdBy = user.getAccessToken(user.getLogins().get(0)).getId();

			if (userId == null) {
				SecurityRoomTokenUtils.setDefaultRoomTokenLimit(maxTokens, maxInputTokens, maxOutputTokens, isActive, createdBy);
			} else {
				SecurityRoomTokenUtils.setUserRoomTokenLimit(userId, maxTokens, maxInputTokens, maxOutputTokens, isActive, createdBy);
			}

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			ret.put("userId", userId);
			ret.put("maxTokens", maxTokens);
			ret.put("maxInputTokens", maxInputTokens);
			ret.put("maxOutputTokens", maxOutputTokens);
			ret.put("isActive", isActive);
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			classLogger.error("Failed to set room token limit", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@POST
	@Consumes("application/x-www-form-urlencoded")
	@Produces("application/json")
	@Path("removeRoomTokenLimit")
	public Response removeRoomTokenLimit(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		if (!SecurityAdminUtils.userIsAdmin(user)) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User must be an admin to perform this function");
			return WebUtility.getResponse(errorMap, 403);
		}

		try {
			String userId = WebUtility.inputSanitizer(form.getFirst("userId"));
			if (userId == null || userId.trim().isEmpty()) {
				Map<String, String> errorMap = new HashMap<>();
				errorMap.put(Constants.ERROR_MESSAGE, "Must provide a userId to remove");
				return WebUtility.getResponse(errorMap, 400);
			}

			SecurityRoomTokenUtils.removeUserRoomTokenLimit(userId);

			Map<String, Object> ret = new HashMap<>();
			ret.put("success", true);
			ret.put("userId", userId);
			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			classLogger.error("Failed to remove room token limit", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	@GET
	@Produces("application/json")
	@Path("getRoomTokenUsage")
	public Response getRoomTokenUsage(@Context HttpServletRequest request,
			@QueryParam("roomId") String roomId) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			classLogger.error("Invalid user session", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		roomId = WebUtility.inputSanitizer(roomId);
		if (roomId == null || roomId.trim().isEmpty()) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must provide a roomId");
			return WebUtility.getResponse(errorMap, 400);
		}

		try {
			String userId = user.getAccessToken(user.getLogins().get(0)).getId();
			Map<String, Object> roomLimit = SecurityRoomTokenUtils.getEffectiveRoomTokenLimit(userId);

			Map<String, Object> ret = new HashMap<>();

			Number combinedUsage = ModelInferenceLogsUtils.getTotalTokensForRoom(roomId, null);
			Number inputUsage = ModelInferenceLogsUtils.getTotalTokensForRoom(roomId, "INPUT");
			Number outputUsage = ModelInferenceLogsUtils.getTotalTokensForRoom(roomId, "RESPONSE");

			ret.put("roomId", roomId);
			ret.put("tokensUsed", combinedUsage != null ? combinedUsage.longValue() : 0);
			ret.put("inputTokensUsed", inputUsage != null ? inputUsage.longValue() : 0);
			ret.put("outputTokensUsed", outputUsage != null ? outputUsage.longValue() : 0);

			if (roomLimit != null) {
				ret.put("configured", true);
				ret.put("tokenLimit", roomLimit.get("maxTokens"));
				ret.put("inputTokenLimit", roomLimit.get("maxInputTokens"));
				ret.put("outputTokenLimit", roomLimit.get("maxOutputTokens"));
				ret.put("isActive", roomLimit.get("isActive"));
			} else {
				ret.put("configured", false);
				ret.put("tokenLimit", null);
				ret.put("inputTokenLimit", null);
				ret.put("outputTokenLimit", null);
			}

			return WebUtility.getResponse(ret, 200);
		} catch (Exception e) {
			classLogger.error("Failed to get room token usage", e);
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 500);
		}
	}

	private long parseLong(String val, long defaultVal) {
		if (val == null || val.trim().isEmpty()) {
			return defaultVal;
		}
		try {
			return Long.parseLong(val.trim());
		} catch (NumberFormatException e) {
			return defaultVal;
		}
	}

	private boolean parseBoolean(String val, boolean defaultVal) {
		if (val == null || val.trim().isEmpty()) {
			return defaultVal;
		}
		return Boolean.parseBoolean(val.trim());
	}
}
