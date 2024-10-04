package prerna.graph.util;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.graph.MSGraphAPICall;
import prerna.util.Constants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import prerna.web.services.util.WebUtility;
import java.util.stream.Collectors;

public class MSGraphUtility {

	private static final Logger classLogger = LogManager.getLogger(MSGraphUtility.class);

	public static List<Map<String, Object>> getFilteredMSGraphUsers(AccessToken accessToken,List<Map<String, Object>> existingUsers, String searchTerm) {
		// Fetch MS Graph users if the session user has an access token
		List<Map<String, Object>> msGraphUsers = new ArrayList<>();
		MSGraphAPICall msGraphApi = new MSGraphAPICall();

		String nextLink = null;
		try {
			do {
				String msUsers = msGraphApi.getUserDetails(accessToken, searchTerm, nextLink);
				JSONObject jsonObject = new JSONObject(msUsers);
				JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);

				Gson gson = new Gson();
				List<Map<String, Object>> currentUsers = gson.fromJson(jsonArray.toString(), List.class);
				msGraphUsers.addAll(currentUsers);// Append the current page users

				// Update next link for iteration
				nextLink = jsonObject.optString("@odata.nextLink", null);
			} while (nextLink != null);
			// filter out users from the Microsoft Graph based on their displayName and
			// mail, compare them with the existing users in the SMSS_USER table using the
			// name and email fields.
			return msGraphUsers.stream().filter(msUser -> existingUsers.stream().noneMatch(dbUser -> dbUser
					.get(Constants.SMSS_USER_EMAIL).equals(msUser.get(Constants.MS_GRAPH_EMAIL))
					|| dbUser.get(Constants.SMSS_USER_NAME).equals(msUser.get(Constants.MS_GRAPH_DISPLAY_NAME))))
					.map(msUser -> {
						Map<String, Object> userMap = new HashMap<>();
						userMap.put(Constants.USER_MAP_NAME, msUser.get(Constants.MS_GRAPH_DISPLAY_NAME));
						userMap.put(Constants.USER_MAP_ID, msUser.get(Constants.MS_GRAPH_ID));
						userMap.put(Constants.USER_MAP_TYPE, AuthProvider.MS);
						userMap.put(Constants.USER_MAP_EMAIL, msUser.get(Constants.MS_GRAPH_EMAIL));
						userMap.put(Constants.USER_MAP_USERNAME, msUser.get(Constants.MS_GRAPH_USER_PRINCIPAL_NAME));
						return userMap;
					}).collect(Collectors.toList());

		} catch (Exception e) {
			classLogger.error(Constants.STACKTRACE, e);
			return (List<Map<String, Object>>) WebUtility.getResponse(new ArrayList<>(), 200);
		}
	}

	public static void addNewUsers(List<Map<String, String>> permissions) {
		// filter out users that already exist
		List<Map<String, String>> filteredUsers = permissions.stream()
				.filter(map -> !SecurityQueryUtils.checkUserExist(map.get(Constants.MAP_USERID)))
				.collect(Collectors.toList());

		if (filteredUsers != null && !filteredUsers.isEmpty()) {
			AccessToken token;
			// Add new users to OAuth if they don't exist
			for (Map<String, String> map : filteredUsers) {
				token = new AccessToken();
				token.setId(map.get(Constants.MAP_USERID));
				token.setEmail(map.get(Constants.MAP_EMAIL));
				token.setName(map.get(Constants.MAP_NAME));
				token.setProvider(AuthProvider.getProviderFromString(map.get(AuthProvider.MS.name())));
				token.setUsername(map.get(Constants.MAP_USERNAME));
				SecurityUpdateUtils.addOAuthUser(token);
			}
		}
	}
}
