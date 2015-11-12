package prerna.semoss.web.form;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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
import prerna.engine.impl.rdbms.RDBMSNativeEngine;
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
	public static void saveFormData(IEngine engine, MultivaluedMap<String, String> form) {
		Gson gson = new Gson();
		String formData = form.getFirst("formData");
		Map<String, Object> engineHash = gson.fromJson(formData, new TypeToken<Map<String, Object>>() {}.getType());

		Properties p = DIHelper.getInstance().getRdfMap();
		//TODO : need to grab this from the OWL or somewhere else
		String semossBaseURI = "http://semoss.org/ontologies";

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
			saveRDFFormData(engine, baseURI, relationBaseURI, propertyBaseURI, nodes, relationships);
		} else if(engine.getEngineType() == IEngine.ENGINE_TYPE.RDBMS) {
			saveRDBMSFormData(engine, baseURI, relationBaseURI, conceptBaseURI, propertyBaseURI, nodes, relationships);
		} else {
			Map<String, String> errorHash = new HashMap<String, String>();
			errorHash.put("errorMessage", "Engine type not found!");
		}

		//commit information to db
		engine.commit();
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
	private static void saveRDFFormData(IEngine engine, String baseURI, String relationBaseURI, String propertyBaseURI, List<HashMap<String, Object>> nodes, List<HashMap<String, Object>> relationships) {
		String nodeType;
		String nodeValue;
		String instanceConceptURI;
		String propertyValue;
		String propertyType;
		String propertyURI;

		Map<String, String> nodeMapping = new HashMap<String, String>();
		//Save nodes and properties of nodes
		for(int i = 0; i < nodes.size(); i++) {
			Map<String, Object> node = nodes.get(i);
			nodeType = node.get("conceptName").toString();
			nodeValue = node.get("conceptValue").toString();
			nodeMapping.put(nodeValue, nodeType);

			instanceConceptURI = baseURI + "/Concept/" + Utility.getInstanceName(nodeType) + "/" + nodeValue;
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, RDF.TYPE, nodeType, true});
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
			startNode = relationship.get("startNodeVal").toString();
			endNode = relationship.get("endNodeVal").toString();
			subject = nodeMapping.get(startNode);
			object = nodeMapping.get(endNode);
			instanceSubjectURI = baseURI + "/" + Utility.getInstanceName(subject) + "/" + startNode;
			instanceObjectURI = baseURI + "/" + Utility.getInstanceName(object) + "/" + endNode;

			relType = relationship.get("relType").toString();
			baseRelationshipURI = relationBaseURI + "/" + relType;
			instanceRel = startNode + ":" + endNode;
			instanceRelationshipURI = baseURI + "/Relation/" + relType + "/" + instanceRel;

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
			//			tableColumn = node.get("columnName").toString();
			tableValue = node.get("conceptValue").toString();

			//			HashMap<String, String> innerMap = new HashMap<String, String>();
			//			innerMap.put(tableColumn, tableValue);
			//			nodeMapping.put(tableName, innerMap);

			Map<String, Object> templateOptions = (Map<String, Object>)node.get("templateOptions");
			tableColumn = templateOptions.get("columnName").toString();
			tableName = tableColumn;
			List<HashMap<String, Object>> properties = (List<HashMap<String, Object>>)templateOptions.get("properties");

			HashMap<String, String> innerMap = new HashMap<String, String>();
			innerMap.put(tableColumn, tableValue);
			nodeMapping.put(tableName, innerMap);

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

			String dataType = checkColumnDataType(engine, tableColumn);
			boolean useQuotes = useQuotes(dataType);
			if(useQuotes) {
				insertQuery.append(") VALUES ('");
				insertQuery.append(tableValue);
				insertQuery.append("'");
			} else {
				insertQuery.append(") VALUES (");
				insertQuery.append(tableValue);
			}

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


	private static String checkColumnDataType(IEngine engine, String tableName) {
		String type = "";
		try {

			String getColumnTypeQuery = "SELECT * FROM" + tableName + "LIMIT 1";
			Map<String, Object> map = (Map<String, Object>) engine.execQuery(getColumnTypeQuery);
			ResultSet rs = (ResultSet) map.get(RDBMSNativeEngine.RESULTSET_OBJECT);
			ResultSetMetaData rsmd = rs.getMetaData();
			type = rsmd.getColumnTypeName(1);

		} catch(Exception e) {

		}
		return type;
	}

	private static boolean useQuotes(String columnType) {
		if(columnType.equals("FLOAT")) return false;
		else return true;
	}
}
