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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocumentList;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.cache.CacheFactory;
import prerna.engine.api.IEngine;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.InsightAdministrator;
import prerna.nameserver.DeleteFromMasterDB;
import prerna.om.Insight;
import prerna.rpa.RPAProps;
import prerna.solr.SolrDocumentExportWriter;
import prerna.solr.SolrIndexEngine;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

public class DBAdminResource {

	private static final Logger LOGGER = Logger.getLogger(DBAdminResource.class.getName());
	private static final int MAX_CHAR = 100;
	private boolean securityEnabled;

	public void setSecurityEnabled(boolean securityEnabled) {
		this.securityEnabled = securityEnabled;
	}

	@POST
	@Path("/scheduleJob")
	@Produces("application/json")
	public Response scheduleJob(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String json = form.getFirst("json");
		String fileName = form.getFirst("fileName");
		try {
			String jsonDirectory = RPAProps.getInstance().getProperty(RPAProps.JSON_DIRECTORY_KEY);
			String jsonFilePath = jsonDirectory + fileName + ".json";
			try (FileWriter file = new FileWriter(jsonDirectory + fileName + ".json")) {
				file.write(json);
			} catch (IOException e) {
				String errorMessage = "failed to write file to " + jsonFilePath + ": " + e.toString();
				return WebUtility.getResponse(errorMessage.substring(0,
						(errorMessage.length() < MAX_CHAR) ? errorMessage.length() : MAX_CHAR), 500);
			}
		} catch (Exception e) {
			String errorMessage = "failed to retrieve the json directory: " + e.toString();
			return WebUtility.getResponse(errorMessage.substring(0,
					(errorMessage.length() < MAX_CHAR) ? errorMessage.length() : MAX_CHAR), 500);
		}
		return WebUtility.getResponse("success", 200);
	}
	
	@POST
	@Path("/unscheduleJob")
	@Produces("application/json")
	public Response unscheduleJob(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String fileName = form.getFirst("fileName");
		try {
			String jsonDirectory = RPAProps.getInstance().getProperty(RPAProps.JSON_DIRECTORY_KEY);
			String jsonFilePath = jsonDirectory + fileName + ".json";
			File file = new File(jsonFilePath);
			try {
				file.delete();
			} catch (SecurityException e) {
				String errorMessage = "failed to delete file " + jsonFilePath + ": " + e.toString();
				return WebUtility.getResponse(errorMessage.substring(0,
						(errorMessage.length() < MAX_CHAR) ? errorMessage.length() : MAX_CHAR), 500);
			}
		} catch (Exception e) {
			String errorMessage = "failed to retrieve the json directory: " + e.toString();
			return WebUtility.getResponse(errorMessage.substring(0,
					(errorMessage.length() < MAX_CHAR) ? errorMessage.length() : MAX_CHAR), 500);
		}
		return WebUtility.getResponse("success", 200);
	}
	
	// TODO delete
	/*
	@POST
	@Path("/quartz")
	@Produces("application/json")
	public void scheduleJob(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String json = form.getFirst("json");
		final String baseFolder = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER);
		Date date = new Date();
		String stringDate = date.toString();
		stringDate = stringDate.replaceAll("\\s+", "_").replaceAll(":", "_");
		try (FileWriter file = new FileWriter(baseFolder + "\\quartz\\job_" + stringDate + ".json")) {
			file.write(json);
			System.out.println("Successfully Copied JSON Object to File...");
			System.out.println("JSON Object: " + json);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	*/
	
	@POST
	@Path("/delete")
	@Produces("application/json")
	public Object delete(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();

		String enginesString = form.getFirst("engines");
//		String perspectivesString = form.getFirst("perspectives");
		String questionsString = form.getFirst("insightIds");

		List<String> questionIds = null;
		if (questionsString != null) {
			questionIds = gson.fromJson(questionsString, List.class);
			AbstractEngine engine = getEngine(enginesString, request);
			InsightAdministrator admin = new InsightAdministrator(engine.getInsightDatabase());
			try {
				admin.dropInsight(questionIds.toArray(new String[questionIds.size()]));
				questionIds = SolrIndexEngine.getSolrIdFromInsightEngineId(engine.getEngineName(), questionIds);
			} catch (RuntimeException e) {
				// reload question xml from file if it errored
				// otherwise xml gets corrupted
				System.out.println("caught exception while deleting questions.................");
				e.printStackTrace();
//				return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0,
//						(e.toString().length() < MAX_CHAR) ? e.toString().length() : MAX_CHAR))).build();
				return WebUtility.getResponse(e.toString().substring(0,
						(e.toString().length() < MAX_CHAR) ? e.toString().length() : MAX_CHAR), 500);
			}
//		} else if (perspectivesString != null) {
//			AbstractEngine engine = getEngine(enginesString, request);
//			QuestionAdministrator questionAdmin = new QuestionAdministrator(engine);
//			try {
//				Vector<String> perspectives = gson.fromJson(perspectivesString, Vector.class);
//				questionIds = questionAdmin.removePerspective(perspectives.toArray(new String[perspectives.size()]));
//				questionIds = SolrIndexEngine.getSolrIdFromInsightEngineId(engine.getEngineName(), questionIds);
//
//			} catch (RuntimeException e) {
//				// reload question xml from file if it errored
//				// otherwise xml gets corrupted
//				System.out.println("caught exception while deleting perspectives.................");
//				e.printStackTrace();
////				return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0,
////						(e.toString().length() < MAX_CHAR) ? e.toString().length() : MAX_CHAR))).build();
//				
//				return WebUtility.getResponse(e.toString().substring(0,
//						(e.toString().length() < MAX_CHAR) ? e.toString().length() : MAX_CHAR), 500);
//			}

		} else if (enginesString != null) {
			Vector<String> engines = gson.fromJson(enginesString, Vector.class);
			UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
			ArrayList<String> ownedEngines = permissions
					.getUserOwnedEngines(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId());
			for (String engineString : engines) {
				IEngine engine = getEngine(engineString, request);
				if (this.securityEnabled) {
					if (ownedEngines.contains(engineString)) {
						deleteEngine(engine, request);
						permissions.deleteEngine(
								((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(),
								engineString);
					} else {
//						return Response.status(400).entity("You do not have access to delete this database.").build();
						return WebUtility.getResponse("You do not have access to delete this database.", 400);
					}
				} else {
					deleteEngine(engine, request);
				}
			}
		}

		if (questionIds != null) {
			SolrIndexEngine solrE;
			try {
				solrE = SolrIndexEngine.getInstance();
				if (solrE.serverActive()) {
					solrE.removeInsight(questionIds);
				}
			} catch (SolrServerException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (KeyManagementException e) {
				e.printStackTrace();
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
			} catch (KeyStoreException e) {
				e.printStackTrace();
			}
		}
//		return Response.status(200).entity(WebUtility.getSO("success")).build();
		return WebUtility.getResponse("success", 200);
	}

//	@POST
//	@Path("reorderPerspective")
//	@Produces("application/json")
//	public Response reorderPerspective(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
//		Gson gson = new Gson();
//		String perspective = form.getFirst("perspective");
//		String enginesString = form.getFirst("engine");
//		String insightsString = form.getFirst("insights");
//		List<String> questionIds = gson.fromJson(insightsString, List.class);
//
//		AbstractEngine engine = getEngine(enginesString, request);
//		QuestionAdministrator questionAdmin = new QuestionAdministrator(engine);
//		try {
//			questionAdmin.reorderPerspective(perspective, questionIds);
//		} catch (RuntimeException e) {
//
//			System.out.println("caught exception while reordering.................");
//			e.printStackTrace();
//			
//			String errorMessage = e.toString().substring(0, (e.toString().length() < MAX_CHAR) ? e.toString().length() : MAX_CHAR);
////			return Response.status(500).entity(WebUtility.getSO(
////					e.toString().substring(0, (e.toString().length() < MAX_CHAR) ? e.toString().length() : MAX_CHAR)))
////					.build();
//			
//			return WebUtility.getResponse(errorMessage, 500);
//		}
//
////		return Response.status(200).entity(WebUtility.getSO("Success")).build();
//		return WebUtility.getResponse("success", 200);
//	}

	@Path("insight-{engine}")
	public Object insight(@PathParam("engine") String engineString, @Context HttpServletRequest request) {
		AbstractEngine engine = getEngine(engineString, request);
		QuestionAdmin admin = new QuestionAdmin(engine);
		return admin;
	}

	private boolean deleteEngine(IEngine coreEngine, HttpServletRequest request) {
		String engineName = coreEngine.getEngineName();
		coreEngine.deleteDB();
		// remove from session
		HttpSession session = request.getSession();
		ArrayList<Hashtable<String, String>> engines = (ArrayList<Hashtable<String, String>>) session
				.getAttribute(Constants.ENGINES);
		for (Hashtable<String, String> engine : engines) {
			String engName = engine.get("name");
			if (engName.equals(engineName)) {
				engines.remove(engine);
				System.out.println("Removed from engines");
				session.setAttribute(Constants.ENGINES, engines);
				break;//
			}
		}
		session.removeAttribute(engineName);

		// remove from dihelper... this is absurd
		String engineNames = (String) DIHelper.getInstance().getLocalProp(Constants.ENGINES);
		engineNames = engineNames.replace(";" + engineName, "");
		DIHelper.getInstance().setLocalProperty(Constants.ENGINES, engineNames);

		DeleteFromMasterDB remover = new DeleteFromMasterDB(Constants.LOCAL_MASTER_DB_NAME);
		remover.deleteEngineRDBMS(engineNames);
		//remover.deleteEngineRDBMS(engineName);

		SolrIndexEngine solrE;
		try {
			solrE = SolrIndexEngine.getInstance();
			if (solrE.serverActive()) {
				solrE.deleteEngine(engineName);
			}
		} catch (KeyManagementException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (KeyStoreException e) {
			e.printStackTrace();
		}

		return true;
	}

	private AbstractEngine getEngine(String engineName, HttpServletRequest request) {
		HttpSession session = request.getSession();
		AbstractEngine engine = null;
		if (session.getAttribute(engineName) instanceof IEngine)
			engine = (AbstractEngine) session.getAttribute(engineName);
		else
			engine = (AbstractEngine) Utility.getEngine(engineName);
		return engine;
	}

	@POST
	@Path("/deleteCache")
	@Produces("application/json")
	public Response deleteDbCache(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String dbName = form.getFirst("engine");
		String insightID = form.getFirst("insightID");
		String questionName = form.getFirst("questionName");
		Insight in = new Insight(getEngine(dbName, request).getEngineName(), insightID);
		in.setInsightName(questionName);

		CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).deleteInsightCache(in);
//		return Response.status(200).entity(WebUtility.getSO("Success")).build();
		return WebUtility.getResponse("success", 200);
	}

	////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////
	/////////////////////////////////// START SOLR
	//////////////////////////////////////////////////////////////////////////////////// /////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////

	/**
	 * Modify the insight tags
	 * 
	 * @return
	 */
	@POST
	@Path("modifyInsightTags")
	@Produces("application/json")
	public StreamingOutput modifyInsightTags(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String solrId = form.getFirst("id");
		String tagsStr = form.getFirst("tags");

		Gson gson = new Gson();
		List<String> tags = gson.fromJson(tagsStr, new TypeToken<List<String>>() {
		}.getType());

		Map<String, Object> fieldsToModify = new HashMap<String, Object>();
		fieldsToModify.put(SolrIndexEngine.TAGS, tags);
		fieldsToModify.put(SolrIndexEngine.INDEXED_TAGS, tags);

		return WebUtility.getSO(modifyInsight(solrId, fieldsToModify));
		
	}

	/**
	 * Modify the insight image
	 * 
	 * @return
	 */
	@POST
	@Path("modifyInsightImage")
	@Produces("application/json")
	public StreamingOutput modifyInsightImage(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request) {
		String solrId = form.getFirst("id");
		String imageStr = form.getFirst("image");

		Map<String, Object> fieldsToModify = new HashMap<String, Object>();
		fieldsToModify.put(SolrIndexEngine.IMAGE, imageStr);

		return WebUtility.getSO(modifyInsight(solrId, fieldsToModify));
	}

	private String modifyInsight(String solrId, Map<String, Object> fieldsToModify) {
		String returnMessage = null;
		try {
			SolrIndexEngine.getInstance().modifyInsight(solrId, fieldsToModify);
			returnMessage = "success";
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | SolrServerException
				| IOException e) {
			e.printStackTrace();
			returnMessage = e.getMessage();
			if (returnMessage == null || returnMessage.isEmpty()) {
				returnMessage = "Unknown error with modifying tags";
			}
		}
		return returnMessage;
	}

	@POST
	@Path("exportDbSolrText")
	@Produces("application/json")
	public StreamingOutput exportDbSolrInfo(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String engineName = form.getFirst("engineName");
		LOGGER.info("Starting to export solr documents for engine : " + engineName);
		// grab the smss file to determine where we should export this file
		String smssFile = (String)DIHelper.getInstance().getCoreProp().getProperty(engineName + "_" + Constants.STORE);
		// create this text file in the same location as the insight_database
		// actual process to load
		FileInputStream fis = null;
		String message = "success";
		try {
			Properties daProp = new Properties();
			fis = new FileInputStream(smssFile);
			daProp.load(fis);
			
			// if there is already a text file that we need to update
			// get the existing file from the smss
			String solrTextFile = null;
			String smssUpdateValue = null;
			boolean overrideExistingSolrFile = false;
			if(daProp.containsKey(Constants.SOLR_EXPORT)) {
				overrideExistingSolrFile = true;
				solrTextFile = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) 
						+ "/" + daProp.getProperty(Constants.SOLR_EXPORT);
			} else {
				// get the location of the insight rdbms and modify it for a new file
				smssUpdateValue = daProp.getProperty(Constants.RDBMS_INSIGHTS);
				// get ride of the file name to get the extension
				smssUpdateValue = smssUpdateValue.substring(0, smssUpdateValue.lastIndexOf("/")+1);
				// add a new extension
				smssUpdateValue = smssUpdateValue + engineName + "_Solr.txt";

				solrTextFile = DIHelper.getInstance().getProperty(Constants.BASE_FOLDER) 
						+ "/" + smssUpdateValue;
			}
			File solrFile = new File(solrTextFile);
			if(solrFile.exists()) {
				solrFile.delete();
			}
			
			SolrDocumentExportWriter writer = new SolrDocumentExportWriter(solrFile);
			SolrDocumentList docs = SolrIndexEngine.getInstance().getEngineInsights(engineName);
			LOGGER.info("Found " + docs.getNumFound() + " documents to export");
			writer.writeSolrDocument(docs);
			writer.closeExport();
			
			// update the smss to know this solr text file exists
			if(!overrideExistingSolrFile) {
				Utility.updateSMSSFile(smssFile, Constants.SOLR_EXPORT, smssUpdateValue);
			}
		} catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | 
				IOException | SolrServerException e) {
			e.printStackTrace();
			message = e.getMessage();
			if(message == null || message.isEmpty()) {
				message = "Unknown error occured while exporting solr information";
			}
		} finally {
			if(fis != null) {
				try {
					fis.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		
		return WebUtility.getSO(message);
	}

	////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////// END SOLR //////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////
}
