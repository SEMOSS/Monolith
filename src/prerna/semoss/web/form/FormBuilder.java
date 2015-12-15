package prerna.semoss.web.form;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.ws.rs.core.MultivaluedMap;

import org.apache.commons.io.IOUtils;
import org.h2.jdbc.JdbcClob;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.RDFS;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import prerna.engine.api.IEngine;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.Utility;

public final class FormBuilder {

	private static final DateFormat df = new SimpleDateFormat("yyy-MM-dd hh:mm:ss");
	private static transient Gson gson = new Gson();
	private static final String FORM_BUILDER_ENGINE_NAME = "form_builder_engine";
	
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
	public static void saveForm(String formName, String formData, String jsonLoc) throws IOException {
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
		
		//add form location into formbuilder db
		String insertMetadata = "INSERT INTO FORM_METADATA (FORM_NAME, FORM_LOCATION) VALUES('" + formName + "', '" + jsonLoc + "')";
		IEngine formBuilderEng = (IEngine) DIHelper.getInstance().getLocalProp(FORM_BUILDER_ENGINE_NAME);
		formBuilderEng.insertData(insertMetadata);
		
		//create new table to store values for form name
		String createFormTable = "CREATE TABLE FORM_DATA (ID INT, USER_ID VARCHAR(225), DATE_ADDED TIMESTAMP, DATA CLOB)";
		formBuilderEng.insertData(createFormTable);
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

	public static void saveFormData(String formName, String userId, MultivaluedMap<String, String> form) {
		IEngine formBuilderEng = (IEngine) DIHelper.getInstance().getLocalProp(FORM_BUILDER_ENGINE_NAME);
		Calendar cal = Calendar.getInstance();
		String currTime = df.format(cal.getTime());
		
		String getLastIdQuery = "SELECT DISTINCT ID FROM " + formName + " ORDER BY ID DESC";
		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(formBuilderEng, getLastIdQuery);
		String retName = wrapper.getVariables()[0];
		Integer lastIdNum = 0;
		if(wrapper.hasNext()){ // need to call hasNext before you call next()
			lastIdNum = (int) wrapper.next().getVar(retName);
		}
		lastIdNum++;
		
		String insertSql = "INSERT INTO " + formName + " (ID, USER_ID, DATE_ADDED, DATA) VALUES("
				+ "'" + lastIdNum + "', '" + escapeForSQLStatement(userId) + "', '" + currTime + "', '" + escapeForSQLStatement(form.getFirst("formData")) + "')";
		formBuilderEng.insertData(insertSql);
	}
	
	/**
	 * 
	 * @param form
	 */
	public static void commitFormData(IEngine engine, MultivaluedMap<String, String> form) {
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
			nodeValue = Utility.cleanString(node.get("conceptValue").toString(), true);
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
			startNode = Utility.cleanString(relationship.get("startNodeVal").toString(), true);
			endNode = Utility.cleanString(relationship.get("endNodeVal").toString(), true);
			subject = nodeMapping.get(startNode);
			object = nodeMapping.get(endNode);
			instanceSubjectURI = baseURI + "/Concept/" + Utility.getInstanceName(subject) + "/" + startNode;
			instanceObjectURI = baseURI + "/Concept/" + Utility.getInstanceName(object) + "/" + endNode;

			baseRelationshipURI = relationship.get("relType").toString();
			instanceRel = startNode + ":" + endNode;
			instanceRelationshipURI = baseRelationshipURI + "/" + instanceRel;

			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, relationBaseURI, instanceObjectURI, true});
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, baseRelationshipURI, instanceObjectURI, true});
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, instanceRelationshipURI, instanceObjectURI, true});
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDFS.SUBPROPERTYOF, baseRelationshipURI, true});
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDFS.SUBPROPERTYOF, relationBaseURI, true});
			engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDF.TYPE, RDF.PROPERTY, true});
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

			// concept name passed to FE from metamodel so it comes back as a URI
			String nodeURI = node.get("conceptName").toString();
			tableName = Utility.getInstanceName(nodeURI);
			tableColumn = Utility.getClassName(nodeURI);
			tableValue = node.get("conceptValue").toString();
			
			List<Map<String, Object>> properties = (List<Map<String, Object>>)node.get("properties");
			Map<String, String> innerMap = new HashMap<String, String>();
			innerMap.put(tableColumn, tableValue);
			nodeMapping.put(tableName, innerMap);

			List<String> propTypes = new ArrayList<String>();
			List<Object> propValues = new ArrayList<Object>();
			for(int k = 0; k < properties.size(); k++) {
				Map<String, Object> property = properties.get(k);
				propTypes.add(Utility.getInstanceName(property.get("propertyName").toString()));
				propValues.add(property.get("propertyValue"));
			}

			StringBuilder insertQuery = new StringBuilder();
			insertQuery.append("INSERT INTO ");
			insertQuery.append(tableName.toUpperCase());
			insertQuery.append(" (");
			insertQuery.append(tableColumn.toUpperCase());
			if(propTypes.size() > 0) {
				insertQuery.append(",");
				for(int i = 0; i < propTypes.size(); i++) {
					insertQuery.append(propTypes.get(i).toUpperCase());
					if(i != propTypes.size() - 1) {
						insertQuery.append(",");
					}
				}
			}

			insertQuery.append(") VALUES ('");
			insertQuery.append(tableValue);
			insertQuery.append("'");

			if(propTypes.size() > 0) {
				insertQuery.append(",");
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
			}
			insertQuery.append(");");

			engine.insertData(insertQuery.toString());
		}

		String startTable;
		String startVal;
		String startCol;
		String endTable;
		String endCol;
		String endVal;
		String _FK = "_FK";

		for(int r = 0; r < relationships.size(); r++) {
			Map<String, Object> relationship =  relationships.get(r);

			String startURI = relationship.get("startNodeType").toString();
			startTable = Utility.getInstanceName(startURI);
			startCol = Utility.getClassName(startURI);
			startVal = relationship.get("startNodeVal").toString();
			
			String endURI = relationship.get("endNodeType").toString();
			endTable = Utility.getInstanceName(endURI);
			endCol =  Utility.getClassName(endURI);
			endVal = relationship.get("endNodeVal").toString();

			boolean addToStart = false;
			String[] relVals = Utility.getInstanceName(relationship.get("relType").toString()).split("\\.");
			if(relVals[1].endsWith(_FK)) {
				if(relVals[1].equals(startTable + _FK)) {
					addToStart = true;
				} 
			} if(relVals[3].endsWith(_FK)) {
				if(relVals[1].equals(startTable + _FK)) {
					addToStart = true;
				}
			}
			
			StringBuilder updateQuery = new StringBuilder();
			
			if(addToStart) {
				updateQuery.append("UPDATE ");
				updateQuery.append(startTable.toUpperCase());
				updateQuery.append(" SET " );
				updateQuery.append(endTable + _FK);
				updateQuery.append("='");
				updateQuery.append(endVal);
				updateQuery.append("' WHERE ");
				updateQuery.append(startCol);
				updateQuery.append("='");
				updateQuery.append(startVal);
				updateQuery.append("';");
				
			} else {
				updateQuery.append("UPDATE ");
				updateQuery.append(endTable.toUpperCase());
				updateQuery.append(" SET " );
				updateQuery.append(startTable + _FK);
				updateQuery.append("='");
				updateQuery.append(startVal);
				updateQuery.append("' WHERE ");
				updateQuery.append(endCol);
				updateQuery.append("='");
				updateQuery.append(endVal);
				updateQuery.append("';");
			}
			engine.insertData(updateQuery.toString());
		}
	}
	
	private static String escapeForSQLStatement(String s) {
		return s.replaceAll("'", "''");
	}

	public static List<Map<String, String>> getStagingData(String formName) {
		IEngine formBuilderEng = (IEngine) DIHelper.getInstance().getLocalProp(FORM_BUILDER_ENGINE_NAME);
		String sqlQuery = "SELECT ID, USER_ID, DATE_ADDED, DATA FROM " + formName;
		
		List<Map<String, String>> results = new ArrayList<Map<String, String>>();
		
		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(formBuilderEng, sqlQuery);
		String[] names = wrapper.getVariables();
		while(wrapper.hasNext()) {
			ISelectStatement ss = wrapper.next();
			Map<String, String> row = new HashMap<String, String>();
			row.put("id",  ss.getVar(names[0]) + "");
			row.put("userId", ss.getVar(names[1]) + "");
			row.put("dateAdded", ss.getVar(names[2]) + "");
			JdbcClob obj = (JdbcClob) ss.getRawVar(names[3]);
			
			InputStream insightDefinition = null;
			try {
				insightDefinition = obj.getAsciiStream();
				row.put("data", IOUtils.toString(insightDefinition));
			} catch (SQLException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
			results.add(row);
		}
		
		return results;
	}
	
	public static void deleteFromStaggingArea(MultivaluedMap<String, String> form) {
		String formName = form.getFirst("formName");
		String idsString = createIdString(gson.fromJson(form.getFirst("ids"), String[].class));
		String deleteQuery = "DELETE FROM " + formName + " WHERE ID IN " + idsString;
		IEngine formBuilderEng = (IEngine) DIHelper.getInstance().getLocalProp(FORM_BUILDER_ENGINE_NAME);
		formBuilderEng.removeData(deleteQuery);
	}
	
	private static String createIdString(String... ids){
		String idsString = "(";
		for(String id : ids){
			idsString = idsString + "'" + id + "', ";
		}
		idsString = idsString.substring(0, idsString.length() - 2) + ")";
		
		return idsString;
	}
	
	/**
	 * Remove all non alpha-numeric underscores from form name
	 * @param s
	 * @return
	 */
	public static String cleanTableName(String s) {
		return s.replaceAll("[^a-zA-Z0-9\\_]", "");
	}
}
