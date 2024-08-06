package prerna.semoss.web.form;

import java.io.IOException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.security.PermitAll;
import javax.naming.InvalidNameException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

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
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/form")
@PermitAll
public class FormResource {

	private static final Logger logger = LogManager.getLogger(FormResource.class);

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
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), cacId, "is trying to modify user access while not being an admin"));
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}
		
		String addOrRemove =WebUtility.inputSQLSanitizer(form.getFirst("addOrRemove"));
		String userid = WebUtility.inputSQLSanitizer(form.getFirst("userid"));
		String instancename = Utility.cleanString(WebUtility.inputSQLSanitizer(form.getFirst("instanceName")), true);
		//  this is only present if we are adding a user
		String owner = WebUtility.inputSQLSanitizer(form.getFirst("ownerStatus"));

		String query = null;
		if (addOrRemove.equals("Remove")) {
			if(instancename != null && !instancename.isEmpty() && !instancename.equals("null") && !instancename.equals("undefined")) {
				query = "DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = '" + 
						RdbmsQueryBuilder.escapeForSQLStatement(userid) + 
						"' AND INSTANCE_NAME = '" + 
						RdbmsQueryBuilder.escapeForSQLStatement(instancename) + "';";
				
				// log the operation
				logger.info(ResourceUtility.getLogMessage(request, request.getSession(), cacId, "is removing user " + userid + " from having access to " + instancename));
			} else {
				// remove all of user
				query = "DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = '" + RdbmsQueryBuilder.escapeForSQLStatement(userid) + "';";
				
				// log the operation
				logger.info(ResourceUtility.getLogMessage(request, request.getSession(), cacId, "is removing all access for user " + userid));
			}
		} else if (addOrRemove.equals("Add")) {
			query = "INSERT INTO FORMS_USER_ACCESS (USER_ID, INSTANCE_NAME, IS_SYS_ADMIN) VALUES ('" + 
					RdbmsQueryBuilder.escapeForSQLStatement(userid) + "','" + 
					RdbmsQueryBuilder.escapeForSQLStatement(instancename) + "','" + 
					RdbmsQueryBuilder.escapeForSQLStatement(owner) + "');";
			
			// log the operation
			logger.info(ResourceUtility.getLogMessage(request, request.getSession(), cacId, "is adding user " + userid + " to have access to " + instancename));
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
			logger.error(Constants.STACKTRACE, e);
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
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), cacId, "is trying to rename an instance while not being an admin"));
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}

		String dbName = WebUtility.inputSQLSanitizer(form.getFirst("dbName"));
		String origUri = WebUtility.inputSQLSanitizer(form.getFirst("originalUri"));
		String newUri = WebUtility.inputSQLSanitizer(form.getFirst("newUri"));
		boolean deleteInstanceBoolean = false;
		if(form.getFirst("deleteInstanceBoolean") != null) {
			deleteInstanceBoolean = Boolean.parseBoolean(form.getFirst("deleteInstanceBoolean"));
		}

		// log the operation
		logger.info(ResourceUtility.getLogMessage(request, request.getSession(), cacId, "is renaming " + origUri + " to " + newUri));

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
			logger.warn(ResourceUtility.getLogMessage(request, request.getSession(), cacId, "is trying to certify " + instanceName + " when he is not the system admin for the system"));
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, e.getMessage());
			return WebUtility.getResponse(err, 400);
		}

		IDatabaseEngine coreEngine = Utility.getDatabase(MasterDatabaseUtility.testDatabaseIdIfAlias(dbName));		
		AbstractFormBuilder formbuilder = FormFactory.getFormBuilder(coreEngine);
		formbuilder.setUser(cacId);
		formbuilder.certifyInstance(instanceType, instanceName);
		
		// log the operation
		logger.info(ResourceUtility.getLogMessage(request, request.getSession(), cacId, "has certified " + instanceName));
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
		String query = "SELECT INSTANCE_NAME, IS_SYS_ADMIN FROM FORMS_USER_ACCESS WHERE USER_ID = '" + RdbmsQueryBuilder.escapeForSQLStatement(cacId) + "';";
		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(getEngine(), query);
			while(wrapper.hasNext()) {
				Object[] values = wrapper.next().getValues();
				userAccessableInstances.put(values[0].toString(), values[1].toString());
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		} finally {
			if(wrapper != null) {
				try {
					wrapper.close();
				} catch (IOException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		}

		Map<String, Object> returnData = new Hashtable<String, Object>();
		returnData.put("cac_id", cacId);
		returnData.put("validInstances", userAccessableInstances);
		return WebUtility.getResponse(returnData, 200);
	}

	/**
	 * Get the CAC ID for the user
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
			HttpSession session = ((HttpServletRequest)request).getSession(false);
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if(user.getAccessToken(AuthProvider.CAC) != null) {
				x509Id = user.getAccessToken(AuthProvider.CAC).getId();
			} else {
				// if not CAC - we are using SMAL
				x509Id = user.getAccessToken(AuthProvider.SAML).getId();
			}
		} catch(Exception e) {
			throw new IOException("Could not identify user");
		}
		if(x509Id == null) {
			throw new IOException("Could not identify user");
		}

		return x509Id;
	}

	/**
	 * Check that user is an admin
	 * @param cacId
	 * @throws IllegalAccessException
	 */
	private void throwErrorIfNotAdmin(String cacId) throws IllegalAccessException {
		String isAdminQuery = "SELECT * FROM FORMS_USER_ACCESS "
				+ "WHERE USER_ID='" + RdbmsQueryBuilder.escapeForSQLStatement(cacId) + "' "
				+ "AND INSTANCE_NAME='ADMIN' "
				+ "LIMIT 1;";

		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(getEngine(), isAdminQuery);
			if(!wrapper.hasNext()) {
				throw new IllegalAccessException("User is not an admin and cannot perform this operation");
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		} finally {
			if(wrapper != null) {
				try {
					wrapper.close();
				} catch (IOException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		}
	}

	/**
	 * Check that user is an admin
	 * @param cacId
	 * @throws IllegalAccessException
	 */
	private void throwErrorIfNotSysAdmin(String cacId, String system) throws IllegalAccessException {
		String isAdminQuery = "SELECT * FROM FORMS_USER_ACCESS "
				+ "WHERE USER_ID='" + RdbmsQueryBuilder.escapeForSQLStatement(cacId) + "' "
				+ "AND INSTANCE_NAME='" + RdbmsQueryBuilder.escapeForSQLStatement(system) + "' "
				+ "AND IS_SYS_ADMIN=TRUE "
				+ "LIMIT 1;";

		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(getEngine(), isAdminQuery);
			if(!wrapper.hasNext()) {
				throw new IllegalAccessException("User is not an admin and cannot perform this operation");
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE, e);
		} finally {
			if(wrapper != null) {
				try {
					wrapper.close();
				} catch (IOException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		}
	}


	/**
	 * Get the form engine
	 * Since this is a resource, we just need to make sure we load
	 * after DBLoader is done loading
	 * @return
	 */
	public IDatabaseEngine getEngine() {
		if(formEngine == null) {
			formEngine = Utility.getDatabase(FormBuilder.FORM_BUILDER_ENGINE_NAME);
			AbstractFormBuilder.generateFormPermissionTable(formEngine);
		}
		return formEngine;
	}
}
