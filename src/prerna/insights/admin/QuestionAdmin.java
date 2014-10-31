package prerna.insights.admin;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class QuestionAdmin {
	
	IEngine coreEngine;
	String output = "";

	public QuestionAdmin(IEngine coreEngine){
		this.coreEngine = coreEngine;
	}
	
  	@POST
	@Path("add")
	@Produces("application/json")
  	public Response addInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request){
		Gson gson = new Gson();
		String selectedEngine = form.getFirst("engine");
		String perspective = form.getFirst("perspective");
		String questionKey = form.getFirst("questionKey");
		String question = form.getFirst("question");
		String sparql = form.getFirst("sparql");
		String layout = form.getFirst("layout");
		String questionDescription = form.getFirst("questionDescription");

		Vector<String> parameterDependList = gson.fromJson(form.getFirst("parameterDependList"), Vector.class);
		Vector<String> parameterQueryList = gson.fromJson(form.getFirst("parameterQueryList"), Vector.class);
		Vector<String> parameterOptionList = gson.fromJson(form.getFirst("parameterOptionList"), Vector.class);
		ArrayList<String> questionList = gson.fromJson(form.getFirst("questionList"), ArrayList.class);
		boolean existingPerspective = form.getFirst("existingPerspective").equals("true");
		boolean existingAutoGenQuestionKey = form.getFirst("existingAutoGenQuestionKey").equals("true");

		String selectedPerspective = form.getFirst("selectedPerspective");
		
		String xmlFile = "db/" + selectedEngine + "/" + selectedEngine
				+ "_Questions.XML";
		String baseFolder = DIHelper.getInstance().getProperty("BaseFolder");

		QuestionAdministrator.selectedEngine = selectedEngine;

		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine, questionList, selectedPerspective, "Add Question");
		questionAdmin.existingAutoGenQuestionKey = existingAutoGenQuestionKey;
		questionAdmin.existingPerspective = existingPerspective;
		questionAdmin.questionList = questionList;
		
		questionKey = questionAdmin.createQuestionKey(perspective);
		questionAdmin.addQuestion(perspective, questionKey, question, sparql, layout, questionDescription, parameterDependList, parameterQueryList, parameterOptionList);
		questionAdmin.createQuestionXMLFile(xmlFile, baseFolder);
  		
  		return Response.status(200).entity(getSO("Success")).build();
  	}
 
  	@POST
	@Path("edit")
	@Produces("application/json")
  	public Response editInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request){
		Gson gson = new Gson();
		String selectedEngine = form.getFirst("engine");
		String perspective = form.getFirst("perspective");
		String questionKey = form.getFirst("questionKey");
		String question = form.getFirst("question");
		String sparql = form.getFirst("sparql");
		String layout = form.getFirst("layout");
		String questionDescription = form.getFirst("questionDescription");

		Vector<String> parameterDependList = gson.fromJson(form.getFirst("parameterDependList"), Vector.class);
		Vector<String> parameterQueryList = gson.fromJson(form.getFirst("parameterQueryList"), Vector.class);
		Vector<String> parameterOptionList = gson.fromJson(form.getFirst("parameterOptionList"), Vector.class);
		ArrayList<String> questionList = gson.fromJson(form.getFirst("questionList"), ArrayList.class);
		//boolean existingPerspective = form.getFirst("existingPerspective").equals("true");
		//boolean existingAutoGenQuestionKey = form.getFirst("existingAutoGenQuestionKey").equals("true");
		
		String currentPerspective = gson.fromJson(form.getFirst("currentPerspective"), String.class);
		String currentQuestionKey = form.getFirst("questionKey");
		String currentQuestion = form.getFirst("currentQuestion");
		String currentSparql = form.getFirst("currentSparql");
		String currentLayout = form.getFirst("currentLayout");
		String currentQuestionDescription = form.getFirst("currentQuestionDescription");
		Vector<String> currentParameterDependList = gson.fromJson(form.getFirst("currentParameterDependList"), Vector.class);
		Vector<String> currentParameterQueryList = gson.fromJson(form.getFirst("currentParameterQueryList"), Vector.class);
		Vector<String> currentParameterOptionList = gson.fromJson(form.getFirst("currentParameterOptionList"), Vector.class);
		String currentNumberofQuestions = form.getFirst("currentNumberOfQuestions");
		
		String selectedPerspective = form.getFirst("selectedPerspective");
		
		//store the current info for each question that is being selected; for deleting the selected
		//question then adding the new modified question
		QuestionAdministrator.currentPerspective = currentPerspective;
		QuestionAdministrator.currentQuestion = currentQuestion;
		QuestionAdministrator.currentQuestionKey = currentQuestionKey;
		QuestionAdministrator.currentLayout = currentLayout;
		QuestionAdministrator.currentQuestionDescription = currentQuestionDescription;
		QuestionAdministrator.currentSparql = currentSparql;
		QuestionAdministrator.currentParameterDependListVector = currentParameterDependList;
		QuestionAdministrator.currentParameterQueryListVector = currentParameterQueryList;
		QuestionAdministrator.currentParameterOptionListVector = currentParameterOptionList;
		QuestionAdministrator.currentNumberofQuestions = currentNumberofQuestions;
		
		String xmlFile = "db/" + selectedEngine + "/" + selectedEngine
				+ "_Questions.XML";
		String baseFolder = DIHelper.getInstance().getProperty("BaseFolder");
		
		QuestionAdministrator.selectedEngine = selectedEngine;
		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine, questionList, selectedPerspective, "Edit Question");

		questionAdmin.modifyQuestion(perspective, questionKey, question, sparql, layout, questionDescription, parameterDependList, parameterQueryList, parameterOptionList);
		questionAdmin.createQuestionXMLFile(xmlFile, baseFolder);
		
  		return Response.status(200).entity(getSO("Success")).build();
  	}
  	
  	@POST
	@Path("delete")
	@Produces("application/json")
  	public Response deleteInsight(MultivaluedMap<String, String> form, @Context HttpServletRequest request){
		Gson gson = new Gson();
		String selectedEngine = form.getFirst("engine");
		String perspective = form.getFirst("perspective");
		String questionKey = form.getFirst("questionKey");
		String question = form.getFirst("question");
		String sparql = form.getFirst("sparql");
		String layout = form.getFirst("layout");
		String questionDescription = form.getFirst("questionDescription");

		Vector<String> parameterDependList = gson.fromJson(form.getFirst("parameterDependList"), Vector.class);
		Vector<String> parameterQueryList = gson.fromJson(form.getFirst("parameterQueryList"), Vector.class);
		Vector<String> parameterOptionList = gson.fromJson(form.getFirst("parameterOptionList"), Vector.class);
		ArrayList<String> questionList = gson.fromJson(form.getFirst("questionList"), ArrayList.class);
		//boolean existingPerspective = form.getFirst("existingPerspective").equals("true");
		//boolean existingAutoGenQuestionKey = form.getFirst("existingAutoGenQuestionKey").equals("true");
		String selectedPerspective = form.getFirst("selectedPerspective");

		QuestionAdministrator.selectedEngine = selectedEngine;

		QuestionAdministrator questionAdmin = new QuestionAdministrator(this.coreEngine, questionList, selectedPerspective, "Delete Question");
		//questionAdmin.existingAutoGenQuestionKey = existingAutoGenQuestionKey;
		//questionAdmin.existingPerspective = existingPerspective;
		questionAdmin.questionList = questionList;
		
		String xmlFile = "db/" + selectedEngine + "/" + selectedEngine
				+ "_Questions.XML";
		String baseFolder = DIHelper.getInstance().getProperty("BaseFolder");
		
		//questionKey = questionAdmin.createQuestionKey(perspective);
		questionAdmin.deleteQuestion(perspective, questionKey, question, sparql, layout, questionDescription, parameterDependList, parameterQueryList, parameterOptionList);
		questionAdmin.createQuestionXMLFile(xmlFile, baseFolder);
		
  		return Response.status(200).entity(getSO("Success")).build();
  	}
	
	private StreamingOutput getSO(Object vec)
	{
		if(vec != null)
		{
			Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
			output = gson.toJson(vec);
			   return new StreamingOutput() {
			         public void write(OutputStream outputStream) throws IOException, WebApplicationException {
			            PrintStream ps = new PrintStream(outputStream);
			            ps.println(output);
			         }};		
		}
		return null;
	}
}
