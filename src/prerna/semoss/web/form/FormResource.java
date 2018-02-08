package prerna.semoss.web.form;

import java.security.cert.X509Certificate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import prerna.engine.api.IEngine;
import prerna.engine.impl.rdbms.RDBMSNativeEngine;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/form")
public class FormResource {

	public static final String FORM_BUILDER_ENGINE_NAME = "form_builder_engine";
	public static final String AUDIT_FORM_SUFFIX = "_FORM_LOG";
	
	@POST
	@Path("/modifyUserAccess")
	@Produces("application/json")
	public Response modifyUserAccess(MultivaluedMap<String, String> form) {	
		IEngine formBuilderEng = Utility.getEngine(FORM_BUILDER_ENGINE_NAME);

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
		
		IEngine coreEngine = Utility.getEngine(dbName);
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
		IEngine coreEngine = Utility.getEngine(dbName);
		
		AbstractFormBuilder formbuilder = FormFactory.getFormBuilder(coreEngine);
		formbuilder.setUser(userid);
		formbuilder.certifyInstance(instanceType, instanceName);
		return WebUtility.getResponse("success", 200);
	}	
	
	@POST
	@Path("/getUserInstanceAuth")
	@Produces("applicaiton/json")
	public Response getUserInstanceAuth(@Context HttpServletRequest request) throws InvalidNameException {
		X509Certificate[] certs = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
		if(certs == null || certs.length == 0) {
			return WebUtility.getResponse("you messed up", 400);
		}

		IEngine formBuilderEng = Utility.getEngine(FORM_BUILDER_ENGINE_NAME);
		
		// the x509 for the user
		// and the instances they have access to
		String x509Id = null;
		Map<String, String> userAccessableInstances = new HashMap<String, String>();

		for(int i = 0; i < certs.length; i++) {
			X509Certificate cert = certs[i];

			String dn = cert.getSubjectX500Principal().getName();
			LdapName ldapDN = new LdapName(dn);
			for(Rdn rdn: ldapDN.getRdns()) {
				if(rdn.getType().equals("CN")) {
					String fullNameAndId = rdn.getValue().toString();
					// TODO: figure out how to do this well
					// we are getting a weird value coming through from one of the certs
					// which is causing issues
					// going to hack around this for now
					// for CAC card, we know it must contain a 10 digit number at the end
					// so make sure length is 10 and we can cast it to an integer
					String[] split = null;
					if((split = fullNameAndId.split("\\.")).length > 0) {
						String possibleId = split[split.length-1];
						if(possibleId.length() == 10) {
							try {
								Integer.parseInt(possibleId);
							} catch(NumberFormatException e) {
								// we know this is not a valid cac id
								// so move on
								continue;
							}
							x509Id = possibleId;
							break;
						}
					}
				}
			}
		}
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
	
//	@POST
//	@Path("/getAllAvailableForms")
//	@Produces("application/json")
//	public Response getAllAvailableForms() {
//		String query = "SELECT FORM_NAME, FORM_LOCATION FROM FORM_METADATA";
//		List<Map<String, String>> formsList = new Vector<Map<String, String>>();
//
//		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(formBuilderEng, query);
//		String[] names = wrapper.getVariables();
//		while(wrapper.hasNext()) {
//			ISelectStatement ss = wrapper.next();
//			Map<String, String> formMap = new Hashtable<String, String>();
//			formMap.put("formName", ss.getVar(names[0]) + "");
//			formMap.put("formLocation", ss.getVar(names[1]) + "");
//			formsList.add(formMap);
//		}
//		
//		return WebUtility.getResponse(formsList, 200);
//	}

//	@POST
//	@Path("/saveForm")
//	@Produces("application/json")	
//	public Response saveForm(MultivaluedMap<String, String> form) 
//	{
//		String formName = form.getFirst("formName");
//		String formLocation = form.getFirst("formLocation");
//
//		try {
//			FormBuilder.saveForm(formBuilderEng, formName, formLocation);
//		} catch (IOException e) {
//			return WebUtility.getResponse(e.getMessage(), 400);
//		}
//
//		return WebUtility.getResponse("saved successfully", 200);
//	}
	
//	@POST
//	@Path("/saveFormData")
//	@Produces("application/json")
//	public Response saveFormData(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
//	{
//		String userId = ((User)request.getSession().getAttribute(Constants.SESSION_USER)).getId();
//		String formName = form.getFirst("formName");
//		String formData = form.getFirst("formData");
//		String formTableName = getFormTableFromName(formName);
//		try {
//			FormBuilder.saveFormData(formBuilderEng, formTableName, userId, formData);
//		} catch(Exception e) {
//			e.printStackTrace();
//			return WebUtility.getResponse("error saving data", 400);
//		}
//
//		return WebUtility.getResponse("success", 200);
//	}
	
//	@POST
//	@Path("/getFormStagingData")
//	@Produces("application/json")
//	public Response getFormStagingData(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
//	{
//		String formName = form.getFirst("formName");
//		String formTableName = getFormTableFromName(formName);
//		List<Map<String, String>> results = null;
//		try {
//			results = FormBuilder.getStagingData(formBuilderEng, formTableName);
//		} catch(Exception e) {
//			e.printStackTrace();
//			return WebUtility.getResponse("error retrieving data", 400);
//			
//		}
//
//		return WebUtility.getResponse(results, 200);
//	}
	
//	@POST
//	@Path("/deleteFormDataFromStaging")
//	@Produces("application/json")
//	public Response deleteFormDataFromStaging(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
//	{
//		Gson gson = new Gson();
//		String formName = form.getFirst("formName");
//		String[] formIds = gson.fromJson(form.getFirst("ids"), String[].class);
//		try {
//			FormBuilder.deleteFromStaggingArea(formBuilderEng, formName, formIds);
//		} catch(Exception e) {
//			e.printStackTrace();
//			return WebUtility.getResponse("error deleting staging data", 400);
//		}
//
//		return WebUtility.getResponse("success", 200);
//	}
	
//	@POST
//	@Path("/deleteForm")
//	@Produces("application/json")
//	public Response deleteForm(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
//		String formName = form.getFirst("formName");
//		String formTableName = getFormTableFromName(formName);
//		
//		// delete form information
//		String deleteQuery = "DELETE FROM FORM_METADATA WHERE FORM_TABLE ='" + formTableName + "'"; 
//		formBuilderEng.removeData(deleteQuery);
//		// drop form table
//		deleteQuery = "DROP TABLE " + formName;
//		formBuilderEng.removeData(deleteQuery);
//		
//		return WebUtility.getResponse("success", 200);
//	}
	
//	private String getFormTableFromName(String formName) {
//		String query = "SELECT FORM_TABLE FROM FORM_METADATA WHERE FORM_NAME = '" + formName + "'";
//		
//		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(formBuilderEng, query);
//		String[] names = wrapper.getVariables();
//		wrapper.hasNext();
//		ISelectStatement ss = wrapper.next();
//		return ss.getVar(names[0]).toString();
//	}
	
}
