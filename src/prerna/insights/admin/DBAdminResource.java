/*******************************************************************************
 * Copyright 2015 SEMOSS.ORG
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
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

import prerna.rdf.engine.api.IEngine;
import prerna.rdf.engine.impl.QuestionAdministrator;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

public class DBAdminResource {
	
	Logger logger = Logger.getLogger(DBAdminResource.class.getName());

	@POST
	@Path("/delete")
	@Produces("application/json")
	public Object delete(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		Gson gson = new Gson();
		
		String enginesString = form.getFirst("engine");
		String perspectivesString = form.getFirst("perspective");
		String questionsString = form.getFirst("question");

		Hashtable<String, Boolean> results = new Hashtable<String, Boolean>();
		
		if (questionsString!=null){
			Vector<String> questions = gson.fromJson(questionsString, Vector.class);
			IEngine engine = getEngine(enginesString, request);
			QuestionAdministrator questionAdmin = new QuestionAdministrator(engine);
			for (String questionTitle: questions){
				//davy this is what we really need to create
				questionAdmin.deleteQuestion(perspectivesString, questionTitle);
			}
			questionAdmin.createQuestionXMLFile();
			
		}
		else if (perspectivesString!=null){
			Vector<String> perspectives = gson.fromJson(perspectivesString, Vector.class);
			IEngine engine = getEngine(enginesString, request);
			QuestionAdministrator questionAdmin = new QuestionAdministrator(engine);
			for(String perspective: perspectives){
				results.put(perspective, questionAdmin.deleteAllFromPersp(perspective));
			}
			
		}
		else if(enginesString!=null){
			Vector<String> engines = gson.fromJson(enginesString, Vector.class);
			for(String engineString: engines){
				IEngine engine = getEngine(engineString, request);
				results.put(engineString, deleteEngine(engine, request));
			}
		}
  		return Response.status(200).entity(WebUtility.getSO(results)).build();
	}
  	
  	@POST
	@Path("reorderPerspective")
	@Produces("application/json")
  	public Response reorderPerspective(MultivaluedMap<String, String> form, @Context HttpServletRequest request){
		Gson gson = new Gson();
		String perspective = form.getFirst("perspective");
		String enginesString = form.getFirst("engine");
		Vector<Hashtable<String, Object>> insightArray = gson.fromJson(form.getFirst("insights") + "", new TypeToken<Vector<Hashtable<String, Object>>>() {}.getType());

		IEngine engine = getEngine(enginesString, request);
		QuestionAdministrator questionAdmin = new QuestionAdministrator(engine);
		questionAdmin.reorderPerspective(perspective, insightArray);

		
  		return Response.status(200).entity(WebUtility.getSO("Success")).build();
  	}

	@Path("/insight")
	@Produces("application/json")
	public Object insight(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		String enginesString = form.getFirst("engine");
		IEngine engine = getEngine(enginesString, request);
		QuestionAdmin admin = new QuestionAdmin(engine, form);
		return admin;
	}
	
	public boolean deleteEngine(IEngine coreEngine, HttpServletRequest request)
	{
		String engineName = coreEngine.getEngineName();
		System.out.println("closing " + engineName);
		coreEngine.closeDB();
		System.out.println("db closed");
		System.out.println("deleting folder");
		String baseFolder = DIHelper.getInstance().getProperty("BaseFolder");
		String insightLoc = baseFolder + "/" + coreEngine.getProperty(Constants.INSIGHTS);
		System.out.println("insight file is  " + insightLoc);
		File insightFile = new File(insightLoc);
		File engineFolder = new File(insightFile.getParent());
		String folderName = engineFolder.getName();
		try {
			System.out.println("checking folder " + folderName + " against db " + engineName);//this check is to ensure we are deleting the right folder
			if(folderName.equals(engineName))
			{
				System.out.println("folder getting deleted is " + engineFolder.getAbsolutePath());
				FileUtils.deleteDirectory(engineFolder);
			}
			else{
				logger.error("Cannot delete database folder as folder name does not line up with engine name");
				//try deleting each file individually
				System.out.println("Deleting insight file " + insightLoc);
				insightFile.delete();

				String ontoLoc = baseFolder + "/" + coreEngine.getProperty(Constants.ONTOLOGY);
				if(ontoLoc != null){
					System.out.println("Deleting onto file " + ontoLoc);
					File ontoFile = new File(ontoLoc);
					ontoFile.delete();
				}

				String owlLoc = baseFolder + "/" + coreEngine.getProperty(Constants.OWL);
				if(owlLoc != null){
					System.out.println("Deleting owl file " + owlLoc);
					File owlFile = new File(owlLoc);
					owlFile.delete();
				}

				String jnlLoc = baseFolder + "/" + coreEngine.getProperty("com.bigdata.journal.AbstractJournal.file");
				if(jnlLoc != null){
					System.out.println("Deleting jnl file " + jnlLoc);
					File jnlFile = new File(jnlLoc);
					jnlFile.delete();
				}
			}
			String smss = coreEngine.getSMSS();
			System.out.println("Deleting smss " + smss);
			File smssFile = new File(smss);
			smssFile.delete();
			
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

			return true;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
		
		
	}
  	
  	private IEngine getEngine(String engineName, HttpServletRequest request){
		HttpSession session = request.getSession();
		IEngine engine = (IEngine)session.getAttribute(engineName);
		return engine;
  	}
}
