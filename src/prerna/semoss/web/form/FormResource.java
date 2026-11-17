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
package prerna.semoss.web.form;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.naming.InvalidNameException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IRDBMSEngine;
import prerna.forms.AbstractFormBuilder;
import prerna.forms.FormBuilder;
import prerna.forms.FormFactory;
import prerna.masterdatabase.utility.MasterDatabaseUtility;
import prerna.util.Constants;
import prerna.util.QueryExecutionUtility;
import prerna.util.QueryExecutionUtility.StatementBinder;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/form")
@PermitAll
@Deprecated(forRemoval = true)
public class FormResource {

	private static final Logger classLogger = LogManager.getLogger(FormResource.class);

	private IDatabaseEngine formEngine;

	@Deprecated
	@POST
	@Path("/modifyUserAccess")
	@Produces("application/json")
	public Response modifyUserAccess(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String cacId;
		try {
			cacId = getCacId(request);
		} catch (IOException e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}

		try {
			throwErrorIfNotAdmin(cacId);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to modify user access while not being an admin");
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}

		String addOrRemove = form.getFirst("addOrRemove");
		String userid = form.getFirst("userid");
		String requestedInstance = form.getFirst("instanceName");
		String instancename = requestedInstance == null ? null : Utility.cleanString(requestedInstance, true);
		// this is only present if we are adding a user
		boolean owner = Boolean.parseBoolean(form.getFirst("ownerStatus"));

		String query = null;
		StatementBinder binder = null;
		if ("Remove".equals(addOrRemove)) {
			if (instancename != null && !instancename.isEmpty() && !instancename.equals("null")
					&& !instancename.equals("undefined")) {
				query = "DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = ? AND INSTANCE_NAME = ?";
				binder = ps -> {
					ps.setString(1, userid);
					ps.setString(2, instancename);
				};

				// log the operation
				classLogger.info("User is removing user {} from having access to {}", userid, instancename);
			} else {
				// remove all of user
				query = "DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = ?";
				binder = ps -> ps.setString(1, userid);

				// log the operation
				classLogger.info("User is removing all access for user {}", userid);
			}
		} else if ("Add".equals(addOrRemove)) {
			query = "INSERT INTO FORMS_USER_ACCESS (USER_ID, INSTANCE_NAME, IS_SYS_ADMIN) VALUES (?, ?, ?)";
			binder = ps -> {
				ps.setString(1, userid);
				ps.setString(2, instancename);
				ps.setBoolean(3, owner);
			};

			// log the operation
			classLogger.info("User is adding user {} to have access to {}", userid, instancename);
		} else {
			return WebUtility.getResponse("Error: need to specify Add or Remove", 400);
		}

		try {
			QueryExecutionUtility.executeUpdate(formDb(), query, binder);
		} catch (Exception e) {
			classLogger.error("Failed to insert form user access data and commit to the form engine", e);
			return WebUtility.getResponse("An error occurred to update the user's access!", 400);
		}

		return WebUtility.getResponse("success", 200);
	}

	@Deprecated
	@POST
	@Path("/renameInstance")
	@Produces("application/json")
	public Response renameInstance(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String cacId;
		try {
			cacId = getCacId(request);
		} catch (IOException e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}

		try {
			throwErrorIfNotAdmin(cacId);
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to rename an instance while not being an admin");
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}

		String dbName = WebUtility.inputSQLSanitizer(form.getFirst("dbName"));
		String origUri = WebUtility.inputSQLSanitizer(form.getFirst("originalUri"));
		String newUri = WebUtility.inputSQLSanitizer(form.getFirst("newUri"));
		boolean deleteInstanceBoolean = false;
		if (form.getFirst("deleteInstanceBoolean") != null) {
			deleteInstanceBoolean = Boolean.parseBoolean(form.getFirst("deleteInstanceBoolean"));
		}

		// log the operation
		classLogger.info("User is renaming {} to {}", origUri, newUri);

		IDatabaseEngine coreEngine = Utility.getDatabase(MasterDatabaseUtility.testDatabaseIdIfAlias(dbName));
		AbstractFormBuilder formbuilder = FormFactory.getFormBuilder(coreEngine);
		formbuilder.modifyInstanceValue(origUri, newUri, deleteInstanceBoolean);
		return WebUtility.getResponse("success", 200);
	}

	@Deprecated
	@POST
	@Path("/certifyInstance")
	@Produces("application/json")
	public Response certifyInstance(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String cacId;
		try {
			cacId = getCacId(request);
		} catch (IOException e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}

		String dbName = WebUtility.inputSQLSanitizer(form.getFirst("dbName"));
		String instanceType = WebUtility.inputSQLSanitizer(form.getFirst("instanceType"));
		String instanceName = WebUtility.inputSQLSanitizer(form.getFirst("instanceName"));

		try {
			throwErrorIfNotSysAdmin(cacId, form.getFirst("instanceName"));
		} catch (IllegalAccessException e) {
			classLogger.warn("User is trying to certify {} when he is not the system admin for the system",
					instanceName);
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}

		IDatabaseEngine coreEngine = Utility.getDatabase(MasterDatabaseUtility.testDatabaseIdIfAlias(dbName));
		AbstractFormBuilder formbuilder = FormFactory.getFormBuilder(coreEngine);
		formbuilder.setUser(cacId);
		formbuilder.certifyInstance(instanceType, instanceName);

		// log the operation
		classLogger.info("User has certified {}", instanceName);
		return WebUtility.getResponse("success", 200);
	}

	@Deprecated
	@POST
	@Path("/getUserInstanceAuth")
	@Produces("applicaiton/json")
	public Response getUserInstanceAuth(@Context HttpServletRequest request) throws InvalidNameException {
		/*
		 * Get the specific instances this user has access to
		 */

		String cacId;
		try {
			cacId = getCacId(request);
		} catch (IOException e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}

		Map<String, String> userAccessableInstances = new TreeMap<String, String>();

		// map to store the valid instances for the given user
		try {
			List<String[]> grants = QueryExecutionUtility.queryList(formDb(),
					"SELECT INSTANCE_NAME, IS_SYS_ADMIN FROM FORMS_USER_ACCESS WHERE USER_ID = ?",
					ps -> ps.setString(1, cacId),
					rs -> new String[] { rs.getString(1), String.valueOf(rs.getBoolean(2)) });
			for (String[] grant : grants) {
				userAccessableInstances.put(grant[0], grant[1]);
			}
		} catch (Exception e) {
			classLogger.error("Failed to query the accessible form instances for user {}", cacId, e);
		}

		Map<String, Object> returnData = new HashMap<String, Object>();
		returnData.put("cac_id", cacId);
		returnData.put("validInstances", userAccessableInstances);
		return WebUtility.getResponse(returnData, 200);
	}

	/**
	 * Get the CAC ID for the user
	 * 
	 * @param request
	 * @return
	 * @throws IOException
	 */
	private String getCacId(@Context HttpServletRequest request) throws IOException {
		/*
		 * If you wanted to debug locally w/o a CAC, just return a string
		 */
//		return "mahkhalil";

		String x509Id = null;
		try {
			HttpSession session = request.getSession(false);
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if (user.getAccessToken(AuthProvider.CAC) != null) {
				x509Id = user.getAccessToken(AuthProvider.CAC).getId();
			} else {
				// if not CAC - we are using SMAL
				x509Id = user.getAccessToken(AuthProvider.SAML).getId();
			}
		} catch (Exception e) {
			throw new IOException("Could not identify user");
		}
		if (x509Id == null) {
			throw new IOException("Could not identify user");
		}

		return x509Id;
	}

	/**
	 * Throws unless the user holds the forms ADMIN grant. A lookup that fails
	 * refuses the user as well, so an error can never let a non-admin through.
	 *
	 * @param cacId the user's CAC id
	 * @throws IllegalAccessException when the user is not a forms admin or the
	 *                                check could not be made
	 */
	private void throwErrorIfNotAdmin(String cacId) throws IllegalAccessException {
		boolean isAdmin = false;
		try {
			isAdmin = QueryExecutionUtility.queryOne(formDb(),
					"SELECT 1 FROM FORMS_USER_ACCESS WHERE USER_ID = ? AND INSTANCE_NAME = 'ADMIN' LIMIT 1",
					ps -> ps.setString(1, cacId), rs -> Boolean.TRUE) != null;
		} catch (Exception e) {
			classLogger.error("Failed to query whether user {} is an admin", cacId, e);
		}
		if (!isAdmin) {
			throw new IllegalAccessException("User is not an admin and cannot perform this operation");
		}
	}

	/**
	 * Throws unless the user is a system admin of the given form instance. A lookup
	 * that fails refuses the user as well, so an error can never let a non-admin
	 * through.
	 *
	 * @param cacId  the user's CAC id
	 * @param system the form instance
	 * @throws IllegalAccessException when the user is not a system admin of the
	 *                                instance or the check could not be made
	 */
	private void throwErrorIfNotSysAdmin(String cacId, String system) throws IllegalAccessException {
		boolean isSysAdmin = false;
		try {
			isSysAdmin = QueryExecutionUtility.queryOne(formDb(),
					"SELECT 1 FROM FORMS_USER_ACCESS WHERE USER_ID = ? AND INSTANCE_NAME = ? AND IS_SYS_ADMIN = TRUE LIMIT 1",
					ps -> {
						ps.setString(1, cacId);
						ps.setString(2, system);
					}, rs -> Boolean.TRUE) != null;
		} catch (Exception e) {
			classLogger.error("Failed to query whether user {} is a system admin for system {}", cacId, system, e);
		}
		if (!isSysAdmin) {
			throw new IllegalAccessException("User is not an admin and cannot perform this operation");
		}
	}

	/**
	 * Get the form engine Since this is a resource, we just need to make sure we
	 * load after DBLoader is done loading
	 * 
	 * @return
	 */
	/**
	 * Returns the forms permission store as the relational engine its
	 * parameterized statements run against.
	 *
	 * @return the forms database
	 * @throws IllegalStateException when the forms database is not relational
	 */
	private IRDBMSEngine formDb() {
		if (getEngine() instanceof IRDBMSEngine rdbms) {
			return rdbms;
		}
		throw new IllegalStateException(
				"The forms database " + FormBuilder.FORM_BUILDER_ENGINE_NAME + " is not a relational database");
	}

	@Deprecated
	public IDatabaseEngine getEngine() {
		if (formEngine == null) {
			formEngine = Utility.getDatabase(FormBuilder.FORM_BUILDER_ENGINE_NAME);
			AbstractFormBuilder.generateFormPermissionTable(formEngine);
		}
		return formEngine;
	}
}
