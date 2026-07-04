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
import com.google.gson.reflect.TypeToken;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.graph.MSGraphAPICall;
import prerna.util.Constants;
import prerna.util.SocialPropertiesUtil;

public class MsGraphUtility {

	private static final Logger classLogger = LogManager.getLogger(MsGraphUtility.class);

	private static final Gson GSON = new Gson();

	private static String prefix = "nld_"; // for next link data
	private static String projectPrefix = prefix + "p_";
	private static String enginePrefix = prefix + "e_";

	/**
	 * 
	 * @param request
	 * @param user
	 * @param projectId
	 * @param searchTerm
	 * @param limit
	 * @param offset
	 * @return
	 * @throws IllegalAccessException
	 */
	public static List<Map<String, Object>> getProjectUsers(HttpServletRequest request, User user, String projectId,
			String searchTerm, String groupId, long limit, long offset, boolean isAdmin) throws IllegalAccessException {

		boolean graphApiUsingSystemCredentials = Boolean.parseBoolean(
				"" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_application_credentials"));

		if (!graphApiUsingSystemCredentials) {
			if (user.getAccessToken(AuthProvider.MICROSOFT) == null) {
				throw new IllegalAccessException("Must be logged into your microsoft login to search for users");
			}
		}

		HttpSession session = request.getSession(false);
		String sessionKey = MsGraphUtility.projectPrefix + projectId + "_" + searchTerm;

		// Initialize or retrieve session data
		Map<String, Object> sessionData = (Map<String, Object>) session.getAttribute(sessionKey);
		// New search if:
		// 1. No session data exists (first time searching this term), OR
		// 2. Offset is 0 (user is restarting the search)
		if (sessionData == null || offset == 0) {
			// Clear any existing data and start fresh
			sessionData = new HashMap<>();
			session.setAttribute(sessionKey, sessionData);
		}

		// Step 1: get the list of current users
		List<Map<String, Object>> currentUsers = null;
		if (isAdmin) {
			currentUsers = SecurityAdminUtils.getInstance(user).getProjectUsers(projectId, searchTerm, "", -1, -1);
		} else {
			currentUsers = SecurityProjectUtils.getProjectUsers(user, projectId, searchTerm, "", -1, -1);
		}

		final List<Map<String, Object>> finalDbUsers = currentUsers;
		String nextLink = (String) sessionData.get("nextLinkData");
		List<Map<String, Object>> msGraphUsers = new ArrayList<>();
		List<Map<String, Object>> filteredUsers = new ArrayList<>();

		try {
			MSGraphAPICall msGraphApi = new MSGraphAPICall();

			// Step 3: Fetch more data if nextLink is in the session, else make a fresh call
			// to Graph API
			if (nextLink == null || offset == 0) {
				// Make a new API call to GraphAPI if nextLink is not in the session
				String msUsers = fetchMsUsers(msGraphApi, user, groupId, searchTerm, null,
						graphApiUsingSystemCredentials);
				JSONObject jsonObject = new JSONObject(msUsers);
				JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);
				msGraphUsers = GSON.fromJson(jsonArray.toString(), List.class);

				// Store new nextLink for pagination if available
				nextLink = jsonObject.optString("@odata.nextLink", null);
				if (nextLink != null) {
					sessionData.put("nextLinkData", nextLink); // Store the nextLink in the same session attribute
				}
			} else {
				// Fetch data from GraphAPI using nextLink
				String msUsers = fetchMsUsers(msGraphApi, user, groupId, searchTerm, nextLink,
						graphApiUsingSystemCredentials);
				JSONObject jsonObject = new JSONObject(msUsers);
				JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);
				msGraphUsers = GSON.fromJson(jsonArray.toString(), List.class);

				// Update or clear nextLink based on the response
				nextLink = jsonObject.optString("@odata.nextLink", null);
				if (nextLink != null) {
					sessionData.put("nextLinkData", nextLink); // Update nextLink in the same session attribute
				} else {
					sessionData.remove("nextLinkData"); // Remove nextLink from session if no more data
				}
			}

			// Load the JSON pattern from the properties file if we want custom mapping
			String jsonPattern = SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_jsonPattern");
			final Map<String, String> mapping;
			if (jsonPattern != null && !jsonPattern.isEmpty()) {
				mapping = GSON.fromJson(jsonPattern, new TypeToken<Map<String, String>>() {
				}.getType());
			} else {
				mapping = null;
			}

			do {
				// Step 4: Compare database users with GraphAPI users and apply necessary
				// filters
				filteredUsers = msGraphUsers.stream().filter(msUser -> finalDbUsers.stream().noneMatch(dbUser -> dbUser
						.get(Constants.SMSS_USER_EMAIL).equals(msUser.get(Constants.MS_GRAPH_EMAIL))
						|| dbUser.get(Constants.SMSS_USER_NAME).equals(msUser.get(Constants.MS_GRAPH_DISPLAY_NAME))))
						.map(msUser -> {

							Map<String, Object> userMap = new HashMap<>();

							// Use the mapping pattern if it exists, otherwise use default mapping
							if (mapping != null && !mapping.isEmpty()) {
								mapping.forEach((userMapKey, msGraphKey) -> {
									userMap.put(userMapKey, msUser.get(msGraphKey));
									userMap.put(Constants.USER_MAP_TYPE, AuthProvider.MICROSOFT);
								});
							} else {
								// Default mapping if no pattern exists
								userMap.put(Constants.USER_MAP_NAME, msUser.get(Constants.MS_GRAPH_DISPLAY_NAME));
								userMap.put(Constants.USER_MAP_ID, msUser.get(Constants.MS_GRAPH_ID));
								userMap.put(Constants.USER_MAP_TYPE, AuthProvider.MICROSOFT);
								userMap.put(Constants.USER_MAP_EMAIL, msUser.get(Constants.MS_GRAPH_EMAIL));
								userMap.put(Constants.USER_MAP_USERNAME,
										msUser.get(Constants.MS_GRAPH_USER_PRINCIPAL_NAME));
							}

							return userMap;
						}).collect(Collectors.toList());

				long currentCount = filteredUsers.size();
				if (currentCount < limit && nextLink != null) {
					List<Map<String, Object>> moreUsers = fetchMsGraphUsers(user, searchTerm, groupId, sessionData,
							graphApiUsingSystemCredentials);
					filteredUsers.addAll(moreUsers);
				}

				if (filteredUsers.size() >= limit || nextLink == null) {
					return filteredUsers.subList(0, (int) Math.min(limit, filteredUsers.size()));
				}

				if (filteredUsers.size() < limit && nextLink != null) {
					long limitCount = limit - filteredUsers.size();
					List<Map<String, Object>> moreUsers = SecurityProjectUtils.getProjectUsers(user, projectId,
							searchTerm, "", limitCount, offset);
					filteredUsers.addAll(moreUsers);
				}

			} while (filteredUsers.size() < limit && nextLink != null);

		} catch (Exception e) {
			classLogger.error("Failed to fetch Microsoft Graph project users for projectId={} searchTerm={}", projectId,
					searchTerm, e);
			throw new IllegalArgumentException("An error occurred while fetching users");
		}

		return filteredUsers;
	}

	/**
	 * 
	 * @param request
	 * @param user
	 * @param engineId
	 * @param searchTerm
	 * @param limit
	 * @param offset
	 * @return
	 * @throws IllegalAccessException
	 */
	public static List<Map<String, Object>> getEngineUsers(HttpServletRequest request, User user, String engineId,
			String searchTerm, String groupId, long limit, long offset, boolean isAdmin) throws IllegalAccessException {

		boolean graphApiUsingSystemCredentials = Boolean.parseBoolean(
				"" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_application_credentials"));

		if (!graphApiUsingSystemCredentials) {
			if (user.getAccessToken(AuthProvider.MICROSOFT) == null) {
				throw new IllegalAccessException("Must be logged into your microsoft login to search for users");
			}
		}

		// Create a session and define a single session key to store everything
		HttpSession session = request.getSession(false);
		String sessionKey = enginePrefix + engineId + "_" + searchTerm;

		// Initialize or retrieve session data
		Map<String, Object> sessionData = (Map<String, Object>) session.getAttribute(sessionKey);
		// New search if:
		// 1. No session data exists (first time searching this term), OR
		// 2. Offset is 0 (user is restarting the search)
		if (sessionData == null || offset == 0) {
			// Clear any existing data and start fresh
			sessionData = new HashMap<>();
			session.setAttribute(sessionKey, sessionData);
		}

		// Step 1: Retrieve database users from session or load from DB if not available
		List<Map<String, Object>> currentUsers = null;
		if (isAdmin) {
			currentUsers = SecurityAdminUtils.getInstance(user).getEngineUsers(engineId, searchTerm, "", -1, -1);
		} else {
			currentUsers = SecurityEngineUtils.getEngineUsers(user, engineId, searchTerm, "", -1, -1);
		}

		final List<Map<String, Object>> finalDbUsers = currentUsers;
		String nextLink = (String) sessionData.get("nextLinkData");
		List<Map<String, Object>> msGraphUsers = new ArrayList<>();
		List<Map<String, Object>> filteredUsers = new ArrayList<>();

		try {
			MSGraphAPICall msGraphApi = new MSGraphAPICall();

			// Step 3: Fetch more data if nextLink is in the session, else make a fresh call
			// to Graph API
			if (nextLink == null || offset == 0) {
				// Make a new API call to GraphAPI if nextLink is not in the session
				String msUsers = fetchMsUsers(msGraphApi, user, groupId, searchTerm, null,
						graphApiUsingSystemCredentials);
				JSONObject jsonObject = new JSONObject(msUsers);
				JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);
				msGraphUsers = GSON.fromJson(jsonArray.toString(), List.class);

				// Store new nextLink for pagination if available
				nextLink = jsonObject.optString("@odata.nextLink", null);
				if (nextLink != null) {
					sessionData.put("nextLinkData", nextLink); // Store the nextLink in the same session attribute
				}
			} else {
				// Fetch data from GraphAPI using nextLink
				String msUsers = fetchMsUsers(msGraphApi, user, groupId, searchTerm, nextLink,
						graphApiUsingSystemCredentials);
				JSONObject jsonObject = new JSONObject(msUsers);
				JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);
				msGraphUsers = GSON.fromJson(jsonArray.toString(), List.class);

				// Update or clear nextLink based on the response
				nextLink = jsonObject.optString("@odata.nextLink", null);
				if (nextLink != null) {
					sessionData.put("nextLinkData", nextLink); // Update nextLink in the same session attribute
				} else {
					sessionData.remove("nextLinkData"); // Remove nextLink from session if no more data
				}
			}

			// Load the JSON pattern from the properties file if we want custom mapping
			String jsonPattern = SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_jsonPattern");
			final Map<String, String> mapping;
			if (jsonPattern != null && !jsonPattern.isEmpty()) {
				mapping = GSON.fromJson(jsonPattern, new TypeToken<Map<String, String>>() {
				}.getType());
			} else {
				mapping = null;
			}

			do {
				// Step 4: Compare database users with GraphAPI users and apply necessary
				// filters
				filteredUsers = msGraphUsers.stream().filter(msUser -> finalDbUsers.stream().noneMatch(dbUser -> dbUser
						.get(Constants.SMSS_USER_EMAIL).equals(msUser.get(Constants.MS_GRAPH_EMAIL))
						|| dbUser.get(Constants.SMSS_USER_NAME).equals(msUser.get(Constants.MS_GRAPH_DISPLAY_NAME))))
						.map(msUser -> {

							Map<String, Object> userMap = new HashMap<>();

							// Use the mapping pattern if it exists, otherwise use default mapping
							// Use the mapping pattern if it exists, otherwise use default mapping
							if (mapping != null && !mapping.isEmpty()) {
								mapping.forEach((userMapKey, msGraphKey) -> {
									userMap.put(userMapKey, msUser.get(msGraphKey));
								});
								userMap.put(Constants.USER_MAP_TYPE, AuthProvider.MICROSOFT);
							} else {
								// Default mapping if no pattern exists
								userMap.put(Constants.USER_MAP_NAME, msUser.get(Constants.MS_GRAPH_DISPLAY_NAME));
								userMap.put(Constants.USER_MAP_ID, msUser.get(Constants.MS_GRAPH_ID));
								userMap.put(Constants.USER_MAP_TYPE, AuthProvider.MICROSOFT);
								userMap.put(Constants.USER_MAP_EMAIL, msUser.get(Constants.MS_GRAPH_EMAIL));
								userMap.put(Constants.USER_MAP_USERNAME,
										msUser.get(Constants.MS_GRAPH_USER_PRINCIPAL_NAME));
							}

							return userMap;
						}).collect(Collectors.toList());
				// step 5: If nextLink was used and limitCount > 0, append the specified
				// limitCount data
				long currentCount = filteredUsers.size();
				if (currentCount < limit && nextLink != null) {
					List<Map<String, Object>> moreUsers = fetchMsGraphUsers(user, searchTerm, groupId, sessionData,
							graphApiUsingSystemCredentials);
					filteredUsers.addAll(moreUsers);
				}
				// Step 6: Return the data if the limit is reached or no more nextLink data
				if (filteredUsers.size() >= limit || nextLink == null) {
					return filteredUsers.subList(0, (int) Math.min(limit, filteredUsers.size()));
				}
				// Step 7: If the limit is not reached, calculate difference and use nextLink to
				// get more data
				if (filteredUsers.size() < limit && nextLink != null) {
					long limitCount = limit - filteredUsers.size();
					List<Map<String, Object>> moreUsers = SecurityEngineUtils.getEngineUsers(user, engineId, searchTerm,
							"", limitCount, offset);
					filteredUsers.addAll(moreUsers);
				}

			} while (filteredUsers.size() < limit && nextLink != null);

		} catch (Exception e) {
			classLogger.error("Failed to fetch Microsoft Graph engine users for engineId={} searchTerm={}", engineId,
					searchTerm, e);
			throw new IllegalArgumentException("An error occurred while fetching users");
		}

		return filteredUsers;
	}

	/**
	 * 
	 * @param user
	 * @param searchTerm
	 * @param sessionData
	 * @return
	 * @throws Exception
	 */
	public static List<Map<String, Object>> fetchMsGraphUsers(User user, String searchTerm, String groupId,
			Map<String, Object> sessionData) throws Exception {
		boolean graphApiUsingSystemCredentials = Boolean.parseBoolean(
				"" + SocialPropertiesUtil.getInstance().getProperty("ms_graphapi_application_credentials"));
		return fetchMsGraphUsers(user, searchTerm, groupId, sessionData, graphApiUsingSystemCredentials);
	}

	/**
	 * 
	 * @param user
	 * @param searchTerm
	 * @param groupId
	 * @param sessionData
	 * @param graphApiUsingSystemCredentials
	 * @return
	 * @throws Exception
	 */
	public static List<Map<String, Object>> fetchMsGraphUsers(User user, String searchTerm, String groupId,
			Map<String, Object> sessionData, boolean graphApiUsingSystemCredentials) throws Exception {
		String nextLink = (String) sessionData.get("nextLinkData");
		List<Map<String, Object>> msGraphUsers = new ArrayList<>();
		MSGraphAPICall msGraphApi = new MSGraphAPICall();

		// Make API call to GraphAPI
		String msUsers;
		if (nextLink == null) {
			// First call to fetch users
			msUsers = fetchMsUsers(msGraphApi, user, groupId, searchTerm, null, graphApiUsingSystemCredentials);
		} else {
			// Subsequent call using nextLink
			msUsers = fetchMsUsers(msGraphApi, user, groupId, searchTerm, nextLink, graphApiUsingSystemCredentials);
		}

		// Parse the response
		JSONObject jsonObject = new JSONObject(msUsers);
		JSONArray jsonArray = jsonObject.getJSONArray(Constants.MS_GRAPH_VALUE);
		msGraphUsers = GSON.fromJson(jsonArray.toString(), List.class);

		// Update nextLink for pagination
		nextLink = jsonObject.optString("@odata.nextLink", null);
		if (nextLink != null) {
			sessionData.put("nextLinkData", nextLink);
		} else {
			sessionData.remove("nextLinkData"); // Remove nextLink if no more data
		}

		return msGraphUsers;
	}

	private static String fetchMsUsers(MSGraphAPICall msGraphApi, User user, String groupId, String searchTerm,
			String nextLink, boolean graphApiUsingSystemCredentials) throws Exception {
		AccessToken requestedAccessToken = graphApiUsingSystemCredentials ? null
				: user.getAccessToken(AuthProvider.MICROSOFT);
		MSGraphAPICall.GraphApiResponse graphApiResponse = msGraphApi.getUserDetails(requestedAccessToken, groupId,
				searchTerm, nextLink);

		// Persist refreshed delegated token in the user session so subsequent calls use
		// the latest token/refresh token pair.
		if (!graphApiUsingSystemCredentials && graphApiResponse.getAccessToken() != null) {
			user.setAccessToken(graphApiResponse.getAccessToken());
		}
		return graphApiResponse.getResponseBody();
	}

}
