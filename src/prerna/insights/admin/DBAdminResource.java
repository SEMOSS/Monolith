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
import java.util.Hashtable;
import java.util.List;
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

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;

import com.google.gson.Gson;

import prerna.auth.User;
import prerna.auth.UserPermissionsMasterDB;
import prerna.cache.CacheFactory;
import prerna.engine.api.IEngine;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.QuestionAdministrator;
import prerna.nameserver.DeleteFromMasterDB;
import prerna.om.Insight;
import prerna.solr.SolrIndexEngine;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.services.util.WebUtility;

public class DBAdminResource {

	final static int MAX_CHAR = 100;
	Logger logger = Logger.getLogger(DBAdminResource.class.getName());
	boolean securityEnabled;

	@POST
	@Path("/delete")
	@Produces("application/json")
	public Object delete(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		
		String enginesString = form.getFirst("engines");
		String perspectivesString = form.getFirst("perspectives");
		String questionsString = form.getFirst("insightIds");
		
		List<String> questionIds = null;
		if (questionsString!=null){
			questionIds = gson.fromJson(questionsString, List.class);
			AbstractEngine engine = getEngine(enginesString, request);
			QuestionAdministrator questionAdmin = new QuestionAdministrator(engine);
			try{
				questionAdmin.removeQuestion(questionIds.toArray(new String[questionIds.size()]));
				questionIds = SolrIndexEngine.getSolrIdFromInsightEngineId(engine.getEngineName(), questionIds);
			}catch (RuntimeException e){
				//reload question xml from file if it errored
				//otherwise xml gets corrupted
				System.out.println("caught exception while deleting questions.................");
				e.printStackTrace();
				return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
			}
		}
		else if (perspectivesString!=null){
			AbstractEngine engine = getEngine(enginesString, request);
			QuestionAdministrator questionAdmin = new QuestionAdministrator(engine);
			try{
				Vector<String> perspectives = gson.fromJson(perspectivesString, Vector.class);
				questionIds = questionAdmin.removePerspective(perspectives.toArray(new String[perspectives.size()]));
				questionIds = SolrIndexEngine.getSolrIdFromInsightEngineId(engine.getEngineName(), questionIds);

			}catch (RuntimeException e){
				//reload question xml from file if it errored
				//otherwise xml gets corrupted
				System.out.println("caught exception while deleting perspectives.................");
				e.printStackTrace();
				return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
			}
			
		}
		else if(enginesString!=null){
			Vector<String> engines = gson.fromJson(enginesString, Vector.class);
			UserPermissionsMasterDB permissions = new UserPermissionsMasterDB();
			for(String engineString: engines){
				IEngine engine = getEngine(engineString, request);
				deleteEngine(engine, request);
				if(this.securityEnabled) {
					if(request.getSession().getAttribute(Constants.SESSION_USER) != null) {
						permissions.deleteEngine(((User) request.getSession().getAttribute(Constants.SESSION_USER)).getId(), engineString);
					} else {
						return Response.status(400).entity("Please log in to delete databases.").build();
					}
				}
			}
		}
		
		if(questionIds != null) {
			SolrIndexEngine solrE;
			try {
				solrE = SolrIndexEngine.getInstance();
				if(solrE.serverActive()) {
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
  		return Response.status(200).entity(WebUtility.getSO("success")).build();
	}
  	
  	@POST
	@Path("reorderPerspective")
	@Produces("application/json")
  	public Response reorderPerspective(MultivaluedMap<String, String> form, @Context HttpServletRequest request){
		Gson gson = new Gson();
		String perspective = form.getFirst("perspective");
		String enginesString = form.getFirst("engine");
		String insightsString = form.getFirst("insights");
		List<String> questionIds = gson.fromJson(insightsString, List.class);

		AbstractEngine engine = getEngine(enginesString, request);
		QuestionAdministrator questionAdmin = new QuestionAdministrator(engine);
		try{
			questionAdmin.reorderPerspective(perspective, questionIds);
		} catch(RuntimeException e){

			System.out.println("caught exception while reordering.................");
			e.printStackTrace();
			return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
		}

		
  		return Response.status(200).entity(WebUtility.getSO("Success")).build();
  	}

	@Path("insight-{engine}")
	public Object insight(@PathParam("engine") String engineString, @Context HttpServletRequest request)
	{
//		String enginesString = form.getFirst("engine");
		AbstractEngine engine = getEngine(engineString, request);
		QuestionAdmin admin = new QuestionAdmin(engine);
		return admin;
	}
	
	public boolean deleteEngine(IEngine coreEngine, HttpServletRequest request)
	{
		String engineName = coreEngine.getEngineName();
		coreEngine.deleteDB();
//		System.out.println("closing " + engineName);
//		coreEngine.closeDB();
//		System.out.println("db closed");
//		System.out.println("deleting folder");
//		String baseFolder = DIHelper.getInstance().getProperty("BaseFolder");
//		String insightLoc = baseFolder + "/" + coreEngine.getProperty(Constants.INSIGHTS);
//		System.out.println("insight file is  " + insightLoc);
//		File insightFile = new File(insightLoc);
//		File engineFolder = new File(insightFile.getParent());
//		String folderName = engineFolder.getName();
//		try {
//			System.out.println("checking folder " + folderName + " against db " + engineName);//this check is to ensure we are deleting the right folder
//			if(folderName.equals(engineName))
//			{
//				System.out.println("folder getting deleted is " + engineFolder.getAbsolutePath());
//				FileUtils.deleteDirectory(engineFolder);
//			}
//			else{
//				logger.error("Cannot delete database folder as folder name does not line up with engine name");
//				//try deleting each file individually
//				System.out.println("Deleting insight file " + insightLoc);
//				insightFile.delete();
//
//				String ontoLoc = baseFolder + "/" + coreEngine.getProperty(Constants.ONTOLOGY);
//				if(ontoLoc != null){
//					System.out.println("Deleting onto file " + ontoLoc);
//					File ontoFile = new File(ontoLoc);
//					ontoFile.delete();
//				}
//
//				String owlLoc = baseFolder + "/" + coreEngine.getProperty(Constants.OWL);
//				if(owlLoc != null){
//					System.out.println("Deleting owl file " + owlLoc);
//					File owlFile = new File(owlLoc);
//					owlFile.delete();
//				}
//
//				String jnlLoc = baseFolder + "/" + coreEngine.getProperty("com.bigdata.journal.AbstractJournal.file");
//				if(jnlLoc != null){
//					System.out.println("Deleting jnl file " + jnlLoc);
//					File jnlFile = new File(jnlLoc);
//					jnlFile.delete();
//				}
//			}
//			String smss = coreEngine.getSMSS();
//			System.out.println("Deleting smss " + smss);
//			File smssFile = new File(smss);
//			smssFile.delete();
//			
			//remove from session
			HttpSession session = request.getSession();
			ArrayList<Hashtable<String,String>> engines = (ArrayList<Hashtable<String,String>>)session.getAttribute(Constants.ENGINES);
			for(Hashtable<String, String> engine : engines){
				String engName = engine.get("name");
				if(engName.equals(engineName)){
					engines.remove(engine);
					System.out.println("Removed from engines");
					session.setAttribute(Constants.ENGINES, engines);
					break;//
				}
			}
			session.removeAttribute(engineName);
			
			//remove from dihelper... this is absurd
			String engineNames = (String)DIHelper.getInstance().getLocalProp(Constants.ENGINES);
			engineNames = engineNames.replace(";" + engineName, "");
			DIHelper.getInstance().setLocalProperty(Constants.ENGINES, engineNames);
			
			DeleteFromMasterDB remover = new DeleteFromMasterDB(Constants.LOCAL_MASTER_DB_NAME);
			remover.deleteEngine(engineName);
			
			SolrIndexEngine solrE;
			try {
				solrE = SolrIndexEngine.getInstance();
				if(solrE.serverActive()) {
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
//		} catch (IOException e) {
//			e.printStackTrace();
//			return false;
//		}	
	}
	
  	private AbstractEngine getEngine(String engineName, HttpServletRequest request){
		HttpSession session = request.getSession();
		AbstractEngine engine = (AbstractEngine)session.getAttribute(engineName);
		return engine;
  	}
  	
  	public void setSecurityEnabled(boolean securityEnabled) {
  		this.securityEnabled = securityEnabled;
  	}
  	
	@POST
	@Path ("/deleteCache")
	@Produces ("application/json")
	public Response deleteDbCache(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		String dbName = form.getFirst("engine");
		String insightID = form.getFirst("insightID");
		String questionName = form.getFirst("questionName");
		Insight in = new Insight(getEngine(dbName, request), "", "");
		in.setRdbmsId(insightID);
		in.setInsightName(questionName);
		
		CacheFactory.getInsightCache(CacheFactory.CACHE_TYPE.DB_INSIGHT_CACHE).deleteCacheFolder(in);
		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}
}
