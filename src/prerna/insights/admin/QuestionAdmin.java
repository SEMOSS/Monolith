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
package prerna.insights.admin;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.solr.client.solrj.SolrServerException;

import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.InsightAdministrator;
import prerna.solr.SolrIndexEngine;
import prerna.web.services.util.WebUtility;

public class QuestionAdmin {

//	private static final Logger LOGGER = Logger.getLogger(QuestionAdmin.class.getName());
//	private static Gson gson = new Gson();

	private AbstractEngine coreEngine;
	
	@Deprecated
	public QuestionAdmin(AbstractEngine coreEngine) {
		this.coreEngine = coreEngine;
	}
	
	@POST
	@Path("delete")
	@Produces("application/json")
	@Deprecated
	public Response deleteInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String insightID = form.getFirst("insightID");
		InsightAdministrator admin = new InsightAdministrator(this.coreEngine.getInsightDatabase());
		admin.dropInsight(insightID);

		try {
			List<String> removeList = new ArrayList<String>();
			removeList.add(coreEngine.getEngineName() + "_" + insightID);
			SolrIndexEngine.getInstance().removeInsight(removeList);
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException | IOException e) {
			e.printStackTrace();
		}
		return WebUtility.getResponse("Success", 200);
	}
	
//	@POST
//	@Path("addFromAction")
//	@Produces("application/json")
//	public Response addNewInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
//		/*
//		 * FE passes the following keys
//		 * insightName - String -  The name of the insight to save
//		 * layout - String - The final layout of the main panel
//		 * saveRecipe - Array - The pkql recipe that the user wants to save
//		 * description - String - The description describing the insight
//		 * tags - Array - The tags associated with the insight
//		 * url - String - The url that monolith is deployed as
//		 * runRecipe - Array - If the insight contains a parameter, we use these recipe steps to capture the image
//		 */
//		
//		LOGGER.info("Adding new insight");
//		LOGGER.info("1) Add insight to insights engine");
//
//		String insightId = form.getFirst("insightID");
//		Insight in = InsightStore.getInstance().get(insightId);
//		
//		// grab the insight name
//		String insightName = form.getFirst("insightName");
//		// grab the layout
//		String layout = form.getFirst("layout");
//		// grab the recipe that we need to save
//		String[] pkqlRecipeToSave = gson.fromJson(form.getFirst("saveRecipe"), String[].class);
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
////		if(!(in.getDataMaker() instanceof Dashboard)) {
////			pkqlRecipeToSave = fixCauseFeDumb(pkqlRecipeToSave);
////		} else {
//			pkqlRecipeToSave = otherFixCauseFeDumb(pkqlRecipeToSave);
////		}
//		
//		// add the recipe to the insights database
//		InsightAdministrator admin = new InsightAdministrator(this.coreEngine.getInsightDatabase());
//		String newRdbmsId = admin.addInsight(insightName, layout, pkqlRecipeToSave);
//		LOGGER.info("1) Done");
//
//		LOGGER.info("2) Add insight to solr");
//		// now add it to solr
//		// get the description
//		String description = form.getFirst("description");
//		// get the tags
//		String tagsString = form.getFirst("tags");
//		List<String> tags = new Vector<String>();
//		if (tagsString != null && !tagsString.isEmpty()) {
//			tags = gson.fromJson(tagsString, new TypeToken<List<String>>() {}.getType());
//		}
//		// get the user id
//		String userId = getUserId(request);
//		addNewInsightToSolr(newRdbmsId, insightName, layout, description, tags, userId);
//		LOGGER.info("2) Done");
//
//		// since we process the image on the BE
//		// we need to start a new thread that will go ahead and save the image for the insight
//		LOGGER.info("3) Starting new thread to add insgiht image");
//		String url = form.getFirst("url");
//		String[] runRecipe = gson.fromJson(form.getFirst("runRecipe"), String[].class);
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
////		if(!(in.getDataMaker() instanceof Dashboard)) {
////			pkqlRecipeToSave = fixCauseFeDumb(pkqlRecipeToSave);
////		} else {
//			pkqlRecipeToSave = otherFixCauseFeDumb(pkqlRecipeToSave);
////		}
//		
//		LOGGER.info("3) Done - thread will be running async");
//		LOGGER.info("Done adding new insight");
//
//		Map<String, Object> retMap = new HashMap<String, Object>();
//		retMap.put("core_engine", this.coreEngine.getEngineName());
//		retMap.put("core_engine_id", newRdbmsId);
//		retMap.put("insightName", insightName);
//		if (newRdbmsId != null) {
//			return WebUtility.getResponse(retMap, 200);
//		} else {
//			return WebUtility.getResponse("Error adding insight", 500);
//		}
//	}
//	
//	/**
//	 * Add metadata around the insight into solr for searching
//	 * @param insightIdToSave
//	 * @param insightName
//	 * @param layout
//	 * @param description
//	 * @param tags
//	 * @param userId
//	 */
//	private void addNewInsightToSolr(String insightIdToSave, String insightName, String layout, String description, List<String> tags, String userId) {
//		Map<String, Object> solrInsights = new HashMap<>();
//		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
//		Date date = new Date();
//		String currDate = dateFormat.format(date);
//		solrInsights.put(SolrIndexEngine.STORAGE_NAME, insightName);
//		solrInsights.put(SolrIndexEngine.TAGS, tags);
//		solrInsights.put(SolrIndexEngine.LAYOUT, layout);
//		solrInsights.put(SolrIndexEngine.CREATED_ON, currDate);
//		solrInsights.put(SolrIndexEngine.MODIFIED_ON, currDate);
//		solrInsights.put(SolrIndexEngine.LAST_VIEWED_ON, currDate);
//		solrInsights.put(SolrIndexEngine.DESCRIPTION, description);
//		solrInsights.put(SolrIndexEngine.CORE_ENGINE_ID, Integer.parseInt(insightIdToSave));
//		solrInsights.put(SolrIndexEngine.USER_ID, userId);
//
//		// TODO: figure out which engines are used within this insight
//		String engineName = this.coreEngine.getEngineName();
//		solrInsights.put(SolrIndexEngine.CORE_ENGINE, this.coreEngine.getEngineName());
//		solrInsights.put(SolrIndexEngine.ENGINES, new HashSet<String>().add(engineName));
//
//		try {
//			SolrIndexEngine.getInstance().addInsight(engineName + "_" + insightIdToSave, solrInsights);
//		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException | IOException e1) {
//			e1.printStackTrace();
//		}
//	}
//	
//	/**
//	 * Get the user id from the request or return "default"
//	 * @param request
//	 * @return
//	 */
//	private String getUserId(HttpServletRequest request) {
//		HttpSession session = request.getSession(true);
//		User user = ((User) session.getAttribute(Constants.SESSION_USER));
//		String userId = null;
//		if(user!= null) {
//			userId = user.getId();
//		} else {
//			userId = "default";
//		}
//		return userId;
//	}
//	
//	@POST
//	@Path("editFromAction")
//	@Produces("application/json")
//	public Response editInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
//		/*
//		 * FE passes the following keys
//		 * insightId - String - the insight id for the in-memory insight
//		 * insightName - String -  The name of the insight to save
//		 * layout - String - The final layout of the main panel
//		 * saveRecipe - Array - The pkql recipe that the user wants to save
//		 * description - String - The description describing the insight
//		 * tags - Array - The tags associated with the insight
//		 * url - String - The url that monolith is deployed as
//		 * runRecipe - Array - If the insight contains a parameter, we use these recipe steps to capture the image
//		 */
//		
//		LOGGER.info("Updating existing insight");
//		LOGGER.info("1) Update insight in insights engine");
//
//		String insightId = form.getFirst("insightID");
//		Insight in = InsightStore.getInstance().get(insightId);
//		String existingRdbmsId = in.getRdbmsId();
//		
//		// grab the insight name
//		String insightName = form.getFirst("insightName");
//		// grab the layout
//		String layout = form.getFirst("layout");
//		// grab the recipe that we need to save
//		String[] pkqlRecipeToSave = gson.fromJson(form.getFirst("saveRecipe"), String[].class);
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
////		if(!(in.getDataMaker() instanceof Dashboard)) {
////			pkqlRecipeToSave = fixCauseFeDumb(pkqlRecipeToSave);
////		} else {
//			pkqlRecipeToSave = otherFixCauseFeDumb(pkqlRecipeToSave);
////		}
//				
//		// add the recipe to the insights database
//		InsightAdministrator admin = new InsightAdministrator(this.coreEngine.getInsightDatabase());
//		admin.updateInsight(existingRdbmsId, insightName, layout, pkqlRecipeToSave);
//		LOGGER.info("1) Done");
//
//		LOGGER.info("2) Add insight to solr");
//		// now add it to solr
//		// get the description
//		String description = form.getFirst("description");
//		// get the tags
//		String tagsString = form.getFirst("tags");
//		List<String> tags = new Vector<String>();
//		if (tagsString != null && !tagsString.isEmpty()) {
//			tags = gson.fromJson(tagsString, new TypeToken<List<String>>() {}.getType());
//		}
//		// get the user id
//		String userId = getUserId(request);
//		editExistingInsightInSolr(existingRdbmsId, insightName, layout, description, tags, userId);
//		LOGGER.info("2) Done");
//
//		// since we process the image on the BE
//		// we need to start a new thread that will go ahead and save the image for the insight
//		LOGGER.info("3) Starting new thread to add insgiht image");
//		String url = form.getFirst("url");
//		String[] runRecipe = gson.fromJson(form.getFirst("runRecipe"), String[].class);
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
//		//TODO: get FE to fix this
////		if(!(in.getDataMaker() instanceof Dashboard)) {
////			pkqlRecipeToSave = fixCauseFeDumb(pkqlRecipeToSave);
////		} else {
//			pkqlRecipeToSave = otherFixCauseFeDumb(pkqlRecipeToSave);
////		}
//		
//		LOGGER.info("3) Done - thread will be running async");
//		LOGGER.info("Done adding new insight");
//
//		Map<String, Object> retMap = new HashMap<String, Object>();
//		retMap.put("core_engine", this.coreEngine.getEngineName());
//		retMap.put("core_engine_id", existingRdbmsId);
//		retMap.put("insightName", insightName);
//		if (existingRdbmsId != null) {
//			return WebUtility.getResponse(retMap, 200);
//		} else {
//			return WebUtility.getResponse("Error adding insight", 500);
//		}
//	}
//
//	/**
//	 * Edit an existing insight saved within solr
//	 * @param existingRdbmsId
//	 * @param insightName
//	 * @param layout
//	 * @param description
//	 * @param tags
//	 * @param userId
//	 */
//	private void editExistingInsightInSolr(String existingRdbmsId, String insightName, String layout, String description, List<String> tags, String userId) {
//		Map<String, Object> solrModifyInsights = new HashMap<>();
//		DateFormat dateFormat = SolrIndexEngine.getDateFormat();
//		Date date = new Date();
//		String currDate = dateFormat.format(date);
//		solrModifyInsights.put(SolrIndexEngine.STORAGE_NAME, insightName);
//		solrModifyInsights.put(SolrIndexEngine.TAGS, tags);
//		solrModifyInsights.put(SolrIndexEngine.DESCRIPTION, description);
//		solrModifyInsights.put(SolrIndexEngine.LAYOUT, layout);
//		solrModifyInsights.put(SolrIndexEngine.MODIFIED_ON, currDate);
//		solrModifyInsights.put(SolrIndexEngine.LAST_VIEWED_ON, currDate);
//
//		// TODO: figure out which engines are used within this insight
//		String engineName = this.coreEngine.getEngineName();
//		solrModifyInsights.put(SolrIndexEngine.CORE_ENGINE, this.coreEngine.getEngineName());
//		solrModifyInsights.put(SolrIndexEngine.ENGINES, new HashSet<String>().add(engineName));
//		
//		try {
//			SolrIndexEngine.getInstance().modifyInsight(engineName + "_" + existingRdbmsId, solrModifyInsights);
//		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException | IOException e1) {
//			e1.printStackTrace();
//		}
//	}
//	
//	
//	private String[] otherFixCauseFeDumb(String[] pixelRecipe) {
//		List<String> fixForFe = new Vector<String>();
//		fixForFe.add("data.frame('dashboard');");
//		for(int i = 0; i < pixelRecipe.length; i++) {
//			fixForFe.add(pixelRecipe[i]);
//		}
//		// edit the reference
//		return fixForFe.toArray(new String[]{});
//	}

}
