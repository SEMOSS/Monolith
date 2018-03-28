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

import java.io.File;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.engine.api.IEngine;
import prerna.engine.api.IEngine.ENGINE_TYPE;
import prerna.engine.api.IRawSelectWrapper;
import prerna.engine.impl.AbstractEngine;
import prerna.forms.AbstractFormBuilder;
import prerna.forms.FormBuilder;
import prerna.forms.FormFactory;
import prerna.insights.admin.DBAdminResource;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.om.OldInsight;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.sablecc2.om.GenRowStruct;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.ReactorKeysEnum;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.sablecc2.reactor.legacy.playsheets.GetPlaysheetParamsReactor;
import prerna.solr.SolrIndexEngine;
import prerna.ui.helpers.OldInsightProcessor;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.util.ZipDatabase;
import prerna.web.services.util.WebUtility;

public class EngineResource {

	private static final Logger LOGGER = Logger.getLogger(EngineResource.class.getName());

	// gets everything specific to an engine
	// essentially this is a wrapper over the engine
	private IEngine coreEngine = null;

	public void setEngine(IEngine coreEngine)
	{
		LOGGER.info("Setting core engine to " + coreEngine);
		this.coreEngine = coreEngine;
	}

	/**
	 * Gets a list of perspectives for the given engine
	 * @param request
	 * @return a hashtable with "perspectives" pointing to to array of perspectives (e.g. ["Generic-Perspective","Movie-Perspective"])
	 */
	@GET
	@Path("perspectives")
	@Produces("application/json")
	public Response getPerspectives(@Context HttpServletRequest request)
	{
		// if the type is null then send all the insights else only that
		Hashtable<String, Vector<String>> hashtable = new Hashtable<String, Vector<String>>(); 
		Vector<String> perspectivesVector = coreEngine.getPerspectives();
		hashtable.put("perspectives", perspectivesVector);
//		return Response.status(200).entity(WebUtility.getSO(hashtable)).build();
		return WebUtility.getResponse(hashtable, 200);
	}

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
				if(coreEngine.getEngineType() == ENGINE_TYPE.RDBMS){
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
		System.out.println(query);
		
		// flush data out
		IRawSelectWrapper wrapper = WrapperManager.getInstance().getRawWrapper(coreEngine, query);
		List<Object[]> data = new Vector<Object[]>();
		while(wrapper.hasNext()) {
			data.add(wrapper.next().getRawValues());
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
		grs1.add(new NounMetadata(coreEngine.getEngineName(), PixelDataType.CONST_STRING));
		paramR.getNounStore().addNoun(ReactorKeysEnum.APP.getKey(), grs1);
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
	public Response createOutput(MultivaluedMap<String, String> form, @Context HttpServletRequest request, @Context HttpServletResponse response)
	{
		Gson gson = new Gson();
		String insight = form.getFirst("insight");
		UserPermissionsMasterDB tracker = new UserPermissionsMasterDB();
		HttpSession session = request.getSession();
		User user = ((User) session.getAttribute(Constants.SESSION_USER));
		String userId = "";
		if(user!= null) {
			userId = user.getId();
		}
		
		// executes the output and gives the data
		// executes the create runner
		// once complete, it would plug the output into the session
		// need to find a way where I can specify if I want to keep the result or not
		// params are typically passed on as
		// pairs like this
		// key$value~key2:value2 etc
		// need to find a way to handle other types than strings

		// if insight, playsheet and sparql are null throw bad data exception
//		if(insight == null) {
//			String playsheet = form.getFirst("layout");
//			String sparql = form.getFirst("sparql");
//			//check for sparql and playsheet; if not null then parameters have been passed in for preview functionality
//			if(sparql != null && playsheet != null){
//				Insight in = null;
//				Object obj = null;
//				try {
//					List<String> allSheets = PlaySheetRDFMapBasedEnum.getAllSheetNames();
//					String dmName = InsightsConverter.getDataMaker(playsheet, allSheets);
//					in = new Insight(coreEngine, dmName, playsheet);
//					Vector<DataMakerComponent> dmcList = new Vector<DataMakerComponent>();
//					DataMakerComponent dmc = new DataMakerComponent(coreEngine, sparql);
//					dmcList.add(dmc);
//					in.setDataMakerComponents(dmcList);
//					InsightStore.getInstance().put(in);
//					InsightCreateRunner insightRunner = new InsightCreateRunner(in);
//					obj = insightRunner.runWeb();
//				} catch (Exception ex) { //need to specify the different exceptions 
//					ex.printStackTrace();
//					Hashtable<String, String> errorHash = new Hashtable<String, String>();
//					errorHash.put("Message", "Error occured processing question.");
//					errorHash.put("Class", this.getClass().getName());
////					return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
//					return WebUtility.getResponse(errorHash, 500);
//				}
//
////				return Response.status(200).entity(WebUtility.getSO(obj)).build();
//				return WebUtility.getResponse(obj, 200);
//			}
//			else{
//				Hashtable<String, String> errorHash = new Hashtable<String, String>();
//				errorHash.put("Message", "No question defined.");
//				errorHash.put("Class", this.getClass().getName());
////				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
//				return WebUtility.getResponse(errorHash, 400);
//			}
//		}
//		else {
			Object obj = null;
			
			// if the insight is a read only insight
			// we store it and do not remove it since we can just send it back faster
			// and there is no chance of it affecting the output return
			String inEngine = this.coreEngine.getEngineName();
			Insight insightObj = InsightStore.getInstance().findInsightInStore(inEngine, insight);
			boolean isReadOnlyInsight = false;
			if(insightObj != null) {
				UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
				isReadOnlyInsight = permissions.isUserReadOnlyInsights(userId, inEngine, insight);
			}
			
			if(isReadOnlyInsight) {
				obj = insightObj.getWebData();
			} else {
				//Get the Insight, grab its ID
				insightObj = ((AbstractEngine)coreEngine).getInsight(insight).get(0);
				// set the user id into the insight
				insightObj.setUser( ((User) request.getSession().getAttribute(Constants.SESSION_USER)) );
				
				Map<String, List<Object>> params = gson.fromJson(form.getFirst("params"), new TypeToken<Map<String, List<Object>>>() {}.getType());
				if(insightObj.isOldInsight()) {
					((OldInsight) insightObj).setParamHash(params);
				}
				// check if the insight has already been cached
//				System.out.println("Params is " + params);
	
				try {
					InsightStore.getInstance().put(insightObj);
					if(insightObj.isOldInsight()) {
						// we have some old legacy stuff...
						// just run and return the object
						OldInsightProcessor processor = new OldInsightProcessor((OldInsight) insightObj);
						obj = processor.runWeb();
						((Map)obj).put("isPkqlRunnable", false);
						((Map)obj).put("recipe", new Object[0]);
						
						// TODO: why did we allow the FE to still require this when
						// we already pass a boolean that says this is not pkql....
						// wtf...
						
						HashMap insightMap = new HashMap();
						Map stuipdFEInsightGarabage = new HashMap();
						stuipdFEInsightGarabage.put("clear", false);
						stuipdFEInsightGarabage.put("closedPanels", new Object[0]);
						stuipdFEInsightGarabage.put("dataID", 0);
						stuipdFEInsightGarabage.put("feData", new HashMap());
						stuipdFEInsightGarabage.put("insightID", insightObj.getInsightId());
						stuipdFEInsightGarabage.put("newColumns", new HashMap());
						stuipdFEInsightGarabage.put("newInsights", new Object[0]);
						stuipdFEInsightGarabage.put("pkqlData", new Object[0]);
						insightMap.put("insights", new Object[]{stuipdFEInsightGarabage});
						((Map)obj).put("pkqlOutput", insightMap);
					} else {
						// TODO: this should no longer be used
						// TODO: this should no longer be used
						// TODO: this should no longer be used
						// TODO: this should no longer be used
						// it is fully encapsulated in pixel
						
						obj = new HashMap<String, String>();
						((Map) obj).put("recipe", insightObj.getPixelRecipe());
						((Map) obj).put("rdbmsID", insightObj.getRdbmsId());
						((Map) obj).put("insightID", insightObj.getInsightId());
						((Map) obj).put("title", insightObj.getInsightName());
						
						// this is only necessary to get dashboards to work...
//						String layout = insightObj.getOutput();
//						((Map) obj).put("layout", layout);
//						if(layout.equalsIgnoreCase("dashboard")) {
//							((Map) obj).put("dataMakerName", "Dashboard");
//						}
					}
				} catch (Exception ex) { //need to specify the different exceptions 
					ex.printStackTrace();
					Hashtable<String, String> errorHash = new Hashtable<String, String>();
					errorHash.put("Message", "Error occured processing question.");
					errorHash.put("Class", this.getClass().getName());
//					return Response.status(500).entity(WebUtility.getSO(errorHash)).build();
					return WebUtility.getResponse(errorHash, 500);
				}
			}
			
			// update security db user tracker
			tracker.trackInsightExecution(userId, coreEngine.getEngineName(), insightObj.getInsightId(), session.getId());
			// update global solr tracker
			try {
				SolrIndexEngine.getInstance().updateViewedInsight(coreEngine.getEngineName() + "_" + insight);
			} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException
					| IOException e) {
				e.printStackTrace();
			}

//			return Response.status(200).entity(WebUtility.getSO(obj)).build();
			return WebUtility.getResponse(obj, 200);
//		}
	}

	@POST
	@Path("/commitFormData")
	@Produces("application/json")
	public Response commitFormData(MultivaluedMap<String, String> form, @Context HttpServletRequest request) 
	{
		Gson gson = new Gson();
		try {
			String formData = form.getFirst("formData");
			Map<String, Object> engineHash = gson.fromJson(formData, new TypeToken<Map<String, Object>>() {}.getType());
			// new way is having specific form builder class for each type of engine
			AbstractFormBuilder formbuilder = FormFactory.getFormBuilder(this.coreEngine);
			if(formbuilder != null) {
				//TODO: need to build this out for RDBMS engines!!!
				formbuilder.commitFormData(engineHash);
			} else {
				// old way is super messy since it has both implementations of inserting
				// into both rdbms and rdf.. super annoying to go through
				FormBuilder.commitFormData(this.coreEngine, engineHash);
			}
		} catch(Exception e) {
			e.printStackTrace();
//			return Response.status(400).entity(WebUtility.getSO(gson.toJson(e.getMessage()))).build();
			return WebUtility.getResponse(gson.toJson(e.getMessage()), 400);
		}

//		return Response.status(200).entity(WebUtility.getSO(gson.toJson("success"))).build();
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
			auditInfo = FormBuilder.getAuditDataForEngine(this.coreEngine.getEngineName());
		} catch(Exception e) {
			e.printStackTrace();
//			return Response.status(400).entity(WebUtility.getSO(gson.toJson(e.getMessage()))).build();
			return WebUtility.getResponse(gson.toJson(e.getMessage()), 400);
		}

//		return Response.status(200).entity(WebUtility.getSO(gson.toJson(auditInfo))).build();
		return WebUtility.getResponse(gson.toJson(auditInfo), 200);
	}
	
	@GET
	@Path("/exportDatabase")
	@Produces("application/zip")
	public Response exportDatabase(@Context HttpServletRequest request) {
		HttpSession session = request.getSession();
		String engineName = coreEngine.getEngineName();
		
		// we want to start exporting the solr documents as well
		// since we want to move away from using the rdbms insights for that
		DBAdminResource dbAdmin = new DBAdminResource();
		MultivaluedMap<String, String> form = new MultivaluedHashMap<String, String>();
		form.putSingle("engineName", engineName);
		dbAdmin.exportDbSolrInfo(form , request);
		
		// close the engine so we can export it
		session.removeAttribute(engineName);
		DIHelper.getInstance().removeLocalProperty(engineName);
		coreEngine.closeDB();
		
		LOGGER.info("Attending to export engine = " + engineName);
		File zip = ZipDatabase.zipEngine(engineName);
		
		Response resp = Response.ok(zip)
				.header("x-filename", zip.getName())
				.header("content-type", "application/zip")
				.header("Content-Disposition", "attachment; filename=\"" + zip.getName() + "\"" ).build();
		
		return resp;
	}

}