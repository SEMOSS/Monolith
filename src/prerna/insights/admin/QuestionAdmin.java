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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import prerna.rdf.engine.api.IEngine;
import prerna.rdf.engine.impl.QuestionAdministrator;
import prerna.util.DIHelper;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

public class QuestionAdmin {

	IEngine coreEngine;
	String output = "";

	public QuestionAdmin(IEngine coreEngine) {
		this.coreEngine = coreEngine;
	}

	@POST
	@Path("add")
	@Produces("application/json")
	public Response addInsight(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request) {
		Gson gson = new Gson();
		String selectedEngine = form.getFirst("engine");
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

		Vector<String> parameterDependList = gson.fromJson(
				form.getFirst("parameterDependList"), Vector.class);
		Vector<String> parameterQueryList = gson.fromJson(
				form.getFirst("parameterQueryList"), Vector.class);
		Vector<String> parameterOptionList = gson.fromJson(
				form.getFirst("parameterOptionList"), Vector.class);
		ArrayList<String> questionList = gson.fromJson(
				form.getFirst("questionList"), ArrayList.class);

		String selectedPerspective = form.getFirst("selectedPerspective");

		String xmlFile = "db/" + selectedEngine + "/" + selectedEngine
				+ "_Questions.XML";
		String baseFolder = DIHelper.getInstance().getProperty("BaseFolder");

		QuestionAdministrator questionAdmin = new QuestionAdministrator(
				this.coreEngine, questionList, selectedPerspective,
				"Add Question");
		questionAdmin.questionList = questionList;

		questionKey = questionAdmin.createQuestionKey(perspective);
		questionAdmin.addQuestion(perspective, questionKey, questionOrder,
				question, sparql, layout, questionDescription,
				parameterDependList, parameterQueryList, parameterOptionList);
		questionAdmin.createQuestionXMLFile(xmlFile, baseFolder);

		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}

	@POST
	@Path("edit")
	@Produces("application/json")
	public Response editInsight(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request) {
		Gson gson = new Gson();
		String selectedEngine = form.getFirst("engine");
		String perspective = form.getFirst("perspective");
		String questionKey = form.getFirst("currentQuestionKey");
		String questionOrder = form.getFirst("questionOrder");
		String question = form.getFirst("question");
		String sparql = form.getFirst("sparql");
		String layout = form.getFirst("layout");
		String questionDescription = form.getFirst("questionDescription");
		if (questionDescription.equals("null")) {
			questionDescription = null;
		}

		Vector<String> parameterDependList = gson.fromJson(
				form.getFirst("parameterDependList"), Vector.class);
		Vector<String> parameterQueryList = gson.fromJson(
				form.getFirst("parameterQueryList"), Vector.class);
		Vector<String> parameterOptionList = gson.fromJson(
				form.getFirst("parameterOptionList"), Vector.class);
		ArrayList<String> questionList = gson.fromJson(
				form.getFirst("questionList"), ArrayList.class);
		// boolean existingPerspective =
		// form.getFirst("existingPerspective").equals("true");
		// boolean existingAutoGenQuestionKey =
		// form.getFirst("existingAutoGenQuestionKey").equals("true");

		String currentPerspective = gson.fromJson(
				form.getFirst("currentPerspective"), String.class);
		String currentQuestionKey = form.getFirst("currentQuestionKey");
		String currentQuestionOrder = form.getFirst("currentQuestionOrder");
		String currentQuestion = form.getFirst("currentQuestion");
		String currentSparql = form.getFirst("currentSparql");
		String currentLayout = form.getFirst("currentLayout");
		String currentQuestionDescription = form
				.getFirst("currentQuestionDescription");
		Vector<String> currentParameterDependList = gson.fromJson(
				form.getFirst("currentParameterDependList"), Vector.class);
		Vector<String> currentParameterQueryList = gson.fromJson(
				form.getFirst("currentParameterQueryList"), Vector.class);
		Vector<String> currentParameterOptionList = gson.fromJson(
				form.getFirst("currentParameterOptionList"), Vector.class);
		String currentNumberofQuestions = form
				.getFirst("currentNumberOfQuestions");

		String selectedPerspective = form.getFirst("selectedPerspective");

		String xmlFile = "db/" + selectedEngine + "/" + selectedEngine
				+ "_Questions.XML";
		String baseFolder = DIHelper.getInstance().getProperty("BaseFolder");

		QuestionAdministrator questionAdmin = new QuestionAdministrator(
				this.coreEngine, questionList, selectedPerspective,
				"Edit Question");

		if (!perspective.equals(currentPerspective)) {
			questionKey = null;
		}

		questionAdmin.modifyQuestion(perspective, questionKey, questionOrder,
				question, sparql, layout, questionDescription,
				parameterDependList, parameterQueryList, parameterOptionList,
				currentPerspective, currentQuestionKey, currentQuestionOrder,
				currentQuestion, currentSparql, currentLayout,
				currentQuestionDescription, currentParameterDependList,
				currentParameterQueryList, currentParameterOptionList,
				currentNumberofQuestions);
		questionAdmin.createQuestionXMLFile(xmlFile, baseFolder);

		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}

	@POST
	@Path("delete")
	@Produces("application/json")
	public Response deleteInsight(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request) {
		Gson gson = new Gson();
		String selectedEngine = form.getFirst("engine");
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

		Vector<String> parameterDependList = gson.fromJson(
				form.getFirst("parameterDependList"), Vector.class);
		Vector<String> parameterQueryList = gson.fromJson(
				form.getFirst("parameterQueryList"), Vector.class);
		Vector<String> parameterOptionList = gson.fromJson(
				form.getFirst("parameterOptionList"), Vector.class);
		ArrayList<String> questionList = gson.fromJson(
				form.getFirst("questionList"), ArrayList.class);
		// boolean existingPerspective =
		// form.getFirst("existingPerspective").equals("true");
		// boolean existingAutoGenQuestionKey =
		// form.getFirst("existingAutoGenQuestionKey").equals("true");
		String selectedPerspective = form.getFirst("selectedPerspective");

		QuestionAdministrator questionAdmin = new QuestionAdministrator(
				this.coreEngine, questionList, selectedPerspective,
				"Delete Question");
		// questionAdmin.existingAutoGenQuestionKey =
		// existingAutoGenQuestionKey;
		// questionAdmin.existingPerspective = existingPerspective;
		questionAdmin.questionList = questionList;

		String xmlFile = "db/" + selectedEngine + "/" + selectedEngine
				+ "_Questions.XML";
		String baseFolder = DIHelper.getInstance().getProperty("BaseFolder");

		// questionKey = questionAdmin.createQuestionKey(perspective);
		questionAdmin.deleteQuestion(perspective, questionKey, questionOrder,
				question, sparql, layout, questionDescription,
				parameterDependList, parameterQueryList, parameterOptionList);
		questionAdmin.createQuestionXMLFile(xmlFile, baseFolder);

		return Response.status(200).entity(WebUtility.getSO("Success")).build();
	}

}
