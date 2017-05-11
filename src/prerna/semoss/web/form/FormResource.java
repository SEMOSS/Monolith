package prerna.semoss.web.form;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;

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

import com.google.gson.Gson;

import prerna.auth.User;
import prerna.engine.api.IEngine;
import prerna.engine.api.IRawSelectWrapper;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.engine.impl.rdbms.RDBMSNativeEngine;
import prerna.engine.impl.rdf.BigDataEngine;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.rdf.main.NodeRenamer;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/form")
public class FormResource {

	public static final String FORM_BUILDER_ENGINE_NAME = "form_builder_engine";
	public static final String AUDIT_FORM_SUFFIX = "_FORM_LOG";
	
	private IEngine formBuilderEng;
	private IEngine userAccessEng;
	
	public FormResource() {
		this.formBuilderEng = Utility.getEngine(FORM_BUILDER_ENGINE_NAME);
		//TODO: add in user access eng
	}
	
	
	@POST
	@Path("/getAllAvailableForms")
	@Produces("application/json")
	public Response getAllAvailableForms() {
		String query = "SELECT FORM_NAME, FORM_LOCATION FROM FORM_METADATA";
		List<Map<String, String>> formsList = new Vector<Map<String, String>>();

		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(formBuilderEng, query);
		String[] names = wrapper.getVariables();
		while(wrapper.hasNext()) {
			ISelectStatement ss = wrapper.next();
			Map<String, String> formMap = new Hashtable<String, String>();
			formMap.put("formName", ss.getVar(names[0]) + "");
			formMap.put("formLocation", ss.getVar(names[1]) + "");
			formsList.add(formMap);
		}
		
//		return Response.status(200).entity(WebUtility.getSO(formsList)).build();
		return WebUtility.getResponse(formsList, 200);
	}

	@POST
	@Path("/saveForm")
	@Produces("application/json")	
	public Response saveForm(MultivaluedMap<String, String> form) 
	{
		String formName = form.getFirst("formName");
		String formLocation = form.getFirst("formLocation");

		try {
			FormBuilder.saveForm(formBuilderEng, formName, formLocation);
		} catch (IOException e) {
//			return Response.status(400).entity(WebUtility.getSO(e.getMessage())).build();
			return WebUtility.getResponse(e.getMessage(), 400);
		}

//		return Response.status(200).entity(WebUtility.getSO("saved successfully")).build();
		return WebUtility.getResponse("saved successfully", 200);
	}
	
	@POST
	@Path("/saveFormData")
	@Produces("application/json")
	public Response saveFormData(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		String userId = ((User)request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		String formName = form.getFirst("formName");
		String formData = form.getFirst("formData");
		String formTableName = getFormTableFromName(formName);
		try {
			FormBuilder.saveFormData(formBuilderEng, formTableName, userId, formData);
		} catch(Exception e) {
			e.printStackTrace();
//			return Response.status(400).entity(WebUtility.getSO("error saving data")).build();
			return WebUtility.getResponse("error saving data", 400);
		}

//		return Response.status(200).entity(WebUtility.getSO("success")).build();
		return WebUtility.getResponse("success", 200);
	}
	
	@POST
	@Path("/getFormStagingData")
	@Produces("application/json")
	public Response getFormStagingData(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		String formName = form.getFirst("formName");
		String formTableName = getFormTableFromName(formName);
		List<Map<String, String>> results = null;
		try {
			results = FormBuilder.getStagingData(formBuilderEng, formTableName);
		} catch(Exception e) {
			e.printStackTrace();
//			return Response.status(400).entity(WebUtility.getSO("error retrieving data")).build();
			return WebUtility.getResponse("error retrieving data", 400);
			
		}

//		return Response.status(200).entity(WebUtility.getSO((results))).build();
		return WebUtility.getResponse(results, 200);
	}
	
	@POST
	@Path("/deleteFormDataFromStaging")
	@Produces("application/json")
	public Response deleteFormDataFromStaging(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		Gson gson = new Gson();
		String formName = form.getFirst("formName");
		String[] formIds = gson.fromJson(form.getFirst("ids"), String[].class);
		try {
			FormBuilder.deleteFromStaggingArea(formBuilderEng, formName, formIds);
		} catch(Exception e) {
			e.printStackTrace();
//			return Response.status(400).entity(WebUtility.getSO("error deleting staging data")).build();
			return WebUtility.getResponse("error deleting staging data", 400);
		}

//		return Response.status(200).entity(WebUtility.getSO("success")).build();
		return WebUtility.getResponse("success", 200);
	}
	
	@POST
	@Path("/renameNode")
	@Produces("application/json")
	public Response nodeRenamer(MultivaluedMap<String, String> form) 
	{
		String dbFilepath = DIHelper.getInstance().getProperty("BaseFolder");
		dbFilepath = dbFilepath + "\\db\\";
		
		String dbName = form.getFirst("dbName");
		String originalUri = form.getFirst("originalUri");
		String newUri = form.getFirst("newUri");
		String newInstanceName = newUri.substring(newUri.lastIndexOf('/')+1);
		
		NodeRenamer nodeRenamer = new NodeRenamer();
		
		BigDataEngine coreEngine = (BigDataEngine) DIHelper.getInstance().getLocalProp(dbName);
		
		if(coreEngine == null) {
			System.out.println("Need to instantiate database.");			
			
			String smssFilepath = dbFilepath + dbName + ".smss";
			coreEngine = new BigDataEngine();
			coreEngine.setEngineName(dbName);
			coreEngine.openDB(smssFilepath);
			DIHelper.getInstance().setLocalProperty(dbName, coreEngine);
		}
		
		// get all the subjects
		String upQuery = "SELECT DISTINCT ?s ?p ?o WHERE {"
				+ "BIND(<" + originalUri + "> AS ?s)"
				+ "{?s ?p ?o}"
				+ "}";

		List<Object[]> upTriples = new Vector<Object[]>();
		IRawSelectWrapper upIt = WrapperManager.getInstance().getRawWrapper(coreEngine, upQuery);
		nodeRenamer.storeValues(upIt, upTriples);

		// get all the objects
		String downQuery = "SELECT DISTINCT ?s ?p ?o WHERE {"
				+ "BIND(<" + originalUri + "> AS ?o)"
				+ "{?s ?p ?o}"
				+ "}";

		List<Object[]> downTriples = new Vector<Object[]>();
		IRawSelectWrapper downIt = WrapperManager.getInstance().getRawWrapper(coreEngine, downQuery);
		nodeRenamer.storeValues(downIt, downTriples);

		// now go through and modify where necessary
		nodeRenamer.deleteTriples(upTriples, coreEngine);
		nodeRenamer.deleteTriples(downTriples, coreEngine);

		nodeRenamer.addUpTriples(upTriples, coreEngine, newUri, newInstanceName);
		nodeRenamer.addDownTriples(downTriples, coreEngine, newUri);
		
		coreEngine.commit();
		
//		return Response.status(200).entity(WebUtility.getSO("success")).build();
		return WebUtility.getResponse("success", 200);
	}	
	
	@POST
	@Path("/deleteForm")
	@Produces("application/json")
	public Response deleteForm(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		String formName = form.getFirst("formName");
		String formTableName = getFormTableFromName(formName);
		
		// delete form information
		String deleteQuery = "DELETE FROM FORM_METADATA WHERE FORM_TABLE ='" + formTableName + "'"; 
		formBuilderEng.removeData(deleteQuery);
		// drop form table
		deleteQuery = "DROP TABLE " + formName;
		formBuilderEng.removeData(deleteQuery);
		
//		return Response.status(200).entity(WebUtility.getSO("success")).build();
		return WebUtility.getResponse("success", 200);
	}
	
	@POST
	@Path("/getUserInstanceAuth")
	@Produces("applicaiton/json")
	public Response getUserInstanceAuth(@Context HttpServletRequest request) throws InvalidNameException {
		X509Certificate[] certs = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
		if(certs == null || certs.length == 0) {
			return WebUtility.getResponse("you messed up", 400);
		} else {
			//TODO: use this id to get the systems the user has access to
			//i.e. create a sql query on the engine you will add at the top
			String x509Id = null;
			for(int i = 0; i < certs.length; i++) {
				X509Certificate cert = certs[i];
				
				String dn = cert.getSubjectX500Principal().getName();
				LdapName ldapDN = new LdapName(dn);
				for(Rdn rdn: ldapDN.getRdns()) {
					if(rdn.getType().equals("CN")) {
						x509Id = rdn.getValue().toString();
					}
				}
			}
			
			// create some query
			// run it on engine
			List<String> userAccessableInstances = new Vector<String>();
			Map<String, Object> ret = (Map<String, Object>) userAccessEng.execQuery("");
			ResultSet rs = (ResultSet) ret.get(RDBMSNativeEngine.RESULTSET_OBJECT);
			try {
				while(rs.next()) {
					// this rs is 1 based!!!
					userAccessableInstances.add(rs.getString(1));
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
			
			Map<String, Object> returnData = new Hashtable<String, Object>();
			returnData.put("cac_id", x509Id);
			returnData.put("validInstances", userAccessableInstances);
			
			return WebUtility.getResponse(returnData, 200);
		}
	}
	
	private String getFormTableFromName(String formName) {
		String query = "SELECT FORM_TABLE FROM FORM_METADATA WHERE FORM_NAME = '" + formName + "'";
		
		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(formBuilderEng, query);
		String[] names = wrapper.getVariables();
		wrapper.hasNext();
		ISelectStatement ss = wrapper.next();
		return ss.getVar(names[0]).toString();
	}
	
}
