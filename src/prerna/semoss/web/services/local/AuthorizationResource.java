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
package prerna.semoss.web.services.local;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import com.google.gson.Gson;

import prerna.auth.User;
import prerna.auth.utils.AbstractSecurityUtils;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/authorization")
public class AuthorizationResource {

	private static final Logger logger = Logger.getLogger(AuthorizationResource.class);
	private static final String STACKTRACE = "StackTrace: ";
	@Context
	protected ServletContext context;

	@GET
	@Path("/admin/isAdminUser")
	public Response isAdminUser(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		boolean isAdmin = SecurityAdminUtils.userIsAdmin(user);
		return WebUtility.getResponse(isAdmin, 200);
	}

	@GET
	@Produces("application/json")
	@Path("searchForUser")
	public StreamingOutput searchForUser(@Context HttpServletRequest request, @QueryParam("searchTerm") String searchTerm) {
		List<Map<String, Object>> ret = SecurityQueryUtils.searchForUser(searchTerm.trim());
		return WebUtility.getSO(ret);
	}

	@POST
	@Produces("application/json")
	@Path("/admin/registerUser")
	public Response registerUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, String> errorRet = new Hashtable<>();
		boolean success = false;
		try{
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);

			String newUserId = form.getFirst("userId");
			Boolean newUserAdmin = Boolean.parseBoolean(form.getFirst("admin"));

			if(SecurityAdminUtils.userIsAdmin(user)){
				success = SecurityUpdateUtils.registerUser(newUserId, newUserAdmin, !AbstractSecurityUtils.adminSetPublisher());
			} else {
				errorRet.put("error", "The user doesn't have the permissions to perform this action.");
				return WebUtility.getResponse(errorRet, 400);
			}
		} catch (IllegalArgumentException e){
    		logger.error(STACKTRACE, e);
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
    		logger.error(STACKTRACE, e);
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}

	/**
	 * Edit user properties 
	 * @param request
	 * @param form
	 * @return true if the edition was performed
	 */
	@POST
	@Path("/editUser")
	@Produces("application/json")
	public Response editUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			Map<String, String> retMap = new Hashtable<>();
			retMap.put("error", "User does not have admin priviledges");
			return WebUtility.getResponse(retMap, 400);
		}

		Gson gson = new Gson();
		Map<String, Object> userInfo = gson.fromJson(form.getFirst("user"), Map.class);
		boolean ret = false;
		try {
			ret = adminUtils.editUser(userInfo);
		} catch(IllegalArgumentException e) {
			Map<String, String> retMap = new Hashtable<>();
			retMap.put("error", e.getMessage());
			return WebUtility.getResponse(retMap, 400);
		}
		if(!ret) {
			Map<String, String> retMap = new Hashtable<>();
			retMap.put("error", "Unknown error occured with updating user. Please try again.");
			return WebUtility.getResponse(retMap, 400);
		}
		return WebUtility.getResponse(ret, 200);
	}

	@POST
	@Produces("application/json")
	@Path("/admin/deleteUser")
	public Response deleteUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			Map<String, String> retMap = new Hashtable<>();
			retMap.put("error", "User does not have admin priviledges");
			return WebUtility.getResponse(retMap, 400);
		}

		String userToDelete = form.getFirst("userId");
		boolean success = adminUtils.deleteUser(userToDelete);
		return WebUtility.getResponse(success, 200);
	}

	@GET
	@Path("/getAllDbUsers")
	@Produces("application/json")
	public Response getAllUsers(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			Map<String, String> retMap = new Hashtable<>();
			retMap.put("error", "User does not have admin priviledges");
			return WebUtility.getResponse(retMap, 400);
		}

		List<Map<String, Object>> ret = adminUtils.getAllUsers();
		return WebUtility.getResponse(ret, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("requestAccess")
	public Response requestAccess(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		// what engine are you requesting permission from
		String engineId = form.getFirst("engineId");
		// what is the permission of the ask
		int requestedPermission = Integer.parseInt(form.getFirst("permission"));

		boolean addedRequests = true;
		try {
			addedRequests = SecurityUpdateUtils.makeRequest(user, engineId, requestedPermission);
		} catch(IllegalArgumentException e) {
    		logger.error(STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<>();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
    		logger.error(STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<>();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		return WebUtility.getResponse(addedRequests, 200);
	}

	@GET
	@Produces("application/json")
	@Path("myRequests")
	public Response getMyRequests(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}

		List<Map<String, Object>> userRequests = null;
		try {
			userRequests = SecurityQueryUtils.getUserAccessRequests(user);
		} catch(IllegalArgumentException e) {
    		logger.error(STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<>();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
    		logger.error(STACKTRACE, e);
			Map<String, String> errorRet = new HashMap<>();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}

		return WebUtility.getResponse(userRequests, 200);
	}


}