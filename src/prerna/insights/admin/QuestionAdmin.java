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

import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.QuestionAdministrator;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;

public class QuestionAdmin {

	AbstractEngine coreEngine;
	String output = "";
	final static int MAX_CHAR = 100;
//	MultivaluedMap<String, String> form;

	public QuestionAdmin(AbstractEngine coreEngine) {
		this.coreEngine = coreEngine;
//		this.form = form;
	}

	@POST
	@Path("add")
	@Produces("application/json")
	public Response addInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();
		String perspective = form.getFirst("perspective");
		String questionKey = form.getFirst("questionKey");
		String questionOrder = form.getFirst("questionOrder");
		String question = form.getFirst("question");
		String sparql = form.getFirst("sparql");
		String layout = form.getFirst("layout");
		String questionDescription = form.getFirst("questionDescription");
		if (questionDescription!=null && questionDescription.equals("null")) {
			questionDescription = null;
		}

		Vector<String> parameterDependList = gson.fromJson(form.getFirst("parameterDependList"), Vector.class);
		Vector<String> parameterQueryList = gson.fromJson(form.getFirst("parameterQueryList"), Vector.class);
		Vector<String> parameterOptionList = gson.fromJson(form.getFirst("parameterOptionList"), Vector.class);

		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);

		try{
			questionKey = questionAdmin.createQuestionKey(perspective);
			questionAdmin.cleanAddQuestion(perspective, questionKey, questionOrder,
					question, sparql, layout, questionDescription,
					parameterDependList, parameterQueryList, parameterOptionList);
			questionAdmin.createQuestionXMLFile();
		}catch(RuntimeException e){
			System.out.println("caught exception while adding question.................");
			e.printStackTrace();
			System.out.println("reverting xml........................");
			questionAdmin.revertQuestionXML();
			return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
		}

		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}

	@POST
	@Path("edit")
	@Produces("application/json")
	public Response editInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();
		String perspective = form.getFirst("perspective");
		String questionKey = form.getFirst("currentQuestionKey");
		String questionOrder = form.getFirst("questionOrder");
		String question = form.getFirst("question");
		String sparql = form.getFirst("sparql");
		String layout = form.getFirst("layout");
		String questionDescription = form.getFirst("questionDescription");
		if (questionDescription!=null && questionDescription.equals("null")) {
			questionDescription = null;
		}

		Vector<String> parameterDependList = gson.fromJson(form.getFirst("parameterDependList"), Vector.class);
		Vector<String> parameterQueryList = gson.fromJson(form.getFirst("parameterQueryList"), Vector.class);
		Vector<String> parameterOptionList = gson.fromJson(form.getFirst("parameterOptionList"), Vector.class);

		String currentPerspective = gson.fromJson(form.getFirst("currentPerspective"), String.class);
		String currentQuestionKey = form.getFirst("currentQuestionKey");
		String currentQuestionOrder = form.getFirst("currentQuestionOrder");
		String currentQuestion = form.getFirst("currentQuestion");
		String currentSparql = form.getFirst("currentSparql");
		String currentLayout = form.getFirst("currentLayout");
		String currentQuestionDescription = form
				.getFirst("currentQuestionDescription");
		Vector<String> currentParameterDependList = gson.fromJson(form.getFirst("currentParameterDependList"), Vector.class);
		Vector<String> currentParameterQueryList = gson.fromJson(form.getFirst("currentParameterQueryList"), Vector.class);
		Vector<String> currentParameterOptionList = gson.fromJson(form.getFirst("currentParameterOptionList"), Vector.class);
		String currentNumberofQuestions = form.getFirst("currentNumberOfQuestions");
		
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);

		if (!perspective.equals(currentPerspective)) {
			questionKey = null;
		}

		try{
			questionAdmin.modifyQuestion(perspective, questionKey, questionOrder,
					question, sparql, layout, questionDescription,
					parameterDependList, parameterQueryList, parameterOptionList,
					currentPerspective, currentQuestionKey, currentQuestionOrder,
					currentQuestion, currentSparql, currentLayout,
					currentQuestionDescription, currentParameterDependList,
					currentParameterQueryList, currentParameterOptionList,
					currentNumberofQuestions);
		}catch(RuntimeException e){
			System.out.println("caught exception while modifying question.................");
			e.printStackTrace();
			System.out.println("reverting xml........................");
			questionAdmin.revertQuestionXML();
			return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
		}

		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}

	@POST
	@Path("delete")
	@Produces("application/json")
	public Response deleteInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request) {
		Gson gson = new Gson();
		String perspective = form.getFirst("perspective");
		String questionKey = form.getFirst("questionKey");
		String questionOrder = form.getFirst("questionOrder");
		String question = form.getFirst("question");
		String sparql = form.getFirst("sparql");
		String layout = form.getFirst("layout");
		String questionDescription = form.getFirst("questionDescription");
		if (questionDescription.equals("null")) {
			questionDescription = null;
		}

		Vector<String> parameterDependList = gson.fromJson( form.getFirst("parameterDependList"), Vector.class);
		Vector<String> parameterQueryList = gson.fromJson( form.getFirst("parameterQueryList"), Vector.class);
		Vector<String> parameterOptionList = gson.fromJson( form.getFirst("parameterOptionList"), Vector.class);

		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine);

		try{
			questionAdmin.cleanDeleteQuestion(perspective, questionKey, questionOrder,
					question, sparql, layout, questionDescription,
					parameterDependList, parameterQueryList, parameterOptionList);
			questionAdmin.createQuestionXMLFile();
		}catch(RuntimeException e){
			System.out.println("caught exception while deleting question.................");
			e.printStackTrace();
			System.out.println("reverting xml........................");
			questionAdmin.revertQuestionXML();
			return Response.status(500).entity(WebUtility.getSO(e.toString().substring(0, (e.toString().length() < MAX_CHAR)?e.toString().length():MAX_CHAR))).build();
		}

		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}

}
