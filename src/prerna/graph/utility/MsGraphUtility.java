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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import prerna.auth.User;
import prerna.auth.utils.AdminSecurityGroupUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.io.connector.ms.MicrosoftGraphUserLookup;
import prerna.util.Constants;
import prerna.util.ValueUtils;

/**
 * Pages Microsoft Graph directory searches for the user search and sharing
 * endpoints.
 *
 * <p>
 * Graph pages are larger than the pages the endpoints return, so each search
 * keeps the users it has fetched but not returned, and the link to the next
 * Graph page, in the session. A request with offset 0 starts the search over;
 * any other offset continues it.
 * </p>
 */
public class MsGraphUtility {

	private static final Logger classLogger = LogManager.getLogger(MsGraphUtility.class);

	private static final String SESSION_PREFIX = "nld_";
	private static final String PROJECT_PREFIX = SESSION_PREFIX + "p_";
	private static final String ENGINE_PREFIX = SESSION_PREFIX + "e_";
	private static final String GROUP_PREFIX = SESSION_PREFIX + "g_";
	private static final String DIRECTORY_PREFIX = SESSION_PREFIX + "u_";

	/**
	 * Key on each directory search result: whether the user already has an account
	 * in the security database.
	 */
	public static final String HAS_ACCOUNT_KEY = "hasAccount";

	private static final String SEARCH_FAILED_MESSAGE = "Could not search your organization's directory. Try again.";

	private MsGraphUtility() {

	}

	/**
	 * Paging state for one search.
	 */
	private static class SearchState implements Serializable {
		private static final long serialVersionUID = 1L;

		private final ArrayList<Map<String, Object>> pending = new ArrayList<>();
		private String nextLink;
		private boolean started;

		private boolean isComplete() {
			return started && nextLink == null;
		}
	}

	/**
	 * Directory users who do not have access to a project yet.
	 *
	 * @param request    the request, whose session holds the paging state
	 * @param user       the signed in user
	 * @param projectId  the project
	 * @param searchTerm text to search for
	 * @param limit      the page size, or 0 or less for one Graph page
	 * @param offset     0 to start the search, anything else to continue it
	 * @param isAdmin    whether the caller is using the admin endpoints
	 * @return the users, in the SEMOSS user shape
	 * @throws IllegalAccessException when the search needs the user's Microsoft
	 *                                login and they are not signed in to Microsoft
	 */
	public static List<Map<String, Object>> getProjectUsers(HttpServletRequest request, User user, String projectId,
			String searchTerm, long limit, long offset, boolean isAdmin) throws IllegalAccessException {
		List<Map<String, Object>> currentUsers = isAdmin
				? SecurityAdminUtils.getInstance(user).getProjectUsers(projectId, searchTerm, "", -1, -1)
				: SecurityProjectUtils.getProjectUsers(user, projectId, searchTerm, "", -1, -1);
		return nextPage(request, user, PROJECT_PREFIX + projectId + "_" + searchTerm, searchTerm, limit, offset,
				excluding(currentUsers, Constants.USER_MAP_ID));
	}

	/**
	 * Directory users who do not have access to an engine yet.
	 *
	 * @param request    the request, whose session holds the paging state
	 * @param user       the signed in user
	 * @param engineId   the engine
	 * @param searchTerm text to search for
	 * @param limit      the page size, or 0 or less for one Graph page
	 * @param offset     0 to start the search, anything else to continue it
	 * @param isAdmin    whether the caller is using the admin endpoints
	 * @return the users, in the SEMOSS user shape
	 * @throws IllegalAccessException when the search needs the user's Microsoft
	 *                                login and they are not signed in to Microsoft
	 */
	public static List<Map<String, Object>> getEngineUsers(HttpServletRequest request, User user, String engineId,
			String searchTerm, long limit, long offset, boolean isAdmin) throws IllegalAccessException {
		List<Map<String, Object>> currentUsers = isAdmin
				? SecurityAdminUtils.getInstance(user).getEngineUsers(engineId, searchTerm, "", -1, -1)
				: SecurityEngineUtils.getEngineUsers(user, engineId, searchTerm, "", -1, -1);
		return nextPage(request, user, ENGINE_PREFIX + engineId + "_" + searchTerm, searchTerm, limit, offset,
				excluding(currentUsers, Constants.USER_MAP_ID));
	}

	/**
	 * Directory users who are not members of a custom group yet.
	 *
	 * @param request    the request, whose session holds the paging state
	 * @param user       the signed in admin
	 * @param groupUtils the admin group utilities
	 * @param groupId    the custom group
	 * @param searchTerm text to search for
	 * @param limit      the page size, or 0 or less for one Graph page
	 * @param offset     0 to start the search, anything else to continue it
	 * @return the users, in the SEMOSS user shape
	 * @throws IllegalAccessException when the search needs the user's Microsoft
	 *                                login and they are not signed in to Microsoft
	 */
	public static List<Map<String, Object>> getGroupUsers(HttpServletRequest request, User user,
			AdminSecurityGroupUtils groupUtils, String groupId, String searchTerm, long limit, long offset)
			throws IllegalAccessException {
		// group members carry their id as userid
		List<Map<String, Object>> currentMembers = groupUtils.getGroupMembers(groupId, searchTerm, -1, -1);
		return nextPage(request, user, GROUP_PREFIX + groupId + "_" + searchTerm, searchTerm, limit, offset,
				excluding(currentMembers, Constants.MAP_USERID));
	}

	/**
	 * Searches the whole directory. Each result carries the Graph fields, the
	 * SEMOSS user shape, and {@link #HAS_ACCOUNT_KEY}.
	 *
	 * @param request    the request, whose session holds the paging state
	 * @param user       the signed in user
	 * @param searchTerm text to search for
	 * @param limit      the page size, or 0 or less for one Graph page
	 * @param offset     0 to start the search, anything else to continue it
	 * @return the users
	 * @throws IllegalAccessException when the search needs the user's Microsoft
	 *                                login and they are not signed in to Microsoft
	 */
	public static List<Map<String, Object>> searchDirectory(HttpServletRequest request, User user, String searchTerm,
			long limit, long offset) throws IllegalAccessException {
		String sessionKey = DIRECTORY_PREFIX + User.getSingleLogginName(user) + "_" + searchTerm;
		List<Map<String, Object>> users = nextPage(request, user, sessionKey, searchTerm, limit, offset,
				graphUser -> true);
		Set<String> existingIds = MicrosoftGraphUserLookup.findExistingUserIds(users);
		for (Map<String, Object> directoryUser : users) {
			String id = ValueUtils.trimToNull(directoryUser.get(Constants.USER_MAP_ID));
			directoryUser.put(HAS_ACCOUNT_KEY, id != null && existingIds.contains(id));
		}
		return users;
	}

	/**
	 * Returns the next page of a search, fetching Graph pages until the page is
	 * full or the directory has no more matches. Without a limit, the page is
	 * whatever the next Graph page with any matches holds.
	 */
	private static List<Map<String, Object>> nextPage(HttpServletRequest request, User user, String sessionKey,
			String searchTerm, long limit, long offset, Predicate<Map<String, Object>> include)
			throws IllegalAccessException {
		HttpSession session = request.getSession();
		Object stored = offset > 0 ? session.getAttribute(sessionKey) : null;
		SearchState state = stored instanceof SearchState ? (SearchState) stored : new SearchState();

		try {
			while (!state.isComplete() && (limit <= 0 ? state.pending.isEmpty() : state.pending.size() < limit)) {
				MicrosoftGraphUserLookup.UserPage page = MicrosoftGraphUserLookup.searchUsers(user, searchTerm,
						state.nextLink);
				List<Map<String, Object>> graphUsers = page.getGraphUsers();
				List<Map<String, Object>> users = page.getUsers();
				for (int i = 0; i < users.size(); i++) {
					if (include.test(users.get(i))) {
						// keep the Graph fields alongside the SEMOSS shape
						Map<String, Object> merged = new HashMap<>(graphUsers.get(i));
						merged.putAll(users.get(i));
						state.pending.add(merged);
					}
				}
				state.nextLink = page.getNextLink();
				state.started = true;
			}
		} catch (IllegalAccessException e) {
			throw e;
		} catch (Exception e) {
			classLogger.error("Failed to search the Microsoft Graph directory for {}", sessionKey, e);
			throw new IllegalArgumentException(SEARCH_FAILED_MESSAGE);
		}

		int count = limit <= 0 ? state.pending.size() : (int) Math.min(limit, state.pending.size());
		List<Map<String, Object>> result = new ArrayList<>(state.pending.subList(0, count));
		state.pending.subList(0, count).clear();
		// set it again so a replicated session stores the change
		session.setAttribute(sessionKey, state);
		return result;
	}

	/**
	 * Matches directory users who are not in the given list, comparing ids and
	 * emails. {@code idKey} names the key that holds each listed user's id.
	 */
	private static Predicate<Map<String, Object>> excluding(List<Map<String, Object>> users, String idKey) {
		Set<String> ids = new HashSet<>();
		Set<String> emails = new HashSet<>();
		for (Map<String, Object> user : users) {
			String id = ValueUtils.trimToNull(user.get(idKey));
			if (id != null) {
				ids.add(id);
			}
			String email = normalizeEmail(user.get(Constants.SMSS_USER_EMAIL));
			if (email != null) {
				emails.add(email);
			}
		}
		return graphUser -> {
			String id = ValueUtils.trimToNull(graphUser.get(Constants.USER_MAP_ID));
			String email = normalizeEmail(graphUser.get(Constants.USER_MAP_EMAIL));
			return (id == null || !ids.contains(id)) && (email == null || !emails.contains(email));
		};
	}

	private static String normalizeEmail(Object email) {
		String trimmed = ValueUtils.trimToNull(email);
		return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
	}

}
