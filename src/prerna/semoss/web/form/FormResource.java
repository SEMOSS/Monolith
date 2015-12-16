package prerna.semoss.web.form;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;

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
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.services.util.WebUtility;

@Path("/form")
public class FormResource {

	private static final String FORM_BUILDER_ENGINE_NAME = "form_builder_engine";
	private IEngine formBuilderEng;
	
	public FormResource() {
		this.formBuilderEng = (IEngine) DIHelper.getInstance().getLocalProp(FORM_BUILDER_ENGINE_NAME);
	}
	
	
	@POST
	@Path("/getAllAvailableForms")
	@Produces("application/json")
	public Response getAllAvailableForms() {
		String query = "SELECT FORM_NAME FORM_LOCATION FROM FORM_METADATA";
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
		
		return Response.status(200).entity(WebUtility.getSO(formsList)).build();
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
			return Response.status(400).entity(WebUtility.getSO(e.getMessage())).build();
		}

		return Response.status(200).entity(WebUtility.getSO("saved successfully")).build();
	}
	
	@POST
	@Path("/saveFormData")
	@Produces("application/json")
	public Response saveFormData(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		String userId = ((User)request.getSession().getAttribute(Constants.SESSION_USER)).getId();
		String formName = form.getFirst("formName");
		String formData = form.getFirst("formData");
		try {
			FormBuilder.saveFormData(formBuilderEng, formName, userId, formData);
		} catch(Exception e) {
			e.printStackTrace();
			return Response.status(200).entity(WebUtility.getSO("error saving data")).build();
		}

		return Response.status(200).entity(WebUtility.getSO("success")).build();
	}
	
	@POST
	@Path("/getFormStagingData")
	@Produces("application/json")
	public Response getFormStagingData(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		String formName = form.getFirst("formName");
		List<Map<String, String>> results = null;
		try {
			results = FormBuilder.getStagingData(formBuilderEng, formName);
		} catch(Exception e) {
			e.printStackTrace();
			return Response.status(200).entity(WebUtility.getSO("error retrieving data")).build();
		}

		return Response.status(200).entity(WebUtility.getSO((results))).build();
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
			return Response.status(200).entity(WebUtility.getSO("error deleting staging data")).build();
		}

		return Response.status(200).entity(WebUtility.getSO("success")).build();
	}
	
}
