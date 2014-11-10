package prerna.semoss.web.services;

import java.util.Hashtable;
import java.util.List;

import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

import prerna.rdf.engine.api.IEngine;
import prerna.ui.components.playsheets.AnalyticsBasePlaySheet;

public class EngineAnalyticsResource {

	private IEngine engine;
	
	public EngineAnalyticsResource(IEngine engine) {
		this.engine = engine;
	}
	
	@Path("scatter")
	public Hashtable<String, Object> generateScatter() {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return ps.generateScatter(engine);		
	}
	
	@Path("genericQuestions")
	public List<Hashtable<String, String>> getQuestionsWithoutParams() {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return ps.getQuestionsWithoutParams(engine);		
	}

	@Path("influentialInstances")
	public List<Hashtable<String, String>> getMostInfluentialInstances(@QueryParam("typeURI") String typeURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		if(typeURI == null) {
			return ps.getMostInfluentialInstancesForAllTypes(engine);		
		} else {
			return ps.getMostInfluentialInstancesForSpecificTypes(engine, typeURI);		
		}
	}
	
	@Path("outliers")
	public List<Hashtable<String, Object>> getLargestOutliers(@QueryParam("typeURI") String typeURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return ps.getLargestOutliers(engine, typeURI);
	}
	
	@Path("connectionMap")
	public Hashtable<String, List<Hashtable<String, Object>>> getConnectionMap(@QueryParam("instanceURI") String instanceURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return ps.getConnectionMap(engine, instanceURI);
	}
	
	@Path("properties")
	public List<Hashtable<String, String>> getPropertiesForInstance(@QueryParam("instanceURI") String instanceURI) {
		AnalyticsBasePlaySheet ps = new AnalyticsBasePlaySheet();
		return ps.getPropertiesForInstance(engine, instanceURI);
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
