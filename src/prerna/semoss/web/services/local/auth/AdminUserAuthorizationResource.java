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
package prerna.semoss.web.services.local.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/auth/admin/user")
@PermitAll
public class AdminUserAuthorizationResource extends AbstractAdminResource {

	private static final Logger classLogger = LogManager.getLogger(AdminUserAuthorizationResource.class);

	@GET
	@Path("/isAdminUser")
	@Produces("application/json")
	public Response isAdminUser(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		boolean isAdmin = SecurityAdminUtils.userIsAdmin(user);
		return WebUtility.getResponse(isAdmin, 200);
	}

	@POST
	@Path("/registerUser")
	@Produces("application/json")
	public Response registerUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put(Constants.ERROR_MESSAGE, "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		Map<String, String> errorRet = new HashMap<>();
		if (!SecurityAdminUtils.userIsAdmin(user)) {
			errorRet.put(Constants.ERROR_MESSAGE, "The user doesn't have the permissions to perform this action.");
			return WebUtility.getResponse(errorRet, 400);
		}

		boolean success = false;
		try {
			String newUserId = form.getFirst("userId");
			if (newUserId == null || newUserId.isEmpty()) {
				throw new IllegalArgumentException("The user id cannot be null or empty");
			}
			String type = WebUtility.inputSanitizer(form.getFirst("type"));
			String name = WebUtility.inputSQLSanitizer(form.getFirst("name"));
			String email = WebUtility.inputSQLSanitizer(form.getFirst("email"));
			String phone = WebUtility.inputSanitizer(request.getParameter("phone"));
			String phoneExtension = WebUtility.inputSanitizer(request.getParameter("phoneextension"));
			String countryCode = WebUtility.inputSanitizer(request.getParameter("countrycode"));
			Boolean newUserAdmin = Boolean.parseBoolean(form.getFirst("admin"));
			Boolean publisher = Boolean.parseBoolean(form.getFirst("publisher"));
			Boolean exporter = Boolean.parseBoolean(form.getFirst("exporter"));
			String password = WebUtility.inputSQLSanitizer(form.getFirst("password"));

			// model restrictions
			String modelUsageRestriction = WebUtility.inputSQLSanitizer(form.getFirst("modelUsageRestriction"));
			String modelUsageFrequency = WebUtility.inputSQLSanitizer(form.getFirst("modelUsageFrequency"));
			Integer modelMaxTokens = null;
			String modelMaxTokensStr = form.getFirst("modelMaxTokens");
			if (modelMaxTokensStr != null && !(modelMaxTokensStr = modelMaxTokensStr.trim()).isEmpty()) {
				try {
					modelMaxTokens = Integer.parseInt(modelMaxTokensStr);
				} catch (NumberFormatException e) {
					classLogger.error("Failed to register user.", e);
					throw new IllegalArgumentException("modelMaxTokens must be a valid Integer value");
				}
			}
			Double modelMaxResponseTime = null;
			String modelMaxResponseTimeStr = form.getFirst("modelMaxResponseTime");
			if (modelMaxResponseTimeStr != null
					&& !(modelMaxResponseTimeStr = modelMaxResponseTimeStr.trim()).isEmpty()) {
				try {
					modelMaxResponseTime = Double.parseDouble(modelMaxResponseTimeStr);
				} catch (NumberFormatException e) {
					classLogger.error("Failed to register user.", e);
					throw new IllegalArgumentException("modelMaxResponseTime must be a valid Number value");
				}
			}

			// validate email & password
			if (email != null && !email.isEmpty()) {
				try {
					AbstractSecurityUtils.validEmail(email, true);
				} catch (Exception e) {
					Map<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(errorMap, 401);
				}
			}
			if (phone != null && !phone.isEmpty()) {
				try {
					phone = AbstractSecurityUtils.formatPhone(phone);
				} catch (Exception e) {
					Map<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(errorMap, 401);
				}
			}
			// password is only defined if native type
			if (password != null && !password.isEmpty()) {
				try {
					AbstractSecurityUtils.validPassword(newUserId, AuthProvider.NATIVE, password);
				} catch (Exception e) {
					Map<String, String> errorMap = new HashMap<>();
					errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
					return WebUtility.getResponse(errorMap, 401);
				}
			}

			success = SecurityUpdateUtils.registerUser(newUserId, name, email, password, type, phone, phoneExtension,
					countryCode, newUserAdmin, publisher, exporter, modelUsageRestriction, modelUsageFrequency,
					modelMaxTokens, modelMaxResponseTime);
		} catch (IllegalArgumentException e) {
			classLogger.error("Failed to register user.", e);
			errorRet.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e) {
			classLogger.error("Failed to register user.", e);
			errorRet.put(Constants.ERROR_MESSAGE, "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}

	/**
	 * Set user as publisher
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Path("/setUserPublisher")
	@Produces("application/json")
	public Response setUserPublisher(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to update user publisher.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		boolean isPublisher = Boolean.parseBoolean(form.getFirst("isPublisher"));

		try {
			adminUtils.setUserPublisher(userId, isPublisher);
		} catch (Exception e) {
			classLogger.error("Failed to update user publisher.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Set user as locked/unlocked
	 * 
	 * @param request
	 * @param form
	 * @return
	 */
	@POST
	@Path("/setUserLocked")
	@Produces("application/json")
	public Response setUserLocked(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to update user locked.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String userId = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String type = WebUtility.inputSQLSanitizer(form.getFirst("type"));
		boolean isLocked = Boolean.parseBoolean(form.getFirst("isLocked"));

		try {
			adminUtils.setUserLock(userId, type, isLocked);
		} catch (Exception e) {
			classLogger.error("Failed to update user locked.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 400);
		}

		Map<String, Object> ret = new HashMap<String, Object>();
		ret.put("success", true);
		return WebUtility.getResponse(ret, 200);
	}

	/**
	 * Edit user properties
	 * 
	 * @param request
	 * @param form
	 * @return true if the edition was performed
	 */
	@POST
	@Path("/editUser")
	@Produces("application/json")
	public Response editUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to update user.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Gson gson = new Gson();
		Map<String, Object> userInfo = gson.fromJson(form.getFirst("user"), Map.class);

		Boolean adminChange = null;
		if (userInfo.containsKey("admin")) {
			if (userInfo.get("admin") instanceof Number) {
				adminChange = ((Number) userInfo.get("admin")).intValue() == 1;
				classLogger.info("User has edited user {} to admin level {}", userInfo.get("id"),
						userInfo.get("admin"));
			} else {
				adminChange = Boolean.parseBoolean(userInfo.get("admin") + "");
				classLogger.info("User has edited user {} to admin level {}", userInfo.get("id"),
						userInfo.get("admin"));
			}
		}

		if (adminChange != null && !adminChange) {
			// if you are making this user not an admin
			// need to make sure they are not the last admin for the instance
			synchronized (AdminUserAuthorizationResource.class) {
				int numAdmins = adminUtils.getNumAdmins();
				if (numAdmins == 1) {
					Object[] adminUser = adminUtils.getAdminUserIdAndType();
					String thisUserId = userInfo.get("id") + "";
					String thisUserType = userInfo.get("type") + "";
					if (thisUserId.equals(adminUser[0]) && thisUserType.equals(adminUser[1])) {
						Map<String, String> errorMap = new HashMap<String, String>();
						errorMap.put(Constants.ERROR_MESSAGE,
								"You cannot remove the last admin from having admin level permissions. Please assign a new admin before removing admin access.");
						return WebUtility.getResponse(errorMap, 400);
					}
				}
			}
		}

		boolean ret = false;
		try {
			ret = adminUtils.editUser(userInfo);
		} catch (IllegalArgumentException e) {
			Map<String, String> retMap = new HashMap<>();
			retMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(retMap, 400);
		}
		if (!ret) {
			Map<String, String> retMap = new HashMap<>();
			retMap.put(Constants.ERROR_MESSAGE, "Unknown error occurred with updating user. Please try again.");
			return WebUtility.getResponse(retMap, 400);
		}
		return WebUtility.getResponse(ret, 200);
	}

	@POST
	@Path("/deleteUser")
	@Produces("application/json")
	public Response deleteUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to delete user.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		String userIdToDelete = WebUtility.inputSQLSanitizer(form.getFirst("userId"));
		String userTypeToDelete = WebUtility.inputSQLSanitizer(form.getFirst("type"));

		boolean isDeletedUserAdmin = adminUtils.userIsAdmin(userIdToDelete, userTypeToDelete);
		if (isDeletedUserAdmin) {
			// need to make sure there are other admins and we are not deleting the last
			// admin
			if (!adminUtils.otherAdminsExist(userIdToDelete, userTypeToDelete)) {
				Map<String, String> errorMap = new HashMap<String, String>();
				errorMap.put(Constants.ERROR_MESSAGE,
						"You cannot delete this user as it is the last admin. Please assign a new admin before deleting this user.");
				return WebUtility.getResponse(errorMap, 400);
			}
		}

		// log the operation
		classLogger.info("User has deleted user {} with provider {}", userIdToDelete, userTypeToDelete);

		boolean success = adminUtils.deleteUser(userIdToDelete, userTypeToDelete);
		return WebUtility.getResponse(success, 200);
	}

	@GET
	@Path("/getAllDbUsers")
	@Produces("application/json")
	@Deprecated
	/**
	 * PLEASE USE
	 * {@link AdminUserAuthorizationResource#getAllUsers(HttpServletRequest)}
	 * 
	 * @param request
	 * @return
	 */
	public Response getAllDbUsers(@Context HttpServletRequest request) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to retrieve all db users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = adminUtils.getAllUsers(null, -1, -1);
		return WebUtility.getResponse(ret, 200);
	}

	@GET
	@Path("/getAllUsers")
	@Produces("application/json")
	public Response getAllUsers(@Context HttpServletRequest request, @QueryParam("filterWord") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to search term.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = adminUtils.getAllUsers(searchTerm, limit, offset);
		return WebUtility.getResponse(ret, 200);
	}

	@GET
	@Path("/getNumUsers")
	@Produces("application/json")
	public Response getNumUsers(@Context HttpServletRequest request, @QueryParam("filterWord") String searchTerm) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to retrieve num users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Long ret = adminUtils.getNumUsers(searchTerm);
		return WebUtility.getResponse(ret, 200);
	}

	@GET
	@Path("/getAllAPIUsers")
	@Produces("application/json")
	public Response getAllAPIUsers(@Context HttpServletRequest request, @QueryParam("filterWord") String searchTerm,
			@QueryParam("limit") long limit, @QueryParam("offset") long offset) {
		searchTerm = WebUtility.inputSQLSanitizer(searchTerm);
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to search term.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> ret = adminUtils.getAllAPIUsers(searchTerm, limit, offset);
		return WebUtility.getResponse(ret, 200);
	}

	@GET
	@Path("/getNumAPIUsers")
	@Produces("application/json")
	public Response getNumAPIUsers(@Context HttpServletRequest request, @QueryParam("filterWord") String searchTerm) {
		SecurityAdminUtils adminUtils = null;
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
			adminUtils = performAdminCheck(request, user);
		} catch (IllegalAccessException e) {
			classLogger.error("Failed to retrieve num users.", e);
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}

		Long ret = adminUtils.getNumAPIUsers(searchTerm);
		return WebUtility.getResponse(ret, 200);
	}

}
