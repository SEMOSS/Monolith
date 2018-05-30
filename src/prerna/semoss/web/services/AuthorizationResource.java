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
package prerna.semoss.web.services;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import javax.servlet.ServletContext;
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

import prerna.auth.AuthProvider;
import prerna.auth.EngineAccessRequest;
import prerna.auth.User;
import prerna.auth.User2;
import prerna.auth.UserPermissionsMasterDB;
import prerna.auth.EnginePermission;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.services.util.WebUtility;

@Path("/authorization")
public class AuthorizationResource
{
	@Context ServletContext context;
	String output = "";
	UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
	
	/**
	 * Check if the security is enabled in the application.
	 * @return true or false
	 */
	@GET
	@Produces("application/json")
	@Path("securityEnabled")
	public StreamingOutput isSecurityEnabled() {
		boolean securityEnabled = Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED));
		return WebUtility.getSO(securityEnabled);
	}
	
	/**
	 * Returns a list of engines the currently logged in user can access on the DB Admin page.
	 */
	@GET
	@Produces("application/json")
	@Path("dbAdminEngines")
	public StreamingOutput getDBAdminEngines(@Context HttpServletRequest request) throws IOException {
		Hashtable<String, ArrayList<Hashtable<String, String>>> ret = new Hashtable<String, ArrayList<Hashtable<String, String>>>();
		ArrayList<Hashtable<String, String>> allEngines = new ArrayList<Hashtable<String, String>>((ArrayList<Hashtable<String, String>>)request.getSession().getAttribute(Constants.ENGINES));
		
		if(!Boolean.parseBoolean(context.getInitParameter(Constants.SECURITY_ENABLED))) {
			ret.put("engines", allEngines);
			return WebUtility.getSO(ret);
		}
		
		EnginePermission[] permissionsList = new EnginePermission[] { EnginePermission.OWNER };
		
		ArrayList<String> accessibleEngines = permissions.getEnginesForUserAndPermissions(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), permissionsList);
		ArrayList<Hashtable<String, String>> engines = new ArrayList<Hashtable<String, String>>();
		
		for(Hashtable<String, String> eng : allEngines) {
			if(accessibleEngines.contains(eng.get("name"))) {
				engines.add(eng);
			}
		}
		
		ret.put("engines", engines);
		return WebUtility.getSO(ret);
	}
	
	@GET
	@Produces("application/json")
	@Path("getEngineAccessRequests")
	public Response getEngineAccessRequestsForUser(@Context HttpServletRequest request) throws IOException {
		Hashtable<String, ArrayList<Hashtable<String, Object>>> ret = new Hashtable<String, ArrayList<Hashtable<String, Object>>>();
		ArrayList<Hashtable<String, Object>> requests = new ArrayList<Hashtable<String,Object>>();
		Hashtable<String, Object> requestdetails;
		
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(user != null && !user.getId().equals(Constants.ANONYMOUS_USER_ID)) {
			ArrayList<EngineAccessRequest> reqs = permissions.getEngineAccessRequestsForUser(user.getId());
			ArrayList<String> allPermissionsList = new ArrayList<String>();
//			for(EnginePermission ep : EnginePermission.values()) {
//				allPermissionsList.add(ep.getPermission());
//			}
			allPermissionsList.add(EnginePermission.READ_ONLY.getPermission());
			for(EngineAccessRequest req : reqs) {
				requestdetails = new Hashtable<String, Object>();
				requestdetails.put("requestId", req.getRequestId());
				requestdetails.put("engine", req.getEngineRequested());
				requestdetails.put("user", req.getUser());
				requestdetails.put("allpermissions", allPermissionsList);
				requests.add(requestdetails);
			}
		}
		
		ret.put("requests", requests);
//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
		return WebUtility.getResponse(ret, 200);
	}
	
	@GET
	@Produces("application/json")
	@Path("getEngineAccessRequestsByUser")
	public StreamingOutput getEngineAccessRequestsByUser(@Context HttpServletRequest request) throws IOException {
		ArrayList<String> enginesRequested = new ArrayList<String>();
		
		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
		if(user != null && !user.getId().equals(Constants.ANONYMOUS_USER_ID)) {
			enginesRequested = permissions.getEngineAccessRequestsByUser(user.getId());
		}
		
		return WebUtility.getSO(enginesRequested);
	}
	
	@POST
	@Produces("application/json")
	@Path("addEngineAccessRequest")
	public Response addEngineAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		HashMap<String, Object> ret = new HashMap<String, Object>();
		String engineName = form.getFirst("engine");
		String userId = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		Boolean success = false;
		String error = "";
		
		if(userId != null && !userId.isEmpty() && userId.equals(Constants.ANONYMOUS_USER_ID)) {
			error = "Must be logged in to request access.";
		} else if(engineName == null || engineName.isEmpty()) {
			error = "No database selected.";
		} else {
			success = permissions.addEngineAccessRequest(engineName, userId);
		}
		
		ret.put("success", success);
		ret.put("error", error);
//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
		return WebUtility.getResponse(ret, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("processEngineAccessRequest")
	public Response approveEngineAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, Boolean> ret = new Hashtable<String, Boolean>();
		String requestId = form.getFirst("requestId");
//		ArrayList<String> enginePermissions = gson.fromJson(form.getFirst("permissions"), ArrayList.class);
		ArrayList<String> enginePermissions = new ArrayList<String>();
		enginePermissions.add(EnginePermission.READ_ONLY.getPermission());
		
		boolean success = permissions.processEngineAccessRequest(requestId, ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), enginePermissions.toArray(new String[enginePermissions.size()]));
		
		ret.put("success", success);
//		return Response.status(200).entity(WebUtility.getSO(ret)).build();
		return WebUtility.getResponse(ret, 200);
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
		HttpSession session = request.getSession(false);
		if(session == null){
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		User2 user = (User2) request.getSession().getAttribute("semoss_user");
		String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
		ArrayList<HashMap<String, Object>> ret = permissions.getGroupsAndMembersForUser(userId);
		
		return WebUtility.getResponse(ret, 200);
	}
	
	@GET
	@Produces("application/json")//DONE
	@Path("getOwnedDatabases")
	public StreamingOutput getOwnedDatabases(@Context HttpServletRequest request) {
		ArrayList<String> engines = permissions.getUserOwnedEngines(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId());
		
		return WebUtility.getSO(engines);
	}
	
	@GET
	@Produces("application/json")
	@Path("getAllPermissionsForDatabase")//DONE
	public StreamingOutput getAllPermissionsForDatabase(@Context HttpServletRequest request, @QueryParam("database") String engineName) {
		HashMap<String, ArrayList<StringMap<String>>> ret = new HashMap<String, ArrayList<StringMap<String>>>();
		ret = permissions.getAllPermissionsGrantedByEngine(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), engineName.trim());
		
		return WebUtility.getSO(ret);
	}
	
	@GET
	@Produces("application/json")//DONE
	@Path("searchForUser")
	public StreamingOutput searchForUser(@Context HttpServletRequest request, @QueryParam("searchTerm") String searchTerm) {
		ArrayList<StringMap<String>> ret = permissions.searchForUser(searchTerm.trim());
		
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
	public Response getDatabases(@Context HttpServletRequest request) {
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		ArrayList<StringMap<String>> ret = new ArrayList<>(); 
		HttpSession session = request.getSession(false);
		if(session == null){
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		try{
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			ret = permissions.getUserDatabases(userId, false);
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
	 * Get databases the user has access to
	 * @param request
	 * @return Databases the user has access to
	 */
	@GET
	@Produces("application/json")
	@Path("/admin/getDatabases")
	public Response getAdminDatabases(@Context HttpServletRequest request) {
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		ArrayList<StringMap<String>> ret = new ArrayList<>(); 
		try{
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			ret = permissions.getUserDatabases(userId, true);
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
	 * Get groups and user of database
	 * @param request
	 * @return Groups and user of a specific database
	 */
	@POST
	@Produces("application/json")
	@Path("/admin/getDatabaseUsersAndGroups")
	public Response getAdminDatabaseUsersAndGroups(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		StringMap<ArrayList<StringMap<String>>>  ret = new StringMap<>();
		try{
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			String engineId = form.getFirst("engineId");
			ret = permissions.getDatabaseUsersAndGroups(userId, engineId, true);
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
		StringMap<ArrayList<StringMap<String>>>  ret = new StringMap<>();
		try{
			String engineId = form.getFirst("engineId");
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			ret = permissions.getDatabaseUsersAndGroups(userId, engineId, false);
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
	
	@GET
	@Produces("application/json")
	@Path("getAllDatabasesAndPermissions")//DONE
	public StreamingOutput getAllDatabasesAndPermissions(@Context HttpServletRequest request) {
		ArrayList<StringMap<String>> ret = permissions.getAllEnginesAndPermissionsForUser(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId());
		
		return WebUtility.getSO(ret);
	}
	
	@POST
	@Produces("application/json")
	@Path("addGroup")
	public Response addNewGroup(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		ArrayList<String> users = gson.fromJson(form.getFirst("users"), ArrayList.class);
		User2 user = (User2) request.getSession().getAttribute("semoss_user");
		String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
		Boolean success = permissions.addGroup(userId, form.getFirst("groupName").trim(), users);
		
		if(success) {
			return WebUtility.getResponse(success, 200);
		} else {
			return WebUtility.getResponse(success, 200);
		}
	}
	
	@POST
	@Produces("application/json")
	@Path("removeGroup")
	public Response removeGroup(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String groupId = form.getFirst("groupId").trim();
		User2 user = (User2) request.getSession().getAttribute("semoss_user");
		String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
		Boolean success = permissions.removeGroup(userId, groupId);
		
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
		ArrayList<String> toAdd = gson.fromJson(form.getFirst("add"), ArrayList.class);
		ArrayList<String> toRemove = gson.fromJson(form.getFirst("remove"), ArrayList.class);
		
		User2 user = (User2) request.getSession().getAttribute("semoss_user");
		String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
		
		for(String add : toAdd) {
			permissions.addUserToGroup(userId, groupId, add);
		}
		
		for(String remove : toRemove) {
			permissions.removeUserFromGroup(userId, groupId, remove);
		}
		return WebUtility.getResponse(true, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("savePermissions")
	public Response savePermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		try{ 
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			String engineId = form.getFirst("engineId").trim();
			StringMap<ArrayList<StringMap<String>>> groups = gson.fromJson(form.getFirst("groups"), StringMap.class);
			StringMap<ArrayList<StringMap<String>>> users = gson.fromJson(form.getFirst("users"), StringMap.class);
			permissions.savePermissions(userId, false, engineId, groups, users);
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
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			String ret = "true";
			
			userId = form.getFirst("userIdAdd").trim();
			String groupId = form.getFirst("groupIdAdd").trim();
			String engineId = form.getFirst("engineId").trim();
			
			StringMap<ArrayList<String>> groups = gson.fromJson(form.getFirst("groups"), StringMap.class);
			ArrayList<String> groupsToAdd = groups.get("add");
			ArrayList<String> groupsToRemove = groups.get("remove");
			
			StringMap<ArrayList<String>> users = gson.fromJson(form.getFirst("users"), StringMap.class);
			ArrayList<String> usersToAdd = users.get("add");
			ArrayList<String> usersToRemove = users.get("remove");
			
			ArrayList<String> groupsFinal = permissions.getAllDbGroupsById(engineId, groupsToAdd, groupsToRemove);
			ArrayList<String> usersFinal = permissions.getAllDbUsersById(engineId, usersToAdd, usersToRemove);
			
			if(!userId.isEmpty()){
				ret = permissions.isUserWithDatabasePermissionAlready(userId, groupsFinal, usersFinal);
			} else if(!groupId.isEmpty()){
				ret = permissions.isGroupUsersWithDatabasePermissionAlready(groupId, groupsFinal, usersFinal);
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
	
	@POST
	@Produces("application/json")
	@Path("isUserAddedToGroupValid")
	public Response isUserAddedToGroupValid(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		try {
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			String ret = "";
			
			String userIdAdd = form.getFirst("userIdAdd").trim();
			String groupId = form.getFirst("groupId").trim();
			
			ret += permissions.isUserAddedToGroupValid(userIdAdd, groupId);
			
			if(ret.equals("true")){
				return WebUtility.getResponse(ret, 200);
			} else {
				return WebUtility.getResponse(ret, 400);
			}
		} catch(Exception ex) {
			return WebUtility.getResponse("An unexpected error happened. Please try again.", 500);
		}		
	}
	
	@GET
	@Produces("application/json")
	@Path("getInsightPermissions")
	public Response getInsightPermissions(@Context HttpServletRequest request, @QueryParam("database") String databaseName, @QueryParam("insight") String insightId) {
//		return Response.status(200).entity(WebUtility.getSO(permissions.getUserPermissionsForInsight(databaseName, insightId))).build();
		return WebUtility.getResponse(permissions.getUserPermissionsForInsight(databaseName, insightId), 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("saveInsightPermissions")
	public Response saveInsightPermissions(@Context HttpServletRequest request, @QueryParam("userId") String userId, @QueryParam("database") String databaseName, @QueryParam("insight") String insightId) {
		String loggedInUser = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		
//		return Response.status(200).entity(WebUtility.getSO(permissions.addInsightPermissionsForUser(loggedInUser, userId, databaseName, insightId))).build();
		return WebUtility.getResponse(permissions.addInsightPermissionsForUser(loggedInUser, userId, databaseName, insightId), 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("removeInsightPermissions")
	public Response removeInsightPermissions(@Context HttpServletRequest request, @QueryParam("userId") String userId, @QueryParam("database") String databaseName, @QueryParam("insight") String insightId) {
		String loggedInUser = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		
//		return Response.status(200).entity(WebUtility.getSO(permissions.removeInsightPermissionsForUser(loggedInUser, userId, databaseName, insightId))).build();
		return WebUtility.getResponse(permissions.removeInsightPermissionsForUser(loggedInUser, userId, databaseName, insightId), 200);
	}
	
	//Seed and RLS Permissions
	
	@POST
	@Produces("application/json")
	@Path("/admin/saveSeed")
	public void saveSeed(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String RLSValue = null;
		String RLSJavaCode = null;
		String userId = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		
		if(form.getFirst("rlsValue") != null) {
			RLSValue = form.getFirst("rlsValue");
		} else if(form.getFirst("rlsCustomPredicate") != null) {
			RLSValue = form.getFirst("rlsCustomPredicate");
		}
		
		permissions.createSeed(form.getFirst("seedName"), form.getFirst("databaseName"), form.getFirst("tableName"), form.getFirst("columnName"), RLSValue, RLSJavaCode, userId);
	}
	
	@POST
	@Produces("application/json")
	@Path("/admin/deleteSeed")
	public void deleteSeed(@Context HttpServletRequest request, @QueryParam("seedName") String seedName) {
		String userId = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		
		permissions.deleteSeed(seedName, userId);
	}
	
	@GET
	@Produces("application/json")
	@Path("/admin/getAllSeeds")
	public Response getAllSeeds(@Context HttpServletRequest request) {
		String userId = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		
//		return Response.status(200).entity(WebUtility.getSO(permissions.getMetamodelSeedsForUser(userId, true))).build();
		return WebUtility.getResponse(permissions.getMetamodelSeedsForUser(userId, true), 200);
	}
	
	@GET
	@Produces("application/json")
	@Path("/admin/getAllSeedsForUser")
	public Response getAllSeedsForUser(@Context HttpServletRequest request, @QueryParam("userId") String userId) {
//		return Response.status(200).entity(WebUtility.getSO(permissions.getMetamodelSeedsForUser(userId, false))).build();
		return WebUtility.getResponse(permissions.getMetamodelSeedsForUser(userId, false), 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/admin/addSeedForUser")
	public Response addSeedForUser(@Context HttpServletRequest request, @QueryParam("seedName") String seedName, @QueryParam("userId") String userId) {
		String loggedInUser = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		
//		return Response.status(200).entity(WebUtility.getSO(permissions.addUserToSeed(userId, seedName, loggedInUser))).build();
		return WebUtility.getResponse(permissions.addUserToSeed(userId, seedName, loggedInUser), 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/admin/deleteSeedForUser")
	public Response deleteSeedForUser(@Context HttpServletRequest request, @QueryParam("seedName") String seedName, @QueryParam("userId") String userId) {
		String loggedInUser = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		
//		return Response.status(200).entity(WebUtility.getSO(permissions.deleteUserFromSeed(userId, seedName, loggedInUser))).build();
		return WebUtility.getResponse(permissions.deleteUserFromSeed(userId, seedName, loggedInUser), 200);
	}
	
	@GET
	@Path("/admin/isAdminUser")
	public Response isAdminUser(@Context HttpServletRequest request) {
		
		HttpSession session = request.getSession(false);
		if(session == null){
			Map<String, String> errorMap = new HashMap<String, String>();
			errorMap.put("error", "User session is invalid");
			return WebUtility.getResponse(errorMap, 401);
		}
		
		if(session.getAttribute("semoss_user") != null) {
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			if(!userId.equals(Constants.ANONYMOUS_USER_ID) && permissions.isUserAdmin(userId)) {
				return WebUtility.getResponse(true, 200);
			}
		}
		
		return WebUtility.getResponse(false, 200);
	}
	
	@GET
	@Path("/startTeamSession")
	@Produces("application/json")
	public Response startTeamSession(@Context HttpServletRequest request, @QueryParam("insightId") String insightId, @QueryParam("teamId") String teamId) {
		StringMap retData = new StringMap<String>();
		StringMap<StringMap<String>> teamShareMaps = new StringMap<StringMap<String>>();
		
		//Get the existing team share mappings, if they exist
		if(DIHelper.getInstance().getLocalProp("teamShareMaps") != null) {
			teamShareMaps = (StringMap<StringMap<String>>) DIHelper.getInstance().getLocalProp("teamShareMaps");
		}
		
		if(teamShareMaps.containsKey(teamId)) {
			retData.put("success", false);
//			return Response.status(400).entity(WebUtility.getSO(retData)).build();
			return WebUtility.getResponse(retData, 200);
		} else {
			StringMap<String> newTeamShareMap = new StringMap<String>();
			newTeamShareMap.put("insightId", insightId);
			newTeamShareMap.put("owner", ((User)request.getSession().getAttribute(Constants.SESSION_USER)).getId());
			teamShareMaps.put(teamId, newTeamShareMap);
		}
		DIHelper.getInstance().setLocalProperty("teamShareMaps", teamShareMaps);
		
		retData.put("success", true);
//		return Response.status(200).entity(WebUtility.getSO(retData)).build();
		return WebUtility.getResponse(retData, 200);
	}
	
	@GET
	@Path("/endTeamSession")
	@Produces("application/json")
	public Response endTeamSession(@Context HttpServletRequest request, @QueryParam("teamId") String teamId) {
		StringMap retData = new StringMap<String>();
		String loggedInUser = ((User)request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		StringMap<StringMap<String>> teamShareMaps = new StringMap<StringMap<String>>();
		
		//Get the existing team share mappings, if they exist
		if(DIHelper.getInstance().getLocalProp("teamShareMaps") != null) {
			teamShareMaps = (StringMap<StringMap<String>>) DIHelper.getInstance().getLocalProp("teamShareMaps");
		}
		
		//If the map doesn't exist, or the teamId isn't in the map, or the logged in user isn't the owner of the team session, don't do anything
		if(teamShareMaps == null || !teamShareMaps.containsKey(teamId) || !teamShareMaps.get(teamId).get("owner").equals(loggedInUser)) {
			retData.put("success", false);
//			return Response.status(400).entity(WebUtility.getSO(retData)).build();
			return WebUtility.getResponse(retData, 200);
		}
		
		//Remove the team session information and replace the map in DIHelper
		teamShareMaps.remove(teamId);
		DIHelper.getInstance().setLocalProperty("teamShareMaps", teamShareMaps);
		
		retData.put("success", true);
//		return Response.status(200).entity(WebUtility.getSO(retData)).build();
		return WebUtility.getResponse(retData, 200);
	}
	
	@GET
	@Path("/getAllDbUsers")
	@Produces("application/json")
	public Response getAllDbUsers(@Context HttpServletRequest request) {
		ArrayList<StringMap<String>> ret = new ArrayList<>();
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		User2 user = (User2) request.getSession().getAttribute("semoss_user");
		String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
		try{
			ret = permissions.getAllDbUsers(userId);
		} catch (IllegalArgumentException e){
			e.printStackTrace();
			errorRet.put("error", e.getMessage());
		} catch (Exception e){
			e.printStackTrace();
			errorRet.put("error", "An unexpected error happened. Please try again.");
		}
		return WebUtility.getResponse(ret, 200);
	}
	
	@POST
	@Path("/removeDatabaseAccess")
	@Produces("application/json")
	public Response removeUserPermissionsbyDbId(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		boolean ret = false;
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		User2 user = (User2) request.getSession().getAttribute("semoss_user");
		String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
		String engineId = form.getFirst("engineId");
		try{
			ret = permissions.removeUserPermissionsbyDbId(userId, engineId);
		} catch (IllegalArgumentException e){
			e.printStackTrace();
			errorRet.put("error", e.getMessage());
		} catch (Exception e){
			e.printStackTrace();
			errorRet.put("error", "An unexpected error happened. Please try again.");
		}
		return WebUtility.getResponse(ret, 200);
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
		boolean ret = false;
		Gson gson = new Gson();
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		try{
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			StringMap<String> userInfo = gson.fromJson(form.getFirst("user"), StringMap.class);
			ret = permissions.editUser(userId, userInfo);
		} catch (IllegalArgumentException e){
			e.printStackTrace();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			e.printStackTrace();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(ret, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/admin/savePermissions")
	public Response savePermissionsAdmin(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		
		try{
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			String engineId = form.getFirst("engineId").trim();
			StringMap<ArrayList<StringMap<String>>> groups = gson.fromJson(form.getFirst("groups"), StringMap.class);
			StringMap<ArrayList<StringMap<String>>> users = gson.fromJson(form.getFirst("users"), StringMap.class);
			permissions.savePermissions(userId, true, engineId, groups, users);
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
	@Path("setDbVisibility")
	public Response setDbVisibility(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		try{
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String userId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			String engineId = form.getFirst("engineId");
			String visibility = form.getFirst("visibility");
			permissions.setDbVisibility(userId, engineId, visibility);
		} catch (IllegalArgumentException e){
			e.printStackTrace();
			errorRet.put("error", e.getMessage());
			return WebUtility.getResponse(errorRet, 400);
		} catch (Exception e){
			e.printStackTrace();
			errorRet.put("error", "An unexpected error happened. Please try again.");
			return WebUtility.getResponse(errorRet, 500);
		}
		return WebUtility.getResponse(true, 200);
	}
	
	@POST
	@Produces("application/json")
	@Path("/admin/deleteUser")
	public Response deleteUser(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, String> errorRet = new Hashtable<String, String>();
		try{
			User2 user = (User2) request.getSession().getAttribute("semoss_user");
			String adminId = user.getAccessToken(AuthProvider.NATIVE.name()).getId();
			String userId = form.getFirst("userId");
			permissions.deleteUser(adminId, userId);
			if(adminId.equals(userId)){
				request.getSession().invalidate();
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
		return WebUtility.getResponse(true, 200);
	}
}