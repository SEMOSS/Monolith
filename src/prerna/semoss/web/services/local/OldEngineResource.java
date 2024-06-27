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
package prerna.semoss.web.services.local;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.IDatabaseEngine.DATABASE_TYPE;
import prerna.engine.api.IRawSelectWrapper;
import prerna.forms.AbstractFormBuilder;
import prerna.forms.FormBuilder;
import prerna.forms.FormFactory;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.reactor.PixelPlanner;
import prerna.reactor.job.JobReactor;
import prerna.reactor.legacy.playsheets.GetPlaysheetParamsReactor;
import prerna.reactor.legacy.playsheets.RunPlaysheetReactor;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@PermitAll
@Deprecated
public class OldEngineResource {

	private static final Logger logger = LogManager.getLogger(OldEngineResource.class);

	@Deprecated
	private static final String APP_KEY = "app";
	
	// gets everything specific to an engine
	// essentially this is a wrapper over the engine
	private IDatabaseEngine coreEngine = null;

	public void setEngine(IDatabaseEngine coreEngine)
	{
		logger.info("Setting core engine to " + coreEngine);
		this.coreEngine = coreEngine;
	}

	/**
	 * Gets a list of perspectives for the given engine
	 * @param request
	 * @return a hashtable with "perspectives" pointing to to array of perspectives (e.g. ["Generic-Perspective","Movie-Perspective"])
	 */
//	@GET
//	@Path("perspectives")
//	@Produces("application/json")
//	public Response getPerspectives(@Context HttpServletRequest request)
//	{
//		// if the type is null then send all the insights else only that
//		Hashtable<String, Vector<String>> hashtable = new Hashtable<String, Vector<String>>(); 
//		Vector<String> perspectivesVector = coreEngine.getPerspectives();
//		hashtable.put("perspectives", perspectivesVector);
////		return Response.status(200).entity(WebUtility.getSO(hashtable)).build();
//		return WebUtility.getResponse(hashtable, 200);
//	}

	// gets a particular insight
	// not sure if I should keep it as it is or turn this into a post because of the query
	@POST
	@Path("querys")
	@Produces("application/json")
	public Response queryDataSelect(MultivaluedMap<String, String> form)
	{
		// returns the insight
		// based on the current ID get the data
		// typically is a JSON of the insight
		// this will also cache it
		Gson gson = new Gson();
		String query = form.getFirst("query");
		String[] paramBind = gson.fromJson(form.getFirst("paramBind"), new TypeToken<String[]>() {}.getType());
		String[] paramValue = gson.fromJson(form.getFirst("paramValue"), new TypeToken<String[]>() {}.getType());
		//do the query binding server side isntead of on the front end.
		if(paramBind.length > 0 && paramValue.length > 0 && (paramBind.length == paramValue.length)){
			for(int i = 0; i < paramBind.length && query.contains(paramBind[i]); i++){
//				String paramValueStr = coreEngine.getTransformedNodeName(paramValue[i], false);
				String paramValueStr = paramValue[i];
				if(coreEngine.getDatabaseType() == DATABASE_TYPE.RDBMS){
					String paramValueTable = Utility.getInstanceName(paramValueStr);
					String paramValueCol = Utility.getClassName(paramValueStr);

					//very risky business going on right now.... will not work on other bindings
					if(paramValueCol != null) query = query.replaceFirst(paramBind[i], paramValueCol);
					if(paramValueTable != null) query = query.replaceFirst(paramBind[i], paramValueTable);

				} else {
					query = query.replaceFirst(paramBind[i], paramValueStr);
				}
			}
		}
		logger.info(Utility.cleanLogString(query));
		
		// flush data out
		List<Object[]> data = new Vector<Object[]>();
		IRawSelectWrapper wrapper = null;
		try {
			wrapper = WrapperManager.getInstance().getRawWrapper(coreEngine, query);
			while(wrapper.hasNext()) {
				data.add(wrapper.next().getRawValues());
			}
		} catch (Exception e) {
			logger.error(Constants.STACKTRACE,e);
		} finally {
			if(wrapper != null) {
				try {
					wrapper.close();
				} catch (IOException e) {
					logger.error(Constants.STACKTRACE, e);
				}
			}
		}
		
//		return Response.status(200).entity(WebUtility.getSO(data)).build();
		return WebUtility.getResponse(data, 200);
	}	

	/**
	 * Uses the title of an insight to get the Insight object as well as the options and params
	 * Insight object has label (e.g. What is the list of Directors?) and propHash which contains order, output, engine, sparql, uri, id
	 * @param insight
	 * @return
	 */
	@GET
	@Path("insight")
	@Produces("application/json")
	public Response getInsightParams(@QueryParam("insight") String insightId)
	{
		// mocking inputs until FE makes full pixel call
		GetPlaysheetParamsReactor paramR = new GetPlaysheetParamsReactor();
		paramR.In();
		GenRowStruct grs1 = new GenRowStruct();
		grs1.add(new NounMetadata(coreEngine.getEngineId(), PixelDataType.CONST_STRING));
		paramR.getNounStore().addNoun(ReactorKeysEnum.DATABASE.getKey(), grs1);
		paramR.getNounStore().addNoun(APP_KEY, grs1);
		GenRowStruct grs2 = new GenRowStruct();
		grs2.add(new NounMetadata(insightId, PixelDataType.CONST_STRING));
		paramR.getNounStore().addNoun(ReactorKeysEnum.ID.getKey(), grs2);
		
		NounMetadata retNoun = paramR.execute();
		return WebUtility.getResponse(retNoun.getValue(), 200);
	}

	/**
	 * Executes a particular insight or runs a custom query on the specified playsheet
	 * To run custom query: must pass playsheet and sparql
	 * To run stored insight: must pass insight (the actual question), and a string of params
	 * @param form
	 * @param request
	 * @param response
	 * @return playsheet.getData()--it depends on the playsheet
	 */
	@POST
	@Path("output")
	@Produces("application/json")
	public Response createOutput(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		HttpSession session = request.getSession(true);
		User user = ((User) session.getAttribute(Constants.SESSION_USER));
		
		Gson gson = new Gson();
		String insightId = form.getFirst("insight");
		Map<String, List<Object>> params = null;
		if(form.getFirst("params") != null && !form.getFirst("params").isEmpty()) {
			params = gson.fromJson(form.getFirst("params"), new TypeToken<Map<String, List<Object>>>() {}.getType());
		}
		
		// mocking inputs until FE makes full pixel call
		RunPlaysheetReactor playsheetRunReactor = new RunPlaysheetReactor();
		playsheetRunReactor.In();
		// make an insight to set
		// so we can pass the user 
		Insight dummyIn = new Insight();
		InsightStore.getInstance().put(dummyIn);
		InsightStore.getInstance().addToSessionHash(session.getId(), dummyIn.getInsightId());
		dummyIn.getVarStore().put(JobReactor.SESSION_KEY, new NounMetadata(session.getId(), PixelDataType.CONST_STRING));
		dummyIn.setUser(user);
		playsheetRunReactor.setInsight(dummyIn);
		PixelPlanner planner = new PixelPlanner();
		planner.setVarStore(dummyIn.getVarStore());
		playsheetRunReactor.setPixelPlanner(planner);
		GenRowStruct grs1 = new GenRowStruct();
		grs1.add(new NounMetadata(coreEngine.getEngineId(), PixelDataType.CONST_STRING));
		playsheetRunReactor.getNounStore().addNoun(ReactorKeysEnum.DATABASE.getKey(), grs1);
		playsheetRunReactor.getNounStore().addNoun(APP_KEY, grs1);
		GenRowStruct grs2 = new GenRowStruct();
		grs2.add(new NounMetadata(insightId, PixelDataType.CONST_STRING));
		playsheetRunReactor.getNounStore().addNoun(ReactorKeysEnum.ID.getKey(), grs2);
		if(params != null) {
			GenRowStruct grs3 = new GenRowStruct();
			grs3.add(new NounMetadata(params, PixelDataType.MAP));
			playsheetRunReactor.getNounStore().addNoun(ReactorKeysEnum.PARAM_KEY.getKey(), grs3);
		}
		
		NounMetadata retNoun = playsheetRunReactor.execute();
		return WebUtility.getResponse(retNoun.getValue(), 200);
	}

	@POST
	@Path("/commitFormData")
	@Produces("application/json")
	public Response commitFormData(@Context HttpServletRequest request, MultivaluedMap<String, String> form) 
	{
		String userId = null;
		try {
			HttpSession session = ((HttpServletRequest)request).getSession(false);
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			if(user.getAccessToken(AuthProvider.CAC) != null) {
				userId = user.getAccessToken(AuthProvider.CAC).getId();
			} else if(user.getAccessToken(AuthProvider.SAML) != null) {
				// if not CAC - we are using SMAL
				userId = user.getAccessToken(AuthProvider.SAML).getId();
			}
		} catch(Exception e) {
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, "Could not identify user");
			return WebUtility.getResponse(err, 400);
		}
		if(userId == null) {
			Map<String, String> err = new HashMap<String, String>();
			err.put(Constants.ERROR_MESSAGE, "Could not identify user");
			return WebUtility.getResponse(err, 400);
		}
		Gson gson = new Gson();
		try {
			String formData = form.getFirst("formData");
			Map<String, Object> engineHash = gson.fromJson(formData, new TypeToken<Map<String, Object>>() {}.getType());
			// new way is having specific form builder class for each type of engine
			AbstractFormBuilder formbuilder = FormFactory.getFormBuilder(this.coreEngine);
			if(formbuilder != null) {
				//TODO: need to build this out for RDBMS engines!!!
				formbuilder.commitFormData(engineHash, userId);
			} else {
				// old way is super messy since it has both implementations of inserting
				// into both rdbms and rdf.. super annoying to go through
				FormBuilder.commitFormData(this.coreEngine, engineHash, userId);
			}
		} catch(Exception e) {
			logger.error(Constants.STACKTRACE,e);
			return WebUtility.getResponse(gson.toJson(e.getMessage()), 400);
		}

		return WebUtility.getResponse("success", 200);
	}

	@POST
	@Path("/getAuditLogForEngine")
	@Produces("application/json")
	public Response getAuditLogForEngine(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		Gson gson = new Gson();
		Map<String, Object> auditInfo = null;
		try {
			auditInfo = FormBuilder.getAuditDataForEngine(this.coreEngine.getEngineId());
		} catch(Exception e) {
			logger.error(Constants.STACKTRACE,e);
			return WebUtility.getResponse(gson.toJson(e.getMessage()), 400);
		}

		return WebUtility.getResponse(gson.toJson(auditInfo), 200);
	}
	
//	@GET
//	@Path("/exportDatabase")
//	@Produces("application/zip")
//	public Response exportDatabase(@Context HttpServletRequest request) {
//		HttpSession session = request.getSession();
//		String engineId = coreEngine.getEngineId();
//		String engineName = coreEngine.getEngineName();
//
//		// we want to start exporting the solr documents as well
//		// since we want to move away from using the rdbms insights for that
//		DBAdminResource dbAdmin = new DBAdminResource();
//		MultivaluedMap<String, String> form = new MultivaluedHashMap<String, String>();
//		form.putSingle("engineName", engineId);
//		dbAdmin.exportDbSolrInfo(form , request);
//		
//		// close the engine so we can export it
//		session.removeAttribute(engineId);
//		DIHelper.getInstance().removeLocalProperty(engineId);
//		coreEngine.close();
//		
//		LOGGER.info("Attending to export engine = " + engineId);
//		File zip = ZipDatabase.zipEngine(engineId, engineName);
//		
//		Response resp = Response.ok(zip)
//				.header("x-filename", zip.getName())
//				.header("content-type", "application/zip")
//				.header("Content-Disposition", "attachment; filename=\"" + zip.getName() + "\"" ).build();
//		
//		return resp;
//	}

}