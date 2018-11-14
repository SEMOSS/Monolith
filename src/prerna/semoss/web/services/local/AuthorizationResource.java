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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import com.google.gson.Gson;
import com.google.gson.internal.StringMap;

import prerna.auth.User;
import prerna.auth.utils.SecurityAdminUtils;
import prerna.auth.utils.SecurityQueryUtils;
import prerna.auth.utils.SecurityUpdateUtils;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/authorization")
public class AuthorizationResource {
	
	/**
	 * Get the user
	 * @param request
	 * @return
	 * @throws IOException
	 */
	private User getUser(@Context HttpServletRequest request) throws IllegalAccessException {
		HttpSession session = request.getSession(false);
		if(session == null){
			throw new IllegalAccessException("User session is invalid");
		}
		
		User user = (User) session.getAttribute(Constants.SESSION_USER);
		if(user == null) {
			throw new IllegalAccessException("User session is invalid");
		}
		
		return user;
	}
	
	//////////////////////////////////////////////
	//////////////////////////////////////////////
	
	/*
	 * General methods
	 */
	
	@GET
	@Produces("application/json")
	@Path("searchForUser")
	public StreamingOutput searchForUser(@Context HttpServletRequest request, @QueryParam("searchTerm") String searchTerm) {
		List<Map<String, String>> ret = SecurityQueryUtils.searchForUser(searchTerm.trim());
		return WebUtility.getSO(ret);
	}
	
	/**
	 * Get databases the user has access to
	 * @param request
	 * @return Databases the user has access to
	 */
	@GET
	@Produces("application/json")
	@Path("getDatabases")
	public Response getUserDatabaseSettings(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		return WebUtility.getResponse(SecurityQueryUtils.getAllUserDatabaseSettings(user), 200);
	}
	
	//////////////////////////////////////////////
	//////////////////////////////////////////////

	/*
	 * Admin methods
	 */
	
	@GET
	@Path("/admin/isAdminUser")
	public Response isAdminUser(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		boolean isAdmin = SecurityAdminUtils.userIsAdmin(user);
		return WebUtility.getResponse(isAdmin, 200);
	}
	
	// TODO: why is this not /admin!!!
	@GET
	@Path("/getAllDbUsers")
	@Produces("application/json")
	public Response getAllUsers(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			Map<String, String> retMap = new Hashtable<String, String>();
			retMap.put("error", "User does not have admin priviledges");
			return WebUtility.getResponse(retMap, 400);
		}

		List<Map<String, Object>> ret = adminUtils.getAllUsers();
		return WebUtility.getResponse(ret, 200);
	}
	
	// TODO: why is this not /admin!!!
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
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			Map<String, String> retMap = new Hashtable<String, String>();
			retMap.put("error", "User does not have admin priviledges");
			return WebUtility.getResponse(retMap, 400);
		}
		
		Gson gson = new Gson();
		Map<String, Object> userInfo = gson.fromJson(form.getFirst("user"), Map.class);
		boolean ret = false;
		try {
			ret = adminUtils.editUser(userInfo);
		} catch(IllegalArgumentException e) {
			Map<String, String> retMap = new Hashtable<String, String>();
			retMap.put("error", e.getMessage());
			return WebUtility.getResponse(retMap, 400);
		}
		if(ret = false) {
			Map<String, String> retMap = new Hashtable<String, String>();
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
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			Map<String, String> retMap = new Hashtable<String, String>();
			retMap.put("error", "User does not have admin priviledges");
			return WebUtility.getResponse(retMap, 400);
		}

		String userToDelete = form.getFirst("userId");
		boolean success = adminUtils.deleteUser(userToDelete);
		return WebUtility.getResponse(success, 200);
	}
	
	/**
	 * Get databases the user has access to
	 * @param request
	 * @return Databases the user has access to
	 */
	@GET
	@Produces("application/json")
	@Path("/admin/getDatabases")
	public Response getAdminDatabaseSettings(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			Map<String, String> retMap = new Hashtable<String, String>();
			retMap.put("error", "User does not have admin priviledges");
			return WebUtility.getResponse(retMap, 400);
		}
		
		return WebUtility.getResponse(adminUtils.getAllUserDatabaseSettings(), 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/admin/setDbPublic")
	public Response setAdminDbPublic(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		SecurityAdminUtils adminUtils = SecurityAdminUtils.getInstance(user);
		if(adminUtils == null) {
			Map<String, String> retMap = new Hashtable<String, String>();
			retMap.put("error", "User does not have admin priviledges");
			return WebUtility.getResponse(retMap, 400);
		}
		
		String engineId = form.getFirst("engineId");
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		adminUtils.setDbGlobal(engineId, isPublic);
		return WebUtility.getResponse(true, 200);
	}
	
	/////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////	
	
	@POST
	@Produces("application/json")
	@Path("setDbPublic")
	public Response setDbGlobal(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String engineId = form.getFirst("engineId");
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		try {
			SecurityUpdateUtils.setDbGlobal(user, engineId, isPublic);
		} catch(IllegalArgumentException e) {
			e.printStackTrace();
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			e.printStackTrace();
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		return WebUtility.getResponse(true, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("setDbVisibility")
	public Response setDbVisibility(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String engineId = form.getFirst("engineId");
		boolean isPublic = Boolean.parseBoolean(form.getFirst("visibility"));
		try {
			SecurityUpdateUtils.setDbVisibility(user, engineId, isPublic);
		} catch(IllegalArgumentException e) {
			e.printStackTrace();
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			e.printStackTrace();
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		return WebUtility.getResponse(true, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("setDbPublic")
	public Response setDbPublic(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String engineId = form.getFirst("engineId");
		
		boolean isPublic = Boolean.parseBoolean(form.getFirst("public"));
		try {
			SecurityUpdateUtils.setDbGlobal(user, engineId, isPublic);
		} catch(IllegalArgumentException e) {
			e.printStackTrace();
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			e.printStackTrace();
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		return WebUtility.getResponse(true, 200);
	} 
	
	@POST
	@Produces("application/json")
	@Path("requestAccess")
	public Response requestAccess(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		User user = null;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
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
			e.printStackTrace();
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			e.printStackTrace();
			Map<String, String> errorRet = new HashMap<String, String>();
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
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		List<Map<String, Object>> userRequests = null;
		try {
			userRequests = SecurityQueryUtils.getUserAccessRequests(user);
		} catch(IllegalArgumentException e) {
			e.printStackTrace();
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			e.printStackTrace();
			Map<String, String> errorRet = new HashMap<String, String>();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		return WebUtility.getResponse(userRequests, 200);
	}
	
	/////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////

	
	@POST
	@Produces("application/json")
	@Path("/admin/registerUser")
	public Response registerUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		boolean success = false;
		try{
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			
			String newUserId = form.getFirst("userId");
			Boolean newUserAdmin = Boolean.parseBoolean(form.getFirst("admin"));

			if(SecurityAdminUtils.userIsAdmin(user)){
				success = SecurityUpdateUtils.registerUser(newUserId, newUserAdmin);
			} else {
				errorRet.put("error", "The user doesn't have the permissions to perform this action.");
				return WebUtility.getResponse(errorRet, 400);
			}
		} catch (IllegalArgumentException e){
			e.printStackTrace();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			e.printStackTrace();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(success, 200);
	}

	@POST
	@Produces("application/json")
	@Path("/admin/savePermissions")
	public Response savePermissionsAdmin(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		Map<String, String> errorRet = new Hashtable<String, String>();
		
		try{
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			String userId = user.getAccessToken(user.getLogins().get(0)).getId();
			String engineId = form.getFirst("engineId").trim();
			Map<String, List<Map<String, String>>> groups = gson.fromJson(form.getFirst("groups"), StringMap.class);
			Map<String, List<Map<String, String>>> users = gson.fromJson(form.getFirst("users"), StringMap.class);
			SecurityUpdateUtils.savePermissions(userId, true, engineId, groups, users);
		} catch(IllegalArgumentException e){
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch(Exception e){
			e.printStackTrace();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		return WebUtility.getResponse(true, 200);
	}
	
	
	//////////////////////////////////////////////
	//////////////////////////////////////////////
	
	
	/**
	 * Get the groups and users directly associated with 
	 * the database requested that the user has access to.
	 * @param request
	 * @return Groups and user of a specific database
	 */
	@POST
	@Produces("application/json")
	@Path("getDatabaseUsersAndGroups")
	public Response getDatabaseUsersAndGroups(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		Map<String, List<Map<String, String>>>  ret = new HashMap<>();
		try{
			String engineId = form.getFirst("engineId");
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			String userId = user.getAccessToken(user.getLogins().get(0)).getId();
			ret = SecurityQueryUtils.getDatabaseUsersAndGroups(userId, engineId, false);
			return WebUtility.getResponse(ret, 200);
		} catch (IllegalArgumentException e){
			e.printStackTrace();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		} catch (Exception e){
			e.printStackTrace();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
	}

	
	@POST
	@Produces("application/json")
	@Path("savePermissions")
	public Response savePermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		try{ 
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			String userId = user.getAccessToken(user.getLogins().get(0)).getId();
			String engineId = form.getFirst("engineId").trim();
			Map<String, List<Map<String, String>>> groups = gson.fromJson(form.getFirst("groups"), StringMap.class);
			Map<String, List<Map<String, String>>> users = gson.fromJson(form.getFirst("users"), StringMap.class);
			SecurityUpdateUtils.savePermissions(userId, false, engineId, groups, users);
		} catch(IllegalArgumentException e){
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch(Exception e){
			e.printStackTrace();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		
		return WebUtility.getResponse(true, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("isUserGroupAddedValid")
	public Response isUserGroupAddedValid(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		try {
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			String userId = user.getAccessToken(user.getLogins().get(0)).getId();
			String ret = "true";
			
			userId = form.getFirst("userIdAdd").trim();
			String groupId = form.getFirst("groupIdAdd").trim();
			String engineId = form.getFirst("engineId").trim();
			
			Map<String, List<Map<String, String>>> groups = gson.fromJson(form.getFirst("groups"), StringMap.class);
			List<Map<String, String>> groupsToAddMap = groups.get("add");
			List<String> groupsToAdd = convertListMapToList(groupsToAddMap);
			List<Map<String, String>> groupsToRemoveMap = groups.get("remove");
			List<String> groupsToRemove = convertListMapToList(groupsToRemoveMap);
			
			Map<String, List<Map<String, String>>> users = gson.fromJson(form.getFirst("users"), StringMap.class);
			List<Map<String, String>> usersToAddMap = users.get("add");
			List<String> usersToAdd = convertListMapToList(usersToAddMap);
			List<Map<String, String>> usersToRemoveMap = users.get("remove");
			List<String> usersToRemove = convertListMapToList(usersToRemoveMap);
			
			List<String> groupsFinal = SecurityQueryUtils.getAllDbGroupsById(engineId, groupsToAdd, groupsToRemove);
			List<String> usersFinal = SecurityQueryUtils.getAllDbUsersById(engineId, usersToAdd, usersToRemove);
			
			if(!userId.isEmpty()){
				ret = SecurityQueryUtils.isUserWithDatabasePermissionAlready(userId, groupsFinal, usersFinal);
			} else if(!groupId.isEmpty()){
				ret = SecurityQueryUtils.isGroupUsersWithDatabasePermissionAlready(groupId, groupsFinal, usersFinal);
			} 
			
			if(ret.equals("true")){
				return WebUtility.getResponse(ret, 200);
			} else {
				return WebUtility.getResponse(ret, 400);
			}
		} catch(Exception ex) {
			return WebUtility.getResponse("An unexpected error happened. Please try again.", 500);
		}		
	}
	
	private List<String> convertListMapToList(List<Map<String, String>> set){
		List<String> array = new ArrayList<>();
		for(Map<String, String> map : set){
			array.add(map.get("id"));
		}
		return array;
	}
	
	@POST
	@Produces("application/json")
	@Path("isUserAddedToGroupValid")
	public Response isUserAddedToGroupValid(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		try {
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			String userId = user.getAccessToken(user.getLogins().get(0)).getId();
			String ret = "";
			
			String userIdAdd = form.getFirst("userIdAdd").trim();
			String groupId = form.getFirst("groupId").trim();
			
			ret += SecurityQueryUtils.isUserAddedToGroupValid(userIdAdd, groupId);
			
			if(ret.equals("true")){
				return WebUtility.getResponse(ret, 200);
			} else {
				return WebUtility.getResponse(ret, 400);
			}
		} catch(Exception ex) {
			return WebUtility.getResponse("An unexpected error happened. Please try again.", 500);
		}		
	}
	
	@POST
	@Path("/removeDatabaseAccess")
	@Produces("application/json")
	public Response removeUserPermissionsbyDbId(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		boolean ret = false;
		Map<String, String> errorRet = new Hashtable<String, String>();
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String userId = user.getAccessToken(user.getLogins().get(0)).getId();
		String engineId = form.getFirst("engineId");
		try{
			ret = SecurityUpdateUtils.removeUserPermissionsbyDbId(userId, engineId);
		} catch (IllegalArgumentException e){
			e.printStackTrace();
			errorRet.put("error", e.getMessage());
		} catch (Exception e){
			e.printStackTrace();
			errorRet.put("error", "An unexpected error happened. Please try again.");
		}
		return WebUtility.getResponse(ret, 200);
	}
	
	/////////////////////////////////////////////////
	/////////////////////////////////////////////////

	/*
	 * Groups
	 */
	
	/**
	 * Get groups and user of database
	 * @param request
	 * @return Groups and user of a specific database
	 */
	@POST
	@Produces("application/json")
	@Path("/admin/getDatabaseUsersAndGroups")
	public Response getAdminDatabaseUsersAndGroups(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		Map<String, List<Map<String, String>>>  ret = new HashMap<>();
		try{
			User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
			String userId = user.getAccessToken(user.getLogins().get(0)).getId();
			String engineId = form.getFirst("engineId");
			ret = SecurityQueryUtils.getDatabaseUsersAndGroups(userId, engineId, true);
			return WebUtility.getResponse(ret, 200);
		} catch (IllegalArgumentException e){
			e.printStackTrace();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 500);
		} catch (Exception e){
			e.printStackTrace();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
	}
	
	/**
	 * Get all the grous the user is part of.
	 * @param request
	 * @return get all groups the user is part of
	 */
	@GET
	@Produces("application/json")
	@Path("getGroups")
	public Response getGroupsAndMembers(@Context HttpServletRequest request) {
		User user = null;
		try {
			user = getUser(request);
		} catch (IllegalAccessException e) {
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", e.getMessage());
			return WebUtility.getResponse(errorMap, 401);
		}
		
		String userId = user.getAccessToken(user.getLogins().get(0)).getId();
		List<Map<String, Object>> ret = SecurityQueryUtils.getGroupsAndMembersForUser(userId);
		return WebUtility.getResponse(ret, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("addGroup")
	public Response addNewGroup(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		List<String> users = gson.fromJson(form.getFirst("users"), ArrayList.class);
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String userId = user.getAccessToken(user.getLogins().get(0)).getId();
		Boolean success = SecurityUpdateUtils.addGroup(userId, form.getFirst("groupName").trim(), users);
		
		if(success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);
		}
	}
	
	@POST
	@Produces("application/json")
	@Path("removeGroup")
	public Response removeGroup(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String groupId = form.getFirst("groupId").trim();
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String userId = user.getAccessToken(user.getLogins().get(0)).getId();
		Boolean success = SecurityUpdateUtils.removeGroup(userId, groupId);
		
		if(success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 400);
		}
	}
	
	@POST
	@Produces("application/json")
	@Path("editGroup")
	public Response editGroup(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		
		String groupId = form.getFirst("groupId").trim();
		List<String> toAdd = gson.fromJson(form.getFirst("add"), ArrayList.class);
		List<String> toRemove = gson.fromJson(form.getFirst("remove"), ArrayList.class);
		
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		String userId = user.getAccessToken(user.getLogins().get(0)).getId();
		
		for(String add : toAdd) {
			SecurityUpdateUtils.addUserToGroup(userId, groupId, add);
		}
		
		for(String remove : toRemove) {
			SecurityUpdateUtils.removeUserFromGroup(userId, groupId, remove);
		}
		return WebUtility.getResponse(true, 200);
	}
	
	
	
	///////////////////////////////////////////
	///////////////////////////////////////////
	///////////////////////////////////////////
	///////////////////////////////////////////

	/*
	 * LEGACY CODE THAT DOESN'T HAVE AN EQUIVALENT IN NEW SECURITY SCHEME YET
	 */
	
//	@GET
//	@Produces("application/json")
//	@Path("getInsightPermissions")
//	public Response getInsightPermissions(@Context HttpServletRequest request, @QueryParam("database") String databaseName, @QueryParam("insight") String insightId) {
//		return WebUtility.getResponse(permissions.getUserPermissionsForInsight(databaseName, insightId), 200);
//	}
//	
//	
//	@GET
//	@Produces("application/json")
//	@Path("/admin/getAllSeedsForUser")
//	public Response getAllSeedsForUser(@Context HttpServletRequest request, @QueryParam("userId") String userId) {
////		return Response.status(200).entity(WebUtility.getSO(permissions.getMetamodelSeedsForUser(userId, false))).build();
//		return WebUtility.getResponse(permissions.getMetamodelSeedsForUser(userId, false), 200);
//	}
	
//	@GET
//	@Path("/startTeamSession")
//	@Produces("application/json")
//	public Response startTeamSession(@Context HttpServletRequest request, @QueryParam("insightId") String insightId, @QueryParam("teamId") String teamId) {
//		StringMap retData = new StringMap<String>();
//		StringMap<StringMap<String>> teamShareMaps = new StringMap<StringMap<String>>();
//		
//		//Get the existing team share mappings, if they exist
//		if(DIHelper.getInstance().getLocalProp("teamShareMaps") != null) {
//			teamShareMaps = (StringMap<StringMap<String>>) DIHelper.getInstance().getLocalProp("teamShareMaps");
//		}
//		
//		if(teamShareMaps.containsKey(teamId)) {
//			retData.put("success", false);
////			return Response.status(400).entity(WebUtility.getSO(retData)).build();
//			return WebUtility.getResponse(retData, 200);
//		} else {
//			StringMap<String> newTeamShareMap = new StringMap<String>();
//			newTeamShareMap.put("insightId", insightId);
//			newTeamShareMap.put("owner", ((User)request.getSession().getAttribute(Constants.SESSION_USER)).getId());
//			teamShareMaps.put(teamId, newTeamShareMap);
//		}
//		DIHelper.getInstance().setLocalProperty("teamShareMaps", teamShareMaps);
//		
//		retData.put("success", true);
////		return Response.status(200).entity(WebUtility.getSO(retData)).build();
//		return WebUtility.getResponse(retData, 200);
//	}
//	
//	@GET
//	@Path("/endTeamSession")
//	@Produces("application/json")
//	public Response endTeamSession(@Context HttpServletRequest request, @QueryParam("teamId") String teamId) {
//		StringMap retData = new StringMap<String>();
//		String loggedInUser = ((User)request.getSession().getAttribute(Constants.SESSION_USER)).getId();
//		StringMap<StringMap<String>> teamShareMaps = new StringMap<StringMap<String>>();
//		
//		//Get the existing team share mappings, if they exist
//		if(DIHelper.getInstance().getLocalProp("teamShareMaps") != null) {
//			teamShareMaps = (StringMap<StringMap<String>>) DIHelper.getInstance().getLocalProp("teamShareMaps");
//		}
//		
//		//If the map doesn't exist, or the teamId isn't in the map, or the logged in user isn't the owner of the team session, don't do anything
//		if(teamShareMaps == null || !teamShareMaps.containsKey(teamId) || !teamShareMaps.get(teamId).get("owner").equals(loggedInUser)) {
//			retData.put("success", false);
////			return Response.status(400).entity(WebUtility.getSO(retData)).build();
//			return WebUtility.getResponse(retData, 200);
//		}
//		
//		//Remove the team session information and replace the map in DIHelper
//		teamShareMaps.remove(teamId);
//		DIHelper.getInstance().setLocalProperty("teamShareMaps", teamShareMaps);
//		
//		retData.put("success", true);
////		return Response.status(200).entity(WebUtility.getSO(retData)).build();
//		return WebUtility.getResponse(retData, 200);
//	}


}