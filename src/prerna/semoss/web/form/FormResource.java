package prerna.semoss.web.form;

import java.io.IOException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.TreeMap;

import javax.naming.InvalidNameException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.engine.api.IEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.forms.AbstractFormBuilder;
import prerna.forms.FormBuilder;
import prerna.forms.FormFactory;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/form")
public class FormResource {

	private IEngine formEngine;
	
	@POST
	@Path("/modifyUserAccess")
	@Produces("application/json")
	public Response modifyUserAccess(@Context HttpServletRequest request, MultivaluedMap<String, String> form) {
		String cacId;
		try {
			cacId = getCacId(request);
		} catch (IOException e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(err, 400);
		}
		
		try {
			throwErrorIfNotAdmin(cacId);
		} catch (IllegalAccessException e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(err, 400);
		}
		
		String addOrRemove = form.getFirst("addOrRemove");
		String userid = form.getFirst("userid");
		String instancename = form.getFirst("instanceName");
		//  this is only present if we are adding a user
		String owner = form.getFirst("ownerStatus");
		
        String query = null;
        if (addOrRemove.equals("Remove")) {
        	if(instancename != null && !instancename.isEmpty() && !instancename.equals("null") && !instancename.equals("undefined")) {
            	query = "DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = '" + userid + "' AND INSTANCE_NAME = '" + instancename + "';";
        	} else {
        		// remove all of user
            	query = "DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = '" + userid + "';";
        	}
        } else if (addOrRemove.equals("Add")) {
        	query = "INSERT INTO FORMS_USER_ACCESS (USER_ID, INSTANCE_NAME, IS_SYS_ADMIN) VALUES ('" + userid + "','" + instancename + "','" + owner + "');";
        } else {
        	return WebUtility.getResponse("Error: need to specify Add or Remove", 400);
        }
        
		IEngine formEngine = getEngine();
        // execute the query
		formEngine.insertData(query);
    	// commit to engine
		formEngine.commit();
    	
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
			err.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(err, 400);
		}
		
		try {
			throwErrorIfNotAdmin(cacId);
		} catch (IllegalAccessException e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(err, 400);
		}
		
		String dbName = form.getFirst("dbName");
		String origUri = form.getFirst("originalUri");
		String newUri = form.getFirst("newUri");
		boolean deleteInstanceBoolean = false;
		if(form.getFirst("deleteInstanceBoolean") != null) {
			deleteInstanceBoolean = Boolean.parseBoolean(form.getFirst("deleteInstanceBoolean"));
		}
		
		IEngine coreEngine = Utility.getEngine(MasterDatabaseUtility.testEngineIdIfAlias(dbName));		
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
			err.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(err, 400);
		}
		
		String dbName = form.getFirst("dbName");
		String instanceType = form.getFirst("instanceType");
		String instanceName = form.getFirst("instanceName");
		
		try {
			throwErrorIfNotSysAdmin(cacId, instanceName);
		} catch (IllegalAccessException e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(err, 400);
		}
		
		IEngine coreEngine = Utility.getEngine(MasterDatabaseUtility.testEngineIdIfAlias(dbName));		
		AbstractFormBuilder formbuilder = FormFactory.getFormBuilder(coreEngine);
		formbuilder.setUser(cacId);
		formbuilder.certifyInstance(instanceType, instanceName);
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
			cacId = getCacId(request);
		} catch (IOException e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put("errorMessage", e.getMessage());
			return WebUtility.getResponse(err, 400);
		}
		
		Map<String, String> userAccessableInstances = new TreeMap<String, String>();

		// map to store the valid instances for the given user
		String query = "SELECT INSTANCE_NAME, IS_SYS_ADMIN FROM FORMS_USER_ACCESS WHERE USER_ID = '" + cacId + "';";
		IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(getEngine(), query);
		while(wrapper.hasNext()) {
			Object[] values = wrapper.next().getValues();
			userAccessableInstances.put(values[0].toString(), values[1].toString());
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
		String x509Id = null;
		try {
			HttpSession session = ((HttpServletRequest)request).getSession(false);
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			x509Id = user.getAccessToken(AuthProvider.CAC).getId();
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
				+ "WHERE USER_ID='" + cacId + "' "
				+ "AND INSTANCE_NAME='ADMIN' "
				+ "LIMIT 1;";
		
		IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(getEngine(), isAdminQuery);
		try {
			if(!wrapper.hasNext()) {
				throw new IllegalAccessException("User is not an admin and cannot perform this operation");
			}
		} finally {
			wrapper.cleanUp();
		}
	}
	
	/**
	 * Check that user is an admin
	 * @param cacId
	 * @throws IllegalAccessException
	 */
	private void throwErrorIfNotSysAdmin(String cacId, String system) throws IllegalAccessException {
		String isAdminQuery = "SELECT * FROM FORMS_USER_ACCESS "
				+ "WHERE USER_ID='" + cacId + "' "
				+ "AND INSTANCE_NAME='" + system + "' "
				+ "AND IS_SYS_ADMIN=TRUE "
				+ "LIMIT 1;";
		
		IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(getEngine(), isAdminQuery);
		try {
			if(!wrapper.hasNext()) {
				throw new IllegalAccessException("User is not an admin and cannot perform this operation");
			}
		} finally {
			wrapper.cleanUp();
		}
	}
	
	
	/**
	 * Get the form engine
	 * Since this is a resource, we just need to make sure we load
	 * after DBLoader is done loading
	 * @return
	 */
	public IEngine getEngine() {
		if(formEngine == null) {
			formEngine = Utility.getEngine(FormBuilder.FORM_BUILDER_ENGINE_NAME);
		}
		return formEngine;
	}
}
