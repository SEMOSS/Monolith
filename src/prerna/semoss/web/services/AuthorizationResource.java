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

import com.google.gson.Gson;
import com.google.gson.internal.StringMap;

import prerna.auth.EngineAccessRequest;
import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.auth.EnginePermission;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("/authorization")
public class AuthorizationResource
{
	@Context ServletContext context;
	String output = "";
	
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
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
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
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
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
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
	}
	
	@GET
	@Produces("application/json")
	@Path("getEngineAccessRequestsByUser")
	public StreamingOutput getEngineAccessRequestsByUser(@Context HttpServletRequest request) throws IOException {
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
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
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
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
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
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
		
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
		boolean success = permissions.processEngineAccessRequest(requestId, ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), enginePermissions.toArray(new String[enginePermissions.size()]));
		
		ret.put("success", success);
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
	}
	
	@GET
	@Produces("application/json")//DONE
	@Path("getGroups")
	public StreamingOutput getGroupsAndMembers(@Context HttpServletRequest request) {
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
		StringMap<ArrayList<StringMap<String>>> ret = permissions.getGroupsAndMembersForUser(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId());
		
		return WebUtility.getSO(ret);
	}
	
	@GET
	@Produces("application/json")//DONE
	@Path("getOwnedDatabases")
	public StreamingOutput getOwnedDatabases(@Context HttpServletRequest request) {
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
		ArrayList<String> engines = permissions.getUserOwnedEngines(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId());
		
		return WebUtility.getSO(engines);
	}
	
	@GET
	@Produces("application/json")
	@Path("getAllPermissionsForDatabase")//DONE
	public StreamingOutput getAllPermissionsForDatabase(@Context HttpServletRequest request, @QueryParam("database") String engineName) {
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
		HashMap<String, ArrayList<StringMap<String>>> ret = new HashMap<String, ArrayList<StringMap<String>>>();
		ret = permissions.getAllPermissionsGrantedByEngine(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), engineName);
		
		return WebUtility.getSO(ret);
	}
	
	@GET
	@Produces("application/json")//DONE
	@Path("searchForUser")
	public StreamingOutput searchForUser(@Context HttpServletRequest request, @QueryParam("searchTerm") String searchTerm) {
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
		ArrayList<StringMap<String>> ret = permissions.searchForUser(searchTerm);
		
		return WebUtility.getSO(ret);
	}
	
	@GET
	@Produces("application/json")
	@Path("getAllDatabasesAndPermissions")//DONE
	public StreamingOutput getAllDatabasesAndPermissions(@Context HttpServletRequest request) {
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
		ArrayList<StringMap<String>> ret = permissions.getAllEnginesAndPermissionsForUser(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId());
		
		return WebUtility.getSO(ret);
	}
	
	@POST
	@Produces("application/json")
	@Path("addGroup")
	public Response addNewGroup(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
		Gson gson = new Gson();
		ArrayList<String> users = gson.fromJson(form.getFirst("users"), ArrayList.class);
		Boolean success = permissions.addGroup(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), form.getFirst("groupName"), users);
		
		if(success) {
			return Response.status(200).entity(WebUtility.getSO(success)).build();
		} else {
			return Response.status(400).entity(WebUtility.getSO(success)).build();
		}
	}
	
	@POST
	@Produces("application/json")
	@Path("removeGroup")
	public Response removeGroup(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
		String groupName = form.getFirst("groupName");
		Boolean success = permissions.removeGroup(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), groupName);
		
		if(success) {
			return Response.status(200).entity(WebUtility.getSO(success)).build();
		} else {
			return Response.status(400).entity(WebUtility.getSO(success)).build();
		}
	}
	
	@POST
	@Produces("application/json")
	@Path("editGroup")
	public Response editGroup(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
		Gson gson = new Gson();
		
		String groupName = form.getFirst("groupName");
		StringMap<ArrayList<String>> users = gson.fromJson(form.getFirst("users"), StringMap.class);
		ArrayList<String> toAdd = users.get("add");
		ArrayList<String> toRemove = users.get("remove");
		
		for(String add : toAdd) {
			permissions.addUserToGroup(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), groupName, add);
		}
		
		for(String remove : toRemove) {
			permissions.removeUserFromGroup(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), groupName, remove);
		}
		
		return Response.status(200).entity(WebUtility.getSO(true)).build();
	}
	
	@POST
	@Produces("application/json")
	@Path("savePermissions")
	public Response savePermissions(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
		Gson gson = new Gson();
		String userId = ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		
		String engineName = form.getFirst("databaseName");
		StringMap<ArrayList<StringMap<String>>> groups = gson.fromJson(form.getFirst("groups"), StringMap.class);
		ArrayList<StringMap<String>> groupsToAdd = groups.get("add");
		ArrayList<StringMap<String>> groupsToRemove = groups.get("remove");
		
		for(StringMap<String> map : groupsToRemove) {
			permissions.removeAllPermissionsForGroup(userId, map.get("groupName"), engineName);
		}
		
		for(StringMap<String> map : groupsToAdd) {
			String perm = map.get("permission");
			EnginePermission[] permArray = new EnginePermission[] { EnginePermission.getPermissionByValue(perm) };
			permissions.setPermissionsForGroup(userId, map.get("groupName"), engineName, permArray);
		}
		
		StringMap<ArrayList<StringMap<String>>> users = gson.fromJson(form.getFirst("users"), StringMap.class);
		ArrayList<StringMap<String>> usersToAdd = users.get("add");
		ArrayList<StringMap<String>> usersToRemove = users.get("remove");
		
		for(StringMap<String> map : usersToRemove) {
			permissions.removeAllPermissionsForUser(userId, engineName, map.get("id"));
		}
		
		for(StringMap<String> map : usersToAdd) {
			String perm = map.get("permission");
			EnginePermission[] permArray = new EnginePermission[] { EnginePermission.getPermissionByValue(perm) };
			permissions.setPermissionsForUser(userId, engineName, map.get("id"), permArray);
		}
		
		
		return Response.status(200).entity(WebUtility.getSO(true)).build();
	}
}