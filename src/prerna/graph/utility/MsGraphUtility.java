package prerna.graph.utility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.google.gson.Gson;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.graph.MSGraphAPICall;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

public class MsGraphUtility {
	
	private static final Logger classLogger = LogManager.getLogger(MsGraphUtility.class);
	
	public static List<Map<String, Object>> getProjectUsers(HttpServletRequest request, User user, String projectId,
			String searchTerm, long limit, long offset) {
		HttpSession session = request.getSession(false);
		String sessionKey = "nextLinkData_" + projectId + "_" + searchTerm;

		// Initialize or retrieve session data
		Map<String, Object> sessionData = (Map<String, Object>) session.getAttribute(sessionKey);
		if (sessionData == null) {
			sessionData = new HashMap<>(); // Create new session data map if not already in session
			session.setAttribute(sessionKey, sessionData);
		}

		// Step 1: Retrieve database users from session or load from DB if not available
		List<Map<String, Object>> dbUsers = (List<Map<String, Object>>) sessionData.get("dbUsers");
		if (dbUsers == null) {
			try {

				dbUsers = (user != null) ? SecurityProjectUtils.getProjectUsers(user, projectId, searchTerm, "", 0, 0)
						: SecurityAdminUtils.getProjectUsers(projectId, searchTerm, "", -1, -1);
				sessionData.put("dbUsers", dbUsers); // Store DB users in the same session attribute
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Unable to retrieve database users");
				return (List<Map<String, Object>>) WebUtility.getResponse(errorMap, 401);
				
			}
		}

		if (user.getAccessToken(AuthProvider.MS) == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must be logged into your microsoft login to search for users");
			return (List<Map<String, Object>>) WebUtility.getResponse(errorMap, 401);
			
		}

		final List<Map<String, Object>> finalDbUsers = dbUsers;
		String nextLink = (String) sessionData.get("nextLinkData");
		List<Map<String, Object>> msGraphUsers = new ArrayList<>();
		List<Map<String, Object>> filteredUsers = new ArrayList<>();

		try {
			MSGraphAPICall msGraphApi = new MSGraphAPICall();
			Gson gson = new Gson();

			// Step 3: Fetch more data if nextLink is in the session, else make a fresh call
			// to Graph API
			if (nextLink == null || offset == 0) {
				// Make a new API call to GraphAPI if nextLink is not in the session
				String msUsers = msGraphApi.getUserDetails(user.getAccessToken(AuthProvider.MS), searchTerm, null);
				JSONObject jsonObject = new JSONObject(msUsers);
				JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);
				msGraphUsers = gson.fromJson(jsonArray.toString(), List.class);

				// Store new nextLink for pagination if available
				nextLink = jsonObject.optString("@odata.nextLink", null);
				if (nextLink != null) {
					sessionData.put("nextLinkData", nextLink); // Store the nextLink in the same session attribute
				}
			} else {
				// Fetch data from GraphAPI using nextLink
				String msUsers = msGraphApi.getUserDetails(user.getAccessToken(AuthProvider.MS), searchTerm, nextLink);
				JSONObject jsonObject = new JSONObject(msUsers);
				JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);
				msGraphUsers = gson.fromJson(jsonArray.toString(), List.class);

				// Update or clear nextLink based on the response
				nextLink = jsonObject.optString("@odata.nextLink", null);
				if (nextLink != null) {
					sessionData.put("nextLinkData", nextLink); // Update nextLink in the same session attribute
				} else {
					sessionData.remove("nextLinkData"); // Remove nextLink from session if no more data
				}
			}

			do {
				// Step 4: Compare database users with GraphAPI users and apply necessary
				// filters
				filteredUsers = msGraphUsers.stream().filter(msUser -> finalDbUsers.stream().noneMatch(dbUser -> dbUser
						.get(Constants.SMSS_USER_EMAIL).equals(msUser.get(Constants.MS_GRAPH_EMAIL))
						|| dbUser.get(Constants.SMSS_USER_NAME).equals(msUser.get(Constants.MS_GRAPH_DISPLAY_NAME))))
						.map(msUser -> {
							Map<String, Object> userMap = new HashMap<>();
							userMap.put(Constants.USER_MAP_NAME, msUser.get(Constants.MS_GRAPH_DISPLAY_NAME));
							userMap.put(Constants.USER_MAP_ID, msUser.get(Constants.MS_GRAPH_ID));
							userMap.put(Constants.USER_MAP_TYPE, AuthProvider.MS);
							userMap.put(Constants.USER_MAP_EMAIL, msUser.get(Constants.MS_GRAPH_EMAIL));
							userMap.put(Constants.USER_MAP_USERNAME,
									msUser.get(Constants.MS_GRAPH_USER_PRINCIPAL_NAME));
							return userMap;
						}).collect(Collectors.toList());

				long currentCount = filteredUsers.size();
				if (currentCount < limit && nextLink != null) {
					long limitCount = limit - currentCount;
					List<Map<String, Object>> moreUsers = fetchMsGraphUsers(user, searchTerm, sessionData);
					filteredUsers.addAll(moreUsers);
				}

				if (filteredUsers.size() >= limit || nextLink == null) {
					return filteredUsers.subList(0, (int) Math.min(limit, filteredUsers.size()));
				}

				if (filteredUsers.size() < limit && nextLink != null) {
					long limitCount = limit - filteredUsers.size();
					List<Map<String, Object>> moreUsers = (user != null)
							? SecurityProjectUtils.getProjectUsers(user, projectId, searchTerm, "", limitCount, offset)
							: SecurityAdminUtils.getProjectUsers(projectId, searchTerm, "", limit, offset);

					filteredUsers.addAll(moreUsers);
				}

			} while (filteredUsers.size() < limit && nextLink != null);

		} catch (Exception e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred while fetching users");
			return (List<Map<String, Object>>) WebUtility.getResponse(errorMap, 401);
			
		}

		return filteredUsers;
	}

	public static List<Map<String, Object>> getEngineUsers(HttpServletRequest request, User user, String engineId,
			String searchTerm, long limit, long offset) {
		// Create a session and define a single session key to store everything
		HttpSession session = request.getSession(false);
		String sessionKey = "nextLinkData_" + engineId + "_" + searchTerm;

		// Initialize or retrieve session data
		Map<String, Object> sessionData = (Map<String, Object>) session.getAttribute(sessionKey);
		if (sessionData == null) {
			sessionData = new HashMap<>(); // Create new session data map if not already in session
			session.setAttribute(sessionKey, sessionData);
		}

		// Step 1: Retrieve database users from session or load from DB if not available
		List<Map<String, Object>> dbUsers = (List<Map<String, Object>>) sessionData.get("dbUsers");
		if (dbUsers == null) {
			try {

				dbUsers = (user != null)
						? SecurityEngineUtils.getEngineUsers(user, engineId, searchTerm, "", limit, offset)
						: SecurityAdminUtils.getEngineUsers(engineId, searchTerm, "", limit, offset);
				sessionData.put("dbUsers", dbUsers); // Store DB users in the same session attribute
			} catch (IllegalAccessException e) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE, "Unable to retrieve database users");
				return (List<Map<String, Object>>) WebUtility.getResponse(errorMap, 401);
			}
		}

		if (user.getAccessToken(AuthProvider.MS) == null) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "Must be logged into your microsoft login to search for users");
			return (List<Map<String, Object>>) WebUtility.getResponse(errorMap, 401);
		}

		final List<Map<String, Object>> finalDbUsers = dbUsers;
		String nextLink = (String) sessionData.get("nextLinkData");
		List<Map<String, Object>> msGraphUsers = new ArrayList<>();
		List<Map<String, Object>> filteredUsers = new ArrayList<>();

		try {
			MSGraphAPICall msGraphApi = new MSGraphAPICall();
			Gson gson = new Gson();

			// Step 3: Fetch more data if nextLink is in the session, else make a fresh call
			// to Graph API
			if (nextLink == null || offset == 0) {
				// Make a new API call to GraphAPI if nextLink is not in the session
				String msUsers = msGraphApi.getUserDetails(user.getAccessToken(AuthProvider.MS), searchTerm, null);
				JSONObject jsonObject = new JSONObject(msUsers);
				JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);
				msGraphUsers = gson.fromJson(jsonArray.toString(), List.class);

				// Store new nextLink for pagination if available
				nextLink = jsonObject.optString("@odata.nextLink", null);
				if (nextLink != null) {
					sessionData.put("nextLinkData", nextLink); // Store the nextLink in the same session attribute
				}
			} else {
				// Fetch data from GraphAPI using nextLink
				String msUsers = msGraphApi.getUserDetails(user.getAccessToken(AuthProvider.MS), searchTerm, nextLink);
				JSONObject jsonObject = new JSONObject(msUsers);
				JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);
				msGraphUsers = gson.fromJson(jsonArray.toString(), List.class);

				// Update or clear nextLink based on the response
				nextLink = jsonObject.optString("@odata.nextLink", null);
				if (nextLink != null) {
					sessionData.put("nextLinkData", nextLink); // Update nextLink in the same session attribute
				} else {
					sessionData.remove("nextLinkData"); // Remove nextLink from session if no more data
				}
			}

			do {
				// Step 4: Compare database users with GraphAPI users and apply necessary
				// filters
				filteredUsers = msGraphUsers.stream().filter(msUser -> finalDbUsers.stream().noneMatch(dbUser -> dbUser
						.get(Constants.SMSS_USER_EMAIL).equals(msUser.get(Constants.MS_GRAPH_EMAIL))
						|| dbUser.get(Constants.SMSS_USER_NAME).equals(msUser.get(Constants.MS_GRAPH_DISPLAY_NAME))))
						.map(msUser -> {
							Map<String, Object> userMap = new HashMap<>();
							userMap.put(Constants.USER_MAP_NAME, msUser.get(Constants.MS_GRAPH_DISPLAY_NAME));
							userMap.put(Constants.USER_MAP_ID, msUser.get(Constants.MS_GRAPH_ID));
							userMap.put(Constants.USER_MAP_TYPE, AuthProvider.MS);
							userMap.put(Constants.USER_MAP_EMAIL, msUser.get(Constants.MS_GRAPH_EMAIL));
							userMap.put(Constants.USER_MAP_USERNAME,
									msUser.get(Constants.MS_GRAPH_USER_PRINCIPAL_NAME));
							return userMap;
						}).collect(Collectors.toList());
			   // step 5: If nextLink was used and limitCount > 0, append the specified limitCount data
				long currentCount = filteredUsers.size();
				if (currentCount < limit && nextLink != null) {
					long limitCount = limit - currentCount;
					List<Map<String, Object>> moreUsers = fetchMsGraphUsers(user, searchTerm, sessionData);
					filteredUsers.addAll(moreUsers);
				}
				// Step 6: Return the data if the limit is reached or no more nextLink data
				if (filteredUsers.size() >= limit || nextLink == null) {
					return filteredUsers.subList(0, (int) Math.min(limit, filteredUsers.size()));
				}
				// Step 7: If the limit is not reached, calculate difference and use nextLink to get more data
				if (filteredUsers.size() < limit && nextLink != null) {
					long limitCount = limit - filteredUsers.size();
					List<Map<String, Object>> moreUsers = (user != null)
							? SecurityEngineUtils.getEngineUsers(user, engineId, searchTerm, "", limitCount, offset)
							: SecurityAdminUtils.getEngineUsers(engineId, searchTerm, "", limitCount, offset);

					filteredUsers.addAll(moreUsers);
				}

			} while (filteredUsers.size() < limit && nextLink != null);

		} catch (Exception e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, "An error occurred while fetching users");
			return (List<Map<String, Object>>) WebUtility.getResponse(errorMap, 401);
		}

		return filteredUsers;
	}

	

	public static List<Map<String, Object>> fetchMsGraphUsers(User user, String searchTerm,
			Map<String, Object> sessionData) throws Exception {
		String nextLink = (String) sessionData.get("nextLinkData");
		List<Map<String, Object>> msGraphUsers = new ArrayList<>();
		MSGraphAPICall msGraphApi = new MSGraphAPICall();
		Gson gson = new Gson();

		// Make API call to GraphAPI
		String msUsers;
		if (nextLink == null) {
			// First call to fetch users
			msUsers = msGraphApi.getUserDetails(user.getAccessToken(AuthProvider.MS), searchTerm, null);
		} else {
			// Subsequent call using nextLink
			msUsers = msGraphApi.getUserDetails(user.getAccessToken(AuthProvider.MS), searchTerm, nextLink);
		}

		// Parse the response
		JSONObject jsonObject = new JSONObject(msUsers);
		JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);
		msGraphUsers = gson.fromJson(jsonArray.toString(), List.class);

		// Update nextLink for pagination
		nextLink = jsonObject.optString("@odata.nextLink", null);
		if (nextLink != null) {
			sessionData.put("nextLinkData", nextLink);
		} else {
			sessionData.remove("nextLinkData"); // Remove nextLink if no more data
		}

		return msGraphUsers;

	}
	
}
	
	
	
	
	
	
	
	
	
	
	
	
	
	

