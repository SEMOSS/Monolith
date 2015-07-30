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
import java.util.Hashtable;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import com.google.gson.Gson;

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
	private final UserPermissionsMasterDB permissions = new UserPermissionsMasterDB(Constants.LOCAL_MASTER_DB_NAME);
	
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
		
		EnginePermission[] permissionsList = new EnginePermission[] { EnginePermission.EDIT_INSIGHT };
		
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
			for(EnginePermission ep : EnginePermission.values()) {
				allPermissionsList.add(ep.getPermissionName());
			}
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
	
	@POST
	@Produces("application/json")
	@Path("addEngineAccessRequest")
	public Response addEngineAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, Boolean> ret = new Hashtable<String, Boolean>();
		String engineName = form.getFirst("engine");
		
		boolean success = permissions.addEngineAccessRequest(engineName, ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId());
		
		ret.put("success", success);
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
	}
	
	@POST
	@Produces("application/json")
	@Path("processEngineAccessRequest")
	public Response approveEngineAccessRequest(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		Hashtable<String, Boolean> ret = new Hashtable<String, Boolean>();
		Gson gson = new Gson();
		String requestId = form.getFirst("requestId");
		ArrayList<String> enginePermissions = gson.fromJson(form.getFirst("permissions"), ArrayList.class);
		
		boolean success = permissions.processEngineAccessRequest(requestId, ((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), enginePermissions.toArray(new String[enginePermissions.size()]));
		
		ret.put("success", success);
		return Response.status(200).entity(WebUtility.getSO(ret)).build();
	}
}