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
import java.util.Map;
import java.util.TreeMap;

import jakarta.annotation.security.PermitAll;
import jakarta.naming.InvalidNameException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.ds.util.RdbmsQueryBuilder;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.forms.AbstractFormBuilder;
import prerna.forms.FormBuilder;
import prerna.forms.FormFactory;
import prerna.masterdatabase.utility.MasterDatabaseUtility;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/form")
@PermitAll
public class FormResource {

	private static final Logger classLogger = LogManager.getLogger(FormResource.class);

	private IDatabaseEngine formEngine;

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

		String addOrRemove = WebUtility.inputSQLSanitizer(form.getFirst("addOrRemove"));
		String userid = WebUtility.inputSQLSanitizer(form.getFirst("userid"));
		String instancename = Utility.cleanString(WebUtility.inputSQLSanitizer(form.getFirst("instanceName")), true);
		// this is only present if we are adding a user
		String owner = WebUtility.inputSQLSanitizer(form.getFirst("ownerStatus"));

		String query = null;
		if (addOrRemove.equals("Remove")) {
			if (instancename != null && !instancename.isEmpty() && !instancename.equals("null")
					&& !instancename.equals("undefined")) {
				query = "DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = '"
						+ RdbmsQueryBuilder.escapeForSQLStatement(userid) + "' AND INSTANCE_NAME = '"
						+ RdbmsQueryBuilder.escapeForSQLStatement(instancename) + "';";

				// log the operation
				classLogger.info("User is removing user {} from having access to {}", userid, instancename);
			} else {
				// remove all of user
				query = "DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = '"
						+ RdbmsQueryBuilder.escapeForSQLStatement(userid) + "';";

				// log the operation
				classLogger.info("User is removing all access for user {}", userid);
			}
		} else if (addOrRemove.equals("Add")) {
			query = "INSERT INTO FORMS_USER_ACCESS (USER_ID, INSTANCE_NAME, IS_SYS_ADMIN) VALUES ('"
					+ RdbmsQueryBuilder.escapeForSQLStatement(userid) + "','"
					+ RdbmsQueryBuilder.escapeForSQLStatement(instancename) + "','"
					+ RdbmsQueryBuilder.escapeForSQLStatement(owner) + "');";

			// log the operation
			classLogger.info("User is adding user {} to have access to {}", userid, instancename);
		} else {
			return WebUtility.getResponse("Error: need to specify Add or Remove", 400);
		}

		IDatabaseEngine formEngine = getEngine();
		// execute the query
		try {
			formEngine.insertData(query);
			// commit to engine
			formEngine.commit();
		} catch (Exception e) {
			classLogger.error("Failed to insert form user access data and commit to the form engine", e);
			return WebUtility.getResponse("An error occurred to update the user's access!", 400);
		}

		return WebUtility.getResponse("success", 200);
	}

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
			throwErrorIfNotSysAdmin(cacId, instanceName);
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

	@POST
	@Path("/getUserInstanceAuth")
	@Produces("applicaiton/json")
	public Response getUserInstanceAuth(@Context HttpServletRequest request) throws InvalidNameException {
		/*
		 * Get the specific instances this user has access to
		 */

		String cacId;
		try {
			cacId = WebUtility.inputSQLSanitizer(getCacId(request));
		} catch (IOException e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}

		Map<String, String> userAccessableInstances = new TreeMap<String, String>();

		// map to store the valid instances for the given user
		String query = "SELECT INSTANCE_NAME, IS_SYS_ADMIN FROM FORMS_USER_ACCESS WHERE USER_ID = '"
				+ RdbmsQueryBuilder.escapeForSQLStatement(cacId) + "';";
		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(getEngine(), query);
			while (wrapper.hasNext()) {
				Object[] values = wrapper.next().getValues();
				userAccessableInstances.put(values[0].toString(), values[1].toString());
			}
		} catch (Exception e) {
			classLogger.error("Failed to query the accessible form instances for user {}", cacId, e);
		} finally {
			if (wrapper != null) {
				try {
					wrapper.close();
				} catch (IOException e) {
					classLogger.error("Failed to close the result wrapper for the user instance access query", e);
				}
			}
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
	 * Check that user is an admin
	 * 
	 * @param cacId
	 * @throws IllegalAccessException
	 */
	private void throwErrorIfNotAdmin(String cacId) throws IllegalAccessException {
		String isAdminQuery = "SELECT * FROM FORMS_USER_ACCESS " + "WHERE USER_ID='"
				+ RdbmsQueryBuilder.escapeForSQLStatement(cacId) + "' " + "AND INSTANCE_NAME='ADMIN' " + "LIMIT 1;";

		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(getEngine(), isAdminQuery);
			if (!wrapper.hasNext()) {
				throw new IllegalAccessException("User is not an admin and cannot perform this operation");
			}
		} catch (Exception e) {
			classLogger.error("Failed to query whether user {} is an admin", cacId, e);
		} finally {
			if (wrapper != null) {
				try {
					wrapper.close();
				} catch (IOException e) {
					classLogger.error("Failed to close the result wrapper for the admin check query", e);
				}
			}
		}
	}

	/**
	 * Check that user is an admin
	 * 
	 * @param cacId
	 * @throws IllegalAccessException
	 */
	private void throwErrorIfNotSysAdmin(String cacId, String system) throws IllegalAccessException {
		String isAdminQuery = "SELECT * FROM FORMS_USER_ACCESS " + "WHERE USER_ID='"
				+ RdbmsQueryBuilder.escapeForSQLStatement(cacId) + "' " + "AND INSTANCE_NAME='"
				+ RdbmsQueryBuilder.escapeForSQLStatement(system) + "' " + "AND IS_SYS_ADMIN=TRUE " + "LIMIT 1;";

		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(getEngine(), isAdminQuery);
			if (!wrapper.hasNext()) {
				throw new IllegalAccessException("User is not an admin and cannot perform this operation");
			}
		} catch (Exception e) {
			classLogger.error("Failed to query whether user {} is a system admin for system {}", cacId, system, e);
		} finally {
			if (wrapper != null) {
				try {
					wrapper.close();
				} catch (IOException e) {
					classLogger.error("Failed to close the result wrapper for the system admin check query", e);
				}
			}
		}
	}

	/**
	 * Get the form engine Since this is a resource, we just need to make sure we
	 * load after DBLoader is done loading
	 * 
	 * @return
	 */
	public IDatabaseEngine getEngine() {
		if (formEngine == null) {
			formEngine = Utility.getDatabase(FormBuilder.FORM_BUILDER_ENGINE_NAME);
			AbstractFormBuilder.generateFormPermissionTable(formEngine);
		}
		return formEngine;
	}
}
