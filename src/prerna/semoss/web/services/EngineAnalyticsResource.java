package prerna.semoss.web.services;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import prerna.rdf.engine.api.IEngine;
import prerna.ui.components.playsheets.AnalyticsBasePlaySheet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class EngineAnalyticsResource {

	private IEngine engine;
	String output = "";

	public EngineAnalyticsResource(IEngine engine) {
		this.engine = engine;
	}
	
	@POST
	@Path("/scatter")
	public Response generateScatter() {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return Response.status(200).entity(getSO(ps.generateScatter(engine))).build();		
	}
	
	@POST
	@Path("/genericQuestions")
	public Response getQuestionsWithoutParams() {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return Response.status(200).entity(getSO(ps.getQuestionsWithoutParams(engine))).build();		
	}

	@POST
	@Path("/influentialInstances")
	public Response getMostInfluentialInstances(@QueryParam("typeURI") String typeURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		if(typeURI == null) {
			return Response.status(200).entity(getSO(ps.getMostInfluentialInstancesForAllTypes(engine))).build();		
		} else {
			return Response.status(200).entity(getSO(ps.getMostInfluentialInstancesForSpecificTypes(engine, typeURI))).build();		
		}
	}
	
	@POST
	@Path("/outliers")
	public Response getLargestOutliers(@QueryParam("typeURI") String typeURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return Response.status(200).entity(getSO(ps.getLargestOutliers(engine, typeURI))).build();
	}
	
	@POST
	@Path("/connectionMap")
	public Response getConnectionMap(@QueryParam("instanceURI") String instanceURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return Response.status(200).entity(getSO(ps.getConnectionMap(engine, instanceURI))).build();
	}
	
	@POST
	@Path("/properties")
	public Response getPropertiesForInstance(@QueryParam("instanceURI") String instanceURI) {
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
