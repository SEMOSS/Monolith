package prerna.semoss.web.services;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Vector;

import prerna.rdf.engine.api.IEngine;
import prerna.rdf.engine.impl.AbstractEngine;
import prerna.rdf.engine.impl.RDFFileSesameEngine;
import prerna.rdf.engine.impl.SesameJenaSelectStatement;
import prerna.rdf.engine.impl.SesameJenaSelectWrapper;
import prerna.util.Utility;

public class EngineAnalyticsResource {

	public EngineAnalyticsResource() {
		
	}
	
	public Hashtable<String, Object> generateScatter(IEngine engine) {
		final String getConceptListQuery = "SELECT DISTINCT ?entity WHERE { {?entity <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://semoss.org/ontologies/Concept>} }";
		final String getConceptsAndInstanceCountsQuery = "SELECT DISTINCT ?entity (COUNT(DISTINCT ?instance) AS ?count) WHERE { {?entity <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://semoss.org/ontologies/Concept>} {?instance <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?entity} } GROUP BY ?entity";
		final String getConceptsAndPropCountsQuery = "SELECT DISTINCT ?nodeType (COUNT(DISTINCT ?entity) AS ?entityCount) WHERE { {?nodeType <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://semoss.org/ontologies/Concept>} {?source <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?nodeType} {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Relation/Contains>} {?source ?entity ?prop } } GROUP BY ?nodeType";
		final String getConceptEdgesCountQuery = "SELECT DISTINCT ?entity ( COUNT(DISTINCT ?inRel) + COUNT(DISTINCT ?outRel) AS ?edgeCount) WHERE { {?entity <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://semoss.org/ontologies/Concept>} {?outRel <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation>} OPTIONAL{?entity ?outRel ?node1} {?inRel <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation>} OPTIONAL{?node2 ?inRel ?entity} } GROUP BY ?entity";
		final String getConceptInsightCountQuery = "SELECT DISTINCT ?entity (COUNT(DISTINCT ?insight) WHERE { BIND(<@ENGINE_NAME@> AS ?engine) {?engineInsight <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Engine:Insight>} {?engine ?engineInsight ?insight} {?insight <PARAM:TYPE> ?entity} } @ENTITY_BINDINGS@";
		
		Vector<String> conceptList = engine.getEntityOfType(getConceptListQuery);
		Hashtable<String, Hashtable<String, Object>> allData = constructDataHash(conceptList);

		allData = addToAllData(engine, getConceptsAndInstanceCountsQuery, "x", allData);
		allData = addToAllData(engine, getConceptsAndPropCountsQuery, "z", allData);
		
		RDFFileSesameEngine baseDataEngine = ((AbstractEngine)engine).getBaseDataEngine();
		allData = addToAllData(baseDataEngine, getConceptEdgesCountQuery, "y", allData);

		String engineName = engine.getEngineName();
		String specificInsightQuery = getConceptInsightCountQuery.replace("@ENGINE_NAME@", "http://semoss.org/ontologies/Concept/Engine/".concat(engineName));
		String bindings = "BINDINGS { ";
		for(String concept : conceptList) {
			bindings.concat("<").concat(concept).concat(">").concat(" ");
		}
		bindings.concat("}");
		specificInsightQuery = specificInsightQuery.replace("@ENTITY_BINDINGS@", bindings);
		RDFFileSesameEngine insightEngine = ((AbstractEngine)engine).getInsightBaseXML();
		allData = addToAllData(insightEngine, specificInsightQuery, "heat", allData);

		Hashtable<String, Object> allHash = new Hashtable<String, Object>();
		allHash.put("dataSeries", allData.values());
		allHash.put("title", "Exploring Data Types in ".concat(engineName));
		allHash.put("xAxisTitle", "Number of Instances");
		allHash.put("yAxisTitle", "Number of Edges");
		allHash.put("zAxisTitle", "Number of Properties");
		allHash.put("heatTitle", "Number of Insights");
		
		return allHash;
	}
	
	private Hashtable<String, Hashtable<String, Object>> constructDataHash(Vector<String> conceptList) {
		Hashtable<String, Hashtable<String, Object>> allData = new Hashtable<String, Hashtable<String, Object>>();
		int length = conceptList.size();
		int i = 0;
		for(;i < length; i++) {
			Hashtable<String, Object> elementHash = new Hashtable<String, Object>();
			elementHash.put("series", "Concepts");
			elementHash.put("label", conceptList.get(i));
			allData.put(conceptList.get(i), elementHash);
		}
		
		return allData;
	}
	
	private Hashtable<String, Hashtable<String, Object>> addToAllData(IEngine engine, String query, String key, Hashtable<String, Hashtable<String, Object>> allData) {
		SesameJenaSelectWrapper sjsw = Utility.processQuery(engine, query);
		String[] names = sjsw.getVariables();
		String param1 = names[0];
		String param2 = names[1];
		while(sjsw.hasNext()) {
			SesameJenaSelectStatement sjss = sjsw.next();
			String concept = sjss.getRawVar(param1).toString();
			Object val = sjss.getVar(param2);
			
			Hashtable<String, Object> elementData = allData.get(concept);
			elementData.put(key, val);
		}
		
		return allData;
	}
	
	public Hashtable<String, Object> getQuestionsWithoutParams(IEngine engine, String engineName, boolean isEngineMaster) {
		final String getInsightsWithoutParamsQuery = "SELECT DISTINCT ?questionDescription WHERE { BIND(@ENGINE_NAME@ AS ?engine) {?engineInsight <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Engine:Insight>} {?engine ?engineInsight ?insight} {?insight <http://semoss.org/ontologies/Relation/Contains/Description> ?questionDescription} MINUS{?insight <PARAM:TYPE> ?entity} }";
		final String getInsightsWithoutParamsFromMasterDBQuery = "SELECT DISTINCT ?questionDescription ?timesClicked WHERE { BIND(@ENGINE_NAME@ AS ?engine) {?engineInsight <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Engine:Insight>} {?engine ?engineInsight ?insight} {?insight <http://semoss.org/ontologies/Relation/Contains/Description> ?questionDescription} {?insight <http://semoss.org/ontologies/Relation/PartOf> ?userInsight} {?userInsight <http://semoss.org/ontologies/Relation/Contains/TimesClicked> ?timesClicked} MINUS{?insight <PARAM:TYPE> ?entity} } ORDER BY ?timesClicked";
				
		List<Object[]> questionSet = new ArrayList<Object[]>();
		
		RDFFileSesameEngine insightEngine = ((AbstractEngine)engine).getInsightBaseXML();
		String query = "";
		if(isEngineMaster) {
			query = getInsightsWithoutParamsQuery.replace("@ENGINE_NAME@", "http://semoss.org/ontologies/Concept/Engine/".concat(engineName));
		} else {
			query = getInsightsWithoutParamsFromMasterDBQuery.replace("@ENGINE_NAME@", "http://semoss.org/ontologies/Concept/Engine/".concat(engineName));
		}
		
		SesameJenaSelectWrapper sjsw = Utility.processQuery(insightEngine, query);
		String[] names = sjsw.getVariables();
		String param1 = names[0];
		while(sjsw.hasNext()) {
			SesameJenaSelectStatement sjss = sjsw.next();
			String[] question = new String[]{sjss.getVar(param1).toString()};
			questionSet.add(question);
		}
		
		Hashtable<String, Object> retHash = new Hashtable<String, Object>();
		retHash.put("data", questionSet);
		retHash.put("headers", new String[]{"Questions"});
		
		return retHash;
	}
	
	public Hashtable<String, Object> getQuestionsForParam(IEngine engine, String typeURI) {
		final String getInsightsWithParamsQuery = "SELECT DISTINCT ?questionDescription WHERE { BIND(<@ENTITY_TYPE@> AS ?entity) BIND(@ENGINE_NAME@ AS ?engine) {?engineInsight <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Engine:Insight>} {?engine ?engineInsight ?insight} {?insight <http://semoss.org/ontologies/Relation/Contains/Description> ?questionDescription} {?insight <PARAM:TYPE> ?entity} }";
		
		List<Object[]> questionSet = new ArrayList<Object[]>();
		
		RDFFileSesameEngine insightEngine = ((AbstractEngine)engine).getInsightBaseXML();
		String query = getInsightsWithParamsQuery.replace("@ENGINE_NAME@", "http://semoss.org/ontologies/Concept/Engine/".concat(engine.getEngineName()));		
		query = getInsightsWithParamsQuery.replace("@ENTITY_TYPE@", typeURI);
				
		SesameJenaSelectWrapper sjsw = Utility.processQuery(insightEngine, query);
		String[] names = sjsw.getVariables();
		String param1 = names[0];
		while(sjsw.hasNext()) {
			SesameJenaSelectStatement sjss = sjsw.next();
			String[] question = new String[]{sjss.getVar(param1).toString()};
			questionSet.add(question);
		}
		
		Hashtable<String, Object> retHash = new Hashtable<String, Object>();
		retHash.put("data", questionSet);
		retHash.put("headers", new String[]{"Questions"});
		
		return retHash;
	}
	
	public Hashtable<String, Object> getMostInfluentialInstancesForAllTypes(IEngine engine) {
		final String getMostConncectedInstancesQuery = "SELECT DISTINCT ?entity ?instance (COUNT(?inRel) + COUNT(?outRel) AS ?edgeCount) WHERE { { FILTER (STR(?entity)!='http://semoss.org/ontologies/Concept') {?entity <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://semoss.org/ontologies/Concept>} {?instance <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?entity} {?instance <http://www.w3.org/2000/01/rdf-schema#label> ?instanceLabel2} {?node2 <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept>} {?inRel <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation>} {?inRel <http://www.w3.org/2000/01/rdf-schema#label> ?relLabel2} {?node2 ?inRel ?instance} } UNION { FILTER (STR(?entity)!='http://semoss.org/ontologies/Concept') {?entity <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://semoss.org/ontologies/Concept>} {?instance <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ?entity} {?instance <http://www.w3.org/2000/01/rdf-schema#label> ?instanceLabel1} {?node1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept>} {?outRel <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation>} {?outRel <http://www.w3.org/2000/01/rdf-schema#label> ?relLabel1} {?instance ?outRel ?node1} } } GROUP BY ?entity ?instance";
		return mostConnectedInstancesProcessing(engine, getMostConncectedInstancesQuery);
	}
	
	public Hashtable<String, Object> getMostInfluentialInstancesForSpecificTypes(IEngine engine, String typeURI) {
		final String getMostConnectedInstancesWithType = "SELECT DISTINCT ?entity (COUNT(?inRel) + COUNT(?outRel) AS ?edgeCount) WHERE { { {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@NODE_URI@>} {?node2 <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept>} {?inRel <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation>} {?inRel <http://www.w3.org/2000/01/rdf-schema#label> ?label2} {?node2 ?inRel ?entity} } UNION { {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <@NODE_URI@>} {?node1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept>} {?outRel <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation>} {?outRel <http://www.w3.org/2000/01/rdf-schema#label> ?label1} {?entity ?outRel ?node1} } } GROUP BY ?entity ";
		String query = getMostConnectedInstancesWithType.replaceAll("@NODE_URI@", typeURI);
		return mostConnectedInstancesProcessing(engine, query);
	}

	private Hashtable<String, Object> mostConnectedInstancesProcessing(IEngine engine, String query) {
		List<Object[]> instanceList = new ArrayList<Object[]>();
		SesameJenaSelectWrapper sjsw = Utility.processQuery(engine, query);
		String[] names = sjsw.getVariables();
		String param1 = names[0];
		String param2 = names[1];
		String param3 = names[2];
		while(sjsw.hasNext()) {
			SesameJenaSelectStatement sjss = sjsw.next();
			String[] instanceInfo = new String[]{sjss.getVar(param1).toString(), sjss.getVar(param2).toString(), sjss.getVar(param3).toString()};
			instanceList.add(instanceInfo);
		}
		
		Hashtable<String, Object> retHash = new Hashtable<String, Object>();
		retHash.put("data", instanceList);
		retHash.put("headers", new String[]{"Node Type", "Instance", "# of Edges"});
		
		return retHash;
	}
	
//	public Hashtable<String, Object> getLargestOutliers(IEngine engine, String typeURI) {
//		
//	}
	
	
	
	public Hashtable<String, Object> getPropertiesForInstance(IEngine engine, String instanceURI) {
		final String getPropertiesForInstance = "SELECT DISTINCT ?entity ?prop WHERE { BIND(<@INSTANCE_URI@> AS ?source) {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Relation/Contains>} {?source ?entity ?prop } } ORDER BY ?entity";

		List<Object[]> propList = new ArrayList<Object[]>();

		String query = getPropertiesForInstance.replace("@INSTANCE_URI@", instanceURI);
		
		SesameJenaSelectWrapper sjsw = Utility.processQuery(engine, query);
		String[] names = sjsw.getVariables();
		String param1 = names[0];
		String param2 = names[1];
		while(sjsw.hasNext()) {
			SesameJenaSelectStatement sjss = sjsw.next();
			String[] instanceInfo = new String[]{sjss.getVar(param1).toString(), sjss.getVar(param2).toString()};
			propList.add(instanceInfo);
		}
		
		Hashtable<String, Object> retHash = new Hashtable<String, Object>();
		retHash.put("data", propList);
		retHash.put("headers", new String[]{"Property", "Value"});
		
		return retHash;
	}
	
}
