package prerna.semoss.web.services;

import java.util.Hashtable;
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
		final String getConceptInsightCountQuery = "SELECT DISTINCT ?entity (COUNT(DISTINCT ?insight) WHERE { BIND(@ENGINE_NAME@ AS ?engine) {?engineInsight <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation/Engine:Insight>} {?engine ?engineInsight ?insight} {?insight <PARAM:TYPE> ?entity} } @ENTITY_BINDINGS@";
		
		Vector<String> conceptList = engine.getEntityOfType(getConceptListQuery);
		Hashtable<String, Hashtable<String, Object>> allData = constructDataHash(conceptList);

		allData = addToAllData(engine, getConceptsAndInstanceCountsQuery, "x", allData);
		allData = addToAllData(engine, getConceptsAndPropCountsQuery, "z", allData);
		
		RDFFileSesameEngine baseDataEngine = ((AbstractEngine)engine).getBaseDataEngine();
		allData = addToAllData(baseDataEngine, getConceptEdgesCountQuery, "y", allData);

		String engineName = engine.getEngineName();
		String specificInsightQuery = getConceptInsightCountQuery.replace("@ENGINE_NAME@", engineName);
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
		while(sjsw.hasNext()) {
			SesameJenaSelectStatement sjss = sjsw.next();
			String concept = sjss.getRawVar(names[0]).toString();
			Object val = sjss.getVar(names[1]);
			
			Hashtable<String, Object> elementData = allData.get(concept);
			elementData.put(key, val);
		}
		
		return allData;
	}
	
}
