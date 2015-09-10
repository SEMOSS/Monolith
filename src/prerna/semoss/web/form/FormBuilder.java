package prerna.semoss.web.form;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.ws.rs.core.MultivaluedMap;

import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.RDFS;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.engine.api.IEngine;
import prerna.engine.api.ISelectWrapper;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.rdf.RDFFileSesameEngine;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;

public final class FormBuilder {

	private FormBuilder() {
	}
	
	/**
	 * 
	 * @param formData the stringified JSON of the form data to save
	 * @param jsonLoc the file name and path
	 * @return
	 * @throws IOException
	 * 
	 * 
	 */
	public static void saveForm(String formData, String jsonLoc) throws IOException {
		
		//throw an error if a file of the same name exists
		if(Files.exists(Paths.get(jsonLoc))) {
			throw new IOException("File already exists");
		}
		
		//write the formData json to a file
		FileWriter file = null;
		try {
			file = new FileWriter(jsonLoc);
			file.write(formData);
		} catch (IOException e) {
			e.printStackTrace();
			throw new IOException("Error Writing JSON Data");
		} finally {
			try {
				if(file != null) {
					file.flush();
					file.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
				throw new IOException("Error Writing JSON Data");
			}
		}
	}
	
	/**
	 * 
	 * @param basePath
	 * @param jsonLoc
	 * @return
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	public static String getForm(String jsonLoc) throws FileNotFoundException, IOException {

		//file does not exist
		if(!Files.exists(Paths.get(jsonLoc))) {
			throw new FileNotFoundException("Form not found");
		}

		//get the file and convert to a string
		StringBuilder stringBuilder = new StringBuilder();
		FileReader fReader = null;
		BufferedReader reader = null;
		try {
			fReader = new FileReader(jsonLoc);
			reader = new BufferedReader(fReader);
			String line = null;
			String ls = System.getProperty("line.separator");
			while( ( line = reader.readLine() ) != null ) {
				stringBuilder.append( line );
				stringBuilder.append( ls );
			}
		} catch (IOException e) {
			e.printStackTrace();
			throw new IOException();
		} finally {
			if(fReader != null) {
				try {
					fReader.close();
				} catch (IOException e) {
					throw new IOException();
				}
			}
			if(reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					throw new IOException();
				}
			}
		}
		
		//return stringified json of form
		return stringBuilder.toString();
	}

	/**
	 * 
	 * @param form
	 */
	public static void saveFormData(MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		String formData = form.getFirst("formData");
		List<HashMap<String, Object>> Engines = gson.fromJson(formData, new TypeToken<List<HashMap<String, Object>>>() {}.getType());
		
		Properties p = DIHelper.getInstance().getRdfMap();
		String semossBaseURI = "http://semoss.org/ontologies";

		for(HashMap<String, Object> engineHash : Engines) {
			String engineName = engineHash.get("engine").toString();
			IEngine engine = (IEngine)DIHelper.getInstance().getLocalProp(engineName);
			
			String baseURI = semossBaseURI;
			if(engineHash.containsKey("baseURI")) {
				baseURI = engineHash.get("baseURI").toString();
			}
			
			String relationBaseURI = semossBaseURI + "/" + Constants.DEFAULT_RELATION_CLASS;
			String conceptBaseURI = semossBaseURI + "/" + Constants.DEFAULT_NODE_CLASS;
			String propertyBaseURI = semossBaseURI + "/" + Constants.DEFAULT_PROPERTY_CLASS;

			List<HashMap<String, Object>> nodes = (List<HashMap<String, Object>>) engineHash.get("nodes"); 
			List<HashMap<String, Object>> relationships = new ArrayList<HashMap<String, Object>>();
			
			if(engineHash.containsKey("relationships")) {
				relationships = (List<HashMap<String, Object>>)engineHash.get("relationships");
			}
			
			if(engine.getEngineType() == IEngine.ENGINE_TYPE.JENA || engine.getEngineType() == IEngine.ENGINE_TYPE.SESAME) {
				saveRDFFormData(engine, baseURI, relationBaseURI, conceptBaseURI, propertyBaseURI, nodes, relationships);
			} else if(engine.getEngineType() == IEngine.ENGINE_TYPE.RDBMS) {
				saveRDBMSFormData(engine, baseURI, relationBaseURI, conceptBaseURI, propertyBaseURI, nodes, relationships);
			} else {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Engine type not found!");
			}
			
			//commit information to db
			engine.commit();
		}
	}
	
	/**
	 * 
	 * @param engine
	 * @param baseURI
	 * @param relationBaseURI
	 * @param conceptBaseURI
	 * @param propertyBaseURI
	 * @param nodes
	 * @param relationships
	 * 
	 * Save data from the form to a RDF Database
	 */
	private static void saveRDFFormData(IEngine engine, String baseURI, String relationBaseURI, String conceptBaseURI, String propertyBaseURI, List<HashMap<String, Object>> nodes, List<HashMap<String, Object>> relationships) {
		String nodeType;
		String nodeValue;
		String instanceConceptURI;
		String propertyValue;
		String propertyType;
		String propertyURI;

		Gson gson = new Gson();
		
		Map<String, String> nodeMapping = new HashMap<String, String>();

		//Save nodes and properties of nodes
		for(int i = 0; i < nodes.size(); i++) {
			Map<String, Object> node = nodes.get(i);
			nodeType = node.get("conceptName").toString();
			nodeValue = node.get("conceptValue").toString();
			nodeMapping.put(nodeType, nodeValue);

			instanceConceptURI = baseURI + "/" + nodeValue;
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, RDF.TYPE, conceptBaseURI + "/" + nodeType, true});
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, RDFS.LABEL, nodeValue, false});
			
			
			if(node.containsKey("properties")) {
				List<HashMap<String, Object>> properties = (List<HashMap<String, Object>>)node.get("properties");
	
				for(int j = 0; j < properties.size(); j++) {
					Map<String, Object> property = properties.get(j);
					propertyValue = property.get("propertyValue").toString();
					propertyType = property.get("propertyName").toString();
					
					propertyURI = propertyBaseURI + "/" + propertyType;
					engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, propertyURI, propertyValue, false});
					engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{propertyURI, RDF.TYPE, propertyBaseURI, true});
				}
			}
		}

		String startNode;
		String endNode;
		String relType;
		String subject;
		String instanceSubjectURI;
		String object;
		String instanceObjectURI;
		String baseRelationshipURI;
		String instanceRel;
		String instanceRelationshipURI;
		
		//Save the relationships
		for(int i = 0; i < relationships.size(); i++) {
			Map<String, Object> relationship = relationships.get(i);
			startNode = relationship.get("startNode").toString();
			endNode = relationship.get("endNode").toString();
			subject = nodeMapping.get(startNode);
			object = nodeMapping.get(endNode);
			instanceSubjectURI = baseURI + "/" + subject;
			instanceObjectURI = baseURI + "/" + object;
			
			relType = relationship.get("relType").toString();
			baseRelationshipURI = relationBaseURI + "/" + relType;
			instanceRel = subject + ":" + object;
			instanceRelationshipURI = baseRelationshipURI + "/" + instanceRel;
			
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, baseRelationshipURI, instanceObjectURI, true});
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, instanceRelationshipURI, instanceObjectURI, true});
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDFS.SUBPROPERTYOF, baseRelationshipURI, true});
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDFS.LABEL, instanceRel, false});
		}
	}
	
	/**
	 * 
	 * @param engine
	 * @param baseURI
	 * @param relationURI
	 * @param conceptBaseURI
	 * @param propertyBaseURI
	 * @param nodes
	 * @param relationships
	 * 
	 * Save form data to a RDBMS database
	 */
	private static void saveRDBMSFormData(IEngine engine, String baseURI, String relationURI, String conceptBaseURI, String propertyBaseURI, List<HashMap<String, Object>> nodes, List<HashMap<String, Object>> relationships) {
		String tableName;
		String tableColumn;
		String tableValue;
		Map<String, Map<String, String>> nodeMapping = new HashMap<String, Map<String, String>>();

		for(int j = 0; j < nodes.size(); j++) {
			Map<String, Object> node = nodes.get(j);
			
			tableName = node.get("conceptName").toString();
			tableColumn = node.get("conceptColumn").toString();
			tableValue = node.get("conceptValue").toString();
			
			HashMap<String, String> innerMap = new HashMap<String, String>();
			innerMap.put(tableColumn, tableValue);
			nodeMapping.put(tableName, innerMap);
			
			Map<String, Object> templateOptions = (Map<String, Object>)node.get("templateOptions");
			List<HashMap<String, Object>> properties = (List<HashMap<String, Object>>)templateOptions.get("properties");
			
			List<String> propTypes = new ArrayList<String>();
			List<Object> propValues = new ArrayList<Object>();
			for(int k = 0; k < properties.size(); k++) {
				Map<String, Object> property = properties.get(k);
				propTypes.add(property.get("propertyName").toString());
				propValues.add(property.get("propertyValue"));
			}
			
			StringBuilder insertQuery = new StringBuilder();
			insertQuery.append("INSERT INTO ");
			insertQuery.append(tableName.toUpperCase());
			insertQuery.append(" (");
			insertQuery.append(tableColumn);
			for(int i = 0; i < propTypes.size(); i++) {
				insertQuery.append(propTypes.get(i).toUpperCase());
				if(i != propTypes.size() - 1) {
					insertQuery.append(",");
				}
			}
			insertQuery.append(") VALUES (");
			insertQuery.append(tableValue);
			for(int i = 0; i < propValues.size(); i++) {
				Object propertyValue = propValues.get(i);
				if(propertyValue instanceof String) {
					insertQuery.append("'");
					insertQuery.append(propertyValue.toString().toUpperCase());
					insertQuery.append("'");
				} else {
					insertQuery.append(propertyValue);
				}
				if(i != propTypes.size() - 1) {
					insertQuery.append(", ");
				}
			}
			insertQuery.append(");");
			
			engine.insertData(insertQuery.toString());
		}
		
		String startTable;
		String startCol;
		String endTable;
		String endCol;
		String _FK = "_FK";

		if(engine instanceof AbstractEngine) {
			// determine existing FKs in schema
			Map<String, Set<String>> tableFKs = new HashMap<String, Set<String>>();
			if(relationships != null && !(relationships.size()==0)) {
				String relQuery = "SELECT DISTINCT ?x WHERE {?x <http://www.w3.org/2000/01/rdf-schema#subPropertyOf> <http://semoss.org/ontologies/Relation>}";
				RDFFileSesameEngine baseEngine = ((AbstractEngine) engine).getBaseDataEngine();
				ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(baseEngine, relQuery);
				String[] names = wrapper.getVariables();
				while(wrapper.hasNext()) {
					String relURI = wrapper.next().getRawVar(names[0]).toString();
					String rel = Utility.getClassName(relURI);
					String[] relVals = rel.split(".");
					for(int i = 0; i < relVals.length; i++) {
						if(relVals[1].endsWith(_FK)) {
							Set<String> fks;
							if(tableFKs.containsKey(relVals[0])) {
								fks = tableFKs.get(relVals[0]);
								fks.add(relVals[1]);
							} else {
								fks = new HashSet<String>();
								fks.add(relVals[1]);
								tableFKs.put(relVals[0], fks);
							}
						}
						if(relVals[3].endsWith(_FK)) {
							Set<String> fks;
							if(tableFKs.containsKey(relVals[2])) {
								fks = tableFKs.get(relVals[2]);
								fks.add(relVals[3]);
							} else {
								fks = new HashSet<String>();
								fks.add(relVals[3]);
								tableFKs.put(relVals[2], fks);
							}
						}
					}
				}
			}
			
			for(int r = 0; r < relationships.size(); r++) {
				Map<String, Object> relationship =  relationships.get(r);
				
				startTable = relationship.get("startNode").toString();
				startCol = relationship.get("startCol").toString();
				endTable = relationship.get("endNode").toString();
				endCol = relationship.get("endCol").toString();

				for(String table : tableFKs.keySet()) {
					Set<String> fks = tableFKs.get(table);
					if(fks.contains(startCol + _FK) && fks.contains(endCol + _FK)) {
						// add to external relationship table
						StringBuilder insertQuery = new StringBuilder();
						insertQuery.append("INSERT INTO ");
						insertQuery.append(table.toUpperCase());
						insertQuery.append(" (" );
						insertQuery.append(startCol);
						insertQuery.append(_FK);
						insertQuery.append(",");
						insertQuery.append(endCol);
						insertQuery.append(_FK);
						insertQuery.append(") VALUES '");
						insertQuery.append(nodeMapping.get(startTable).get(startCol));
						insertQuery.append("', '");
						insertQuery.append(nodeMapping.get(endTable).get(endCol));
						insertQuery.append("');");
						
						engine.insertData(insertQuery.toString());
					}
				}
				
				Set<String> fks = tableFKs.get(startTable);
				if(fks.contains(endTable + _FK)) {
					// update record in startTable with FK relationship to endTable
					StringBuilder updateQuery = new StringBuilder();
					updateQuery.append("UPDATE ");
					updateQuery.append(startTable.toUpperCase());
					updateQuery.append(" SET" );
					updateQuery.append(endCol + _FK);
					updateQuery.append("=");
					updateQuery.append(nodeMapping.get(endTable).get(endCol));
					updateQuery.append(" WHERE");
					updateQuery.append(startCol);
					updateQuery.append("='");
					updateQuery.append(nodeMapping.get(startTable).get(startCol));
					updateQuery.append("';");
					
					engine.insertData(updateQuery.toString());
				}
				
				fks = tableFKs.get(endTable);
				if(fks.contains(startTable + _FK)) {
					// update record in endTable with FK relationship to startTable
					StringBuilder updateQuery = new StringBuilder();
					updateQuery.append("UPDATE ");
					updateQuery.append(endTable.toUpperCase());
					updateQuery.append(" SET" );
					updateQuery.append(startCol + _FK);
					updateQuery.append("=");
					updateQuery.append(nodeMapping.get(startTable).get(startCol));
					updateQuery.append(" WHERE");
					updateQuery.append(endCol);
					updateQuery.append("='");
					updateQuery.append(nodeMapping.get(endTable).get(endCol));
					updateQuery.append("';");
					
					engine.insertData(updateQuery.toString());
				}
			}
		} else {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Base ontologogy (OWL File) not found for engine!");		
		}
	}
	
	private String buildSQLQuery(String table, String fk, String String3, String String4, String String5) {
		StringBuilder updateQuery = new StringBuilder();
		updateQuery.append("UPDATE ");
		updateQuery.append(table.toUpperCase());
		updateQuery.append(" SET" );
		updateQuery.append(fk);
		updateQuery.append("=");
		updateQuery.append(String3);
		updateQuery.append(" WHERE");
		updateQuery.append(String4);
		updateQuery.append("='");
		updateQuery.append(String5);
		updateQuery.append("';");
		return updateQuery.toString();
	}
	
	//OLD CODE using org.json, will delete when new code using gson is fully tested
//	public static Response saveFormDataJSON(MultivaluedMap<String, String> form) {
//	Gson gson = new Gson();
//	System.out.println(form);
//	System.out.println(form.getFirst("formData"));
//	String[] o = gson.fromJson(form.getFirst("formData"), String[].class);
//	String formData = form.getFirst("formData");
//	JSONArray Engines = new JSONArray(formData);
////	List<HashMap<String, String> Engines = gson.fromJson(o[0].toString(), new TypeToken<List<HashMap<String, String>>>() {}.getType());
//
////	String semossBaseURI = (String) DIHelper.getInstance().getLocalProp("Concept");
//	Properties p = DIHelper.getInstance().getRdfMap();
//	String semossBaseURI = "http://semoss.org/ontologies";
//	//String semossBaseURI = Constants.SEMOSS_URI;
////	String relationBaseURI = semossBaseURI + "/" + Constants.DEFAULT_RELATION_CLASS;
////	String conceptBaseURI = semossBaseURI + "/" + Constants.DEFAULT_NODE_CLASS;
////	String propertyBaseURI = semossBaseURI + "/" + Constants.DEFAULT_PROPERTY_CLASS;
//
//	for(int e = 0; e < Engines.length(); e++) {
//		JSONObject engineHash = Engines.getJSONObject(e);
////	for(MultivaluedMap<String, Object> engineHash : Engines) {
////		String engineName = engineHash.getFirst("engine").toString();
//		String engineName = engineHash.getString("engine");
//		IEngine engine = (IEngine)DIHelper.getInstance().getLocalProp(engineName);
//		
//		
//		String baseURI = semossBaseURI;
////		if(engineHash.getFirst("baseURI") != null) {
////			baseURI = engineHash.getFirst("baseURI").toString();
////		}
//		if(engineHash.has("baseURI")) {
//			baseURI = engineHash.getString("baseURI");
//		}
//		
//		String relationBaseURI = semossBaseURI + "/" + Constants.DEFAULT_RELATION_CLASS;
//		String conceptBaseURI = semossBaseURI + "/" + Constants.DEFAULT_NODE_CLASS;
//		String propertyBaseURI = semossBaseURI + "/" + Constants.DEFAULT_PROPERTY_CLASS;
//
////		List<HashMap<String, String>> nodes = gson.fromJson(engineHash.getFirst("nodes"), new TypeToken<List<HashMap<String, String>>>() {}.getType()); 
////		List<HashMap<String, String>> relationships = gson.fromJson(engineHash.getFirst("relationships"), new TypeToken<List<HashMap<String, String>>>() {}.getType());
//		
//		JSONArray nodes = engineHash.getJSONArray("nodes");
//		JSONArray relationships = new JSONArray();
//		if(engineHash.has("relationships")) {
//			relationships = engineHash.getJSONArray("relationships");
//		}
//		if(engine.getEngineType() == IEngine.ENGINE_TYPE.JENA || engine.getEngineType() == IEngine.ENGINE_TYPE.SESAME) {
//			saveRDFFormData(engine, baseURI, relationBaseURI, conceptBaseURI, propertyBaseURI, nodes, relationships);
//		} else if(engine.getEngineType() == IEngine.ENGINE_TYPE.RDBMS) {
//			saveRDBMSFormData(engine, baseURI, relationBaseURI, conceptBaseURI, propertyBaseURI, nodes, relationships);
//		} else {
//			Map<String, String> errorHash = new HashMap<String, String>();
//			errorHash.put("errorMessage", "Engine type not found!");
//			return Response.status(400).entity(WebUtility.getSO(gson.toJson(errorHash))).build();
//		}
//		
//		//commit information to db
//		engine.commit();
//	}
//	
//	return Response.status(200).entity(WebUtility.getSO(gson.toJson("success"))).build();
//}

//private static void saveRDFFormData(IEngine engine, String baseURI, String relationBaseURI, String conceptBaseURI, String propertyBaseURI, JSONArray nodes, JSONArray relationships) {
//	String nodeType;
//	String nodeValue;
//	String instanceConceptURI;
//	String propertyValue;
//	String propertyType;
//	String propertyURI;
//	
//	Map<String, String> nodeMapping = new HashMap<String, String>();
////	for(HashMap<String, String> node : nodes) {
//	for(int j = 0; j < nodes.length(); j++) {
//		JSONObject node = nodes.getJSONObject(j);
////		nodeType = node.get("conceptName");
////		nodeValue = node.get("conceptValue");
//		nodeType = node.getString("conceptName");
//		nodeValue = node.get("conceptValue").toString();
//		nodeMapping.put(nodeType, nodeValue);
//
//		instanceConceptURI = baseURI + "/" + nodeValue;
//		engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, RDF.TYPE, conceptBaseURI + "/" + nodeType, true});
//		engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, RDFS.LABEL, nodeValue, false});
//		
////		List<HashMap<String, String>> properties = gson.fromJson(node.get("properties"), new TypeToken<List<HashMap<String, String>>>() {}.getType());
//		JSONArray properties = node.getJSONObject("templateOptions").getJSONArray("properties");
////		for(HashMap<String, String> property : properties) {
//		for(int z = 0; z < properties.length(); z++) {
//			JSONObject property = properties.getJSONObject(z);
//			propertyValue = property.getString("propertyValue");
//			propertyType = property.getString("propertyName");
//			
////			propertyValue = property.get("propertyValue");
////			propertyType = property.get("propertyName");
//			propertyURI = propertyBaseURI + "/" + propertyType;
//			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, propertyURI, propertyValue, false});
//			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{propertyURI, RDF.TYPE, propertyBaseURI, true});
//		}
//	}
//
//	String startNode;
//	String endNode;
//	String relType;
//	String subject;
//	String instanceSubjectURI;
//	String object;
//	String instanceObjectURI;
//	String baseRelationshipURI;
//	String instanceRel;
//	String instanceRelationshipURI;
//	
//	for(int a = 0; a < relationships.length(); a++) {
////	for(HashMap<String, String> relationship : relationships) {
//		JSONObject relationship = relationships.getJSONObject(a);
////		startNode = relationship.get("startNode");
////		endNode = relationship.get("endNode");
//		startNode = relationship.getString("startNode");
//		endNode = relationship.getString("endNode");
//		subject = nodeMapping.get(startNode);
//		object = nodeMapping.get(endNode);
//		instanceSubjectURI = baseURI + "/" + subject;
//		instanceObjectURI = baseURI + "/" + object;
//		
////		relType = relationship.get("relType");
//		relType = relationship.getString("relType");
//		baseRelationshipURI = relationBaseURI + "/" + relType;
//		instanceRel = subject + ":" + object;
//		instanceRelationshipURI = baseRelationshipURI + "/" + instanceRel;
//		
//		engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, baseRelationshipURI, instanceObjectURI, true});
//		engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, instanceRelationshipURI, instanceObjectURI, true});
//		engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDFS.SUBPROPERTYOF, baseRelationshipURI, true});
//		engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDFS.LABEL, instanceRel, false});
//	}
//}
}
