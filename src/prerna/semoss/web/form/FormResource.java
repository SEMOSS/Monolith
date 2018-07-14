package prerna.semoss.web.form;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

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
import prerna.engine.impl.rdbms.RDBMSNativeEngine;
import prerna.forms.AbstractFormBuilder;
import prerna.forms.FormBuilder;
import prerna.forms.FormFactory;
import prerna.nameserver.utility.MasterDatabaseUtility;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/form")
public class FormResource {

	@POST
	@Path("/modifyUserAccess")
	@Produces("application/json")
	public Response modifyUserAccess(MultivaluedMap<String, String> form) {	
		IEngine formBuilderEng = Utility.getEngine(FormBuilder.FORM_BUILDER_ENGINE_NAME);

		String addOrRemove = form.getFirst("addOrRemove");
		String userid = form.getFirst("userid");
		
        String query = null;
        if (addOrRemove.equals("Remove")) {        
        	query = "DELETE FROM FORMS_USER_ACCESS WHERE USER_ID = '" + userid + "';";
        } else if (addOrRemove.equals("Add")) {
    		String instancename = form.getFirst("instanceName");
    		// this is a boolean being represented by a string true/false
    		String owner = form.getFirst("ownerStatus");
    		
        	query = "INSERT INTO FORMS_USER_ACCESS (USER_ID, INSTANCE_NAME, IS_SYS_ADMIN) VALUES ('" + userid + "','" + instancename + "','" + owner + "');";
        } else {
        	return WebUtility.getResponse("Error: need to specify Add or Remove", 400);
        }
        
        // execute the insert statement
    	formBuilderEng.insertData(query);
    	// commit to engine
    	formBuilderEng.commit();
    	
		return WebUtility.getResponse("success", 200);
	}
	
	@POST
	@Path("/renameInstance")
	@Produces("application/json")
	public Response renameInstance(MultivaluedMap<String, String> form) {
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
	public Response certifyInstance(MultivaluedMap<String, String> form) {
		String userid = form.getFirst("userid");
		String instanceType = form.getFirst("instanceType");
		String instanceName = form.getFirst("instanceName");
		String dbName = form.getFirst("dbName");

		IEngine coreEngine = Utility.getEngine(MasterDatabaseUtility.testEngineIdIfAlias(dbName));
		
		AbstractFormBuilder formbuilder = FormFactory.getFormBuilder(coreEngine);
		formbuilder.setUser(userid);
		formbuilder.certifyInstance(instanceType, instanceName);
		return WebUtility.getResponse("success", 200);
	}	
	
	@POST
	@Path("/getUserInstanceAuth")
	@Produces("applicaiton/json")
	public Response getUserInstanceAuth(@Context HttpServletRequest request) throws InvalidNameException {
		String x509Id = null;
		try {
			HttpSession session = ((HttpServletRequest)request).getSession(false);
			User user = (User) session.getAttribute("semoss_user");
			x509Id = user.getAccessToken(AuthProvider.CAC).getId();
		} catch(Exception e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put("errorMessage", "Could not identify user");
			return WebUtility.getResponse(err, 400);
		}
		if(x509Id == null) {
			Map<String, String> err = new HashMap<String, String>();
			err.put("errorMessage", "Could not identify user");
			return WebUtility.getResponse(err, 400);
		}
		
		Map<String, String> userAccessableInstances = new HashMap<String, String>();

		IEngine formBuilderEng = Utility.getEngine(FormBuilder.FORM_BUILDER_ENGINE_NAME);
		// map to store the valid instances for the given user
		String query = "SELECT INSTANCE_NAME, IS_SYS_ADMIN FROM FORMS_USER_ACCESS WHERE USER_ID = '" + x509Id + "';";
		Map<String, Object> ret = (Map<String, Object>) formBuilderEng.execQuery(query);
		Statement stmt = (Statement) ret.get(RDBMSNativeEngine.STATEMENT_OBJECT);
		ResultSet rs = (ResultSet) ret.get(RDBMSNativeEngine.RESULTSET_OBJECT);
		try {
			while(rs.next()) {
				userAccessableInstances.put(rs.getString("INSTANCE_NAME"), rs.getString("IS_SYS_ADMIN"));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			try {
				if(rs != null) {
					rs.close();
				}
				if(stmt != null) {
					stmt.close();
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}

		Map<String, Object> returnData = new Hashtable<String, Object>();
		returnData.put("cac_id", x509Id);
		returnData.put("validInstances", userAccessableInstances);

		return WebUtility.getResponse(returnData, 200);
	}
}
