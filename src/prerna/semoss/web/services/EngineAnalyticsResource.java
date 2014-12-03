package prerna.semoss.web.services;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Hashtable;
import java.util.List;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.rdf.engine.api.IEngine;
import prerna.ui.components.playsheets.AnalyticsBasePlaySheet;
import prerna.util.Utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class EngineAnalyticsResource {

	private static final Logger LOGGER = LogManager.getLogger(EngineAnalyticsResource.class.getName());
	
	private IEngine engine;
	String output = "";

	public EngineAnalyticsResource(IEngine engine) {
		this.engine = engine;
	}
	
	@POST
	@Path("/scatter")
	public Response generateScatter() {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		LOGGER.info("Creating scatterplot for " + engine.getEngineName() + "'s base page...");
		return Response.status(200).entity(getSO(ps.generateScatter(engine))).build();		
	}
	
	@POST
	@Path("/questions")
	public Response getQuestions(@QueryParam("typeURI") String typeURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		if(typeURI == null) {
			LOGGER.info("Creating generic question list...");
			List<Hashtable<String, String>> questionList = ps.getQuestionsWithoutParams(engine);
			if(questionList.isEmpty()) {
				String errorMessage = "No insights exist that do not contain a paramter input.";
				return Response.status(400).entity(getSO(errorMessage)).build();
			} else {
				return Response.status(200).entity(getSO(questionList)).build();
			}
		} else {
			LOGGER.info("Creating question list with parameter of type " + typeURI + "...");
			List<Hashtable<String, String>> questionList = ps.getQuestionsForParam(engine, typeURI);
			if(questionList.isEmpty()) {
				String errorMessage = "No insights exist that contain " + Utility.getInstanceName(typeURI) + " as a paramter input.";
				return Response.status(400).entity(getSO(errorMessage)).build();
			} else {
				return Response.status(200).entity(getSO(questionList)).build();
			}
		}
	}
	
	@POST
	@Path("/influentialInstances")
	public Response getMostInfluentialInstances(@QueryParam("typeURI") String typeURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		if(typeURI == null) {
			LOGGER.info("Creating list of instances with most edge connections across all concepts...");
			return Response.status(200).entity(getSO(ps.getMostInfluentialInstancesForAllTypes(engine))).build();		
		} else {
			LOGGER.info("Creating list of instances with most edge connections of type " + typeURI + "...");
			return Response.status(200).entity(getSO(ps.getMostInfluentialInstancesForSpecificTypes(engine, typeURI))).build();		
		}
	}
	
	@POST
	@Path("/outliers")
	public Response getLargestOutliers(@QueryParam("typeURI") String typeURI) {
		if(typeURI == null) {
			String errorMessage = "No typeURI provided";
			return Response.status(400).entity(getSO(errorMessage)).build();
		}
		
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		LOGGER.info("Running outlier algorithm for instances of type " + typeURI + "...");
		List<Hashtable<String, Object>> results = ps.getLargestOutliers(engine, typeURI); 
		
		if(results == null) {
			String errorMessage = "No properties or edge connections to determine outliers among concepts of type ".concat(Utility.getInstanceName(typeURI));
			return Response.status(400).entity(getSO(errorMessage)).build();
		}
		if(results.isEmpty()) {
			String errorMessage = "Insufficient sample size of instances of type ".concat(Utility.getInstanceName(typeURI).concat(" to determine outliers"));
			return Response.status(400).entity(getSO(errorMessage)).build();
		}
		return Response.status(200).entity(getSO(results)).build();
	}
	
	@POST
	@Path("/connectionMap")
	public Response getConnectionMap(@QueryParam("instanceURI") String instanceURI) {
		if(instanceURI == null) {
			String errorMessage = "No instanceURI provided";
			return Response.status(400).entity(getSO(errorMessage)).build();
		}
		
		LOGGER.info("Creating instance mapping to concepts...");
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return Response.status(200).entity(getSO(ps.getConnectionMap(engine, instanceURI))).build();
	}
	
	@POST
	@Path("/properties")
	public Response getPropertiesForInstance(@QueryParam("instanceURI") String instanceURI) {
		if(instanceURI == null) {
			String errorMessage = "No instanceURI provided";
			return Response.status(400).entity(getSO(errorMessage)).build();
		}
		
		LOGGER.info("Creating list of properties for " + instanceURI + "...");
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return Response.status(200).entity(getSO(ps.getPropertiesForInstance(engine, instanceURI))).build();
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
	
	//TODO: getting questions from master db is web specific and should not be semoss playsheet
//	public Hashtable<String, Object> getQuestionsWithoutParamsFromMasterDB(IEngine engine, String engineName, boolean isEngineMaster) {
//		final String getInsightsWithoutParamsFromMasterDBQuery = "SELECT DISTINCT ?questionDescription ?timesClicked WHERE { BIND(@ENGINE_NAME@ AS ?engine) {?engineInsight <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Engine:Insight>} {?engine ?engineInsight ?insight} {?insight <http://semoss.org/ontologies/Relation/Contains/Description> ?questionDescription} {?insight <http://semoss.org/ontologies/Relation/PartOf> ?userInsight} {?userInsight <http://semoss.org/ontologies/Relation/Contains/TimesClicked> ?timesClicked} MINUS{?insight <PARAM:TYPE> ?entity} } ORDER BY ?timesClicked";
//				
//		List<Object[]> questionSet = new ArrayList<Object[]>();
//		
//		RDFFileSesameEngine insightEngine = ((AbstractEngine)engine).getInsightBaseXML();
//		String query = getInsightsWithoutParamsFromMasterDBQuery.replace("@ENGINE_NAME@", "http://semoss.org/ontologies/Concept/Engine/".concat(engineName));
//		
//		SesameJenaSelectWrapper sjsw = Utility.processQuery(insightEngine, query);
//		String[] names = sjsw.getVariables();
//		String param1 = names[0];
//		while(sjsw.hasNext()) {
//			SesameJenaSelectStatement sjss = sjsw.next();
//			String[] question = new String[]{sjss.getVar(param1).toString()};
//			questionSet.add(question);
//		}
//		
//		Hashtable<String, Object> retHash = new Hashtable<String, Object>();
//		retHash.put("data", questionSet);
//		retHash.put("headers", new String[]{"Questions"});
//		
//		return retHash;
//	}
	
}
