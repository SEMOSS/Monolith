package prerna.semoss.web.form;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.h2.jdbc.JdbcClob;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.RDFS;

import prerna.algorithm.api.ITableDataFrame;
import prerna.engine.api.IEngine;
import prerna.engine.impl.AbstractEngine;
import prerna.engine.impl.rdf.RDFFileSesameEngine;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Constants;
import prerna.util.Utility;

public final class FormBuilder {

	private static final DateFormat DATE_DF = new SimpleDateFormat("yyy-MM-dd hh:mm:ss");
	private static final DateFormat SIMPLE_DATE_DF = new SimpleDateFormat("yyy-MM-dd");
	private static final DateFormat GENERIC_DF = new SimpleDateFormat("yyy-MM-dd'T'hh:mm:ss.SSSSSS'Z'");

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
	public static void saveForm(IEngine formBuilderEng, String formName, String formLocation) throws IOException {
		// clean table name
		formName = escapeForSQLStatement(formName);
		String formStorage = cleanTableName(formName);
		formStorage = escapeForSQLStatement(formStorage);
		formLocation = escapeForSQLStatement(formLocation);
		// make sure table name doesn't exist
		ITableDataFrame f2 = WrapperManager.getInstance().getSWrapper(formBuilderEng, "select count(*) from information_schema.tables where table_name = '" + formStorage + "'").getTableDataFrame(); 
		if((Double)f2.getData().get(0)[0] != 0 ) {
			throw new IOException("Form name already exists. Please modify the form name.");
		}
		
		//add form location into formbuilder db
		String insertMetadata = "INSERT INTO FORM_METADATA (FORM_NAME, FORM_TABLE, FORM_LOCATION) VALUES('" + formName + "', '" + formStorage + "', '" + formLocation + "')";
		formBuilderEng.insertData(insertMetadata);
		//create new table to store values for form name
		String createFormTable = "CREATE TABLE " + formStorage + " (ID INT, USER_ID VARCHAR(225), DATE_ADDED TIMESTAMP, DATA CLOB)";
		formBuilderEng.insertData(createFormTable);
	}

	public static void saveFormData(IEngine formBuilderEng, String formTableName, String userId, String formData) {
		Calendar cal = Calendar.getInstance();
		String currTime = DATE_DF.format(cal.getTime());

		String getLastIdQuery = "SELECT DISTINCT ID FROM " + formTableName + " ORDER BY ID DESC";
		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(formBuilderEng, getLastIdQuery);
		String retName = wrapper.getVariables()[0];
		Integer lastIdNum = 0;
		if(wrapper.hasNext()){ // need to call hasNext before you call next()
			lastIdNum = (int) wrapper.next().getVar(retName);
		}
		lastIdNum++;
		
		String insertSql = "INSERT INTO " + formTableName + " (ID, USER_ID, DATE_ADDED, DATA) VALUES("
				+ "'" + lastIdNum + "', '" + escapeForSQLStatement(userId) + "', '" + currTime + "', '" + escapeForSQLStatement(formData) + "')";
		formBuilderEng.insertData(insertSql);
	}
	
	/**
	 * 
	 * @param form
	 * @throws IOException 
	 */
	public static void commitFormData(IEngine engine, Map<String, Object> engineHash) throws IOException {
		if(engine == null) {
			throw new IOException("Engine cannot be found");
		}
		
		String semossBaseURI = "http://semoss.org/ontologies";
		String baseURI = engine.getNodeBaseUri();
		if(baseURI != null && !baseURI.isEmpty()) {
			baseURI = baseURI.replace("/Concept/", "");
		} else {
			baseURI = semossBaseURI;
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
			throw new IOException("Engine type cannot be found");
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
			//TODO: need to stop doing this null check - assuming always overriding
			boolean override = true;
			if(node.get("override") != null) {
				override = Boolean.parseBoolean(node.get("override").toString());
			}
			
			instanceConceptURI = baseURI + "/Concept/" + Utility.getInstanceName(nodeType) + "/" + nodeValue;
			// no need to add if overriding, triples already there
			if(!override) {
				engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, RDF.TYPE, nodeType, true});
				engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, RDFS.LABEL, nodeValue, false});
			}

			if(node.containsKey("properties")) {
				List<HashMap<String, Object>> properties = (List<HashMap<String, Object>>)node.get("properties");

				for(int j = 0; j < properties.size(); j++) {
					Map<String, Object> property = properties.get(j);
					propertyValue = property.get("propertyValue").toString();
					propertyType = property.get("propertyName").toString();

					propertyURI = propertyBaseURI + "/" + propertyType;
					if(override) {
						removeRDFNodeProp(engine, instanceConceptURI, propertyURI);
					}
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
			//TODO: how to deal with override
			//TODO: which direction does the override occur in, the subject or the object?
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

	private static void removeRDFNodeProp(IEngine engine, String instanceConceptURI, String propertyURI) {
		String getOldNodePropValuesQuery = "SELECT DISTINCT ?propVal WHERE { BIND(<" + instanceConceptURI + "> AS ?instance) {?instance <" + propertyURI + "> ?propVal} }";
		
		List<Object> propVals = new ArrayList<Object>();
		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(engine, getOldNodePropValuesQuery);
		String[] names = wrapper.getVariables();
		while(wrapper.hasNext()) {
			ISelectStatement ss = wrapper.next();
			propVals.add(ss.getVar(names[0]));
		}
		
		for(Object propertyValue : propVals) {
			engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{instanceConceptURI, propertyURI, propertyValue, false});
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
		Map<String, Map<String, String>> tableColTypesHash = getExistingRDBMSStructure(engine);
		
		List<String> tablesToRemoveDuplicates = new ArrayList<String>();
		List<String> colsForTablesToRemoveDuplicates = new ArrayList<String>();
		for(int j = 0; j < nodes.size(); j++) {
			Map<String, Object> node = nodes.get(j);

			// concept name passed to FE from metamodel so it comes back as a URI
			String nodeURI = node.get("conceptName").toString();
			tableName = Utility.getInstanceName(nodeURI);
			tableColumn = Utility.getClassName(nodeURI);
			tableValue = node.get("conceptValue").toString();
			//TODO: need to stop doing this null check - assuming always overriding
			boolean override = true;
			if(node.get("override") != null) {
				override = Boolean.parseBoolean(node.get("override").toString());
			}
			
			Map<String, String> colNamesAndType = tableColTypesHash.get(tableName.toUpperCase());
			if(colNamesAndType == null) {
				throw new IllegalArgumentException("Table name, " + tableName + ", cannot be found.");
			}
			if(!colNamesAndType.containsKey(tableColumn.toUpperCase())) {
				throw new IllegalArgumentException("Table column, " + tableColumn + ", within table name, " + tableName + ", cannot be found.");
			}
			
			List<Map<String, Object>> properties = (List<Map<String, Object>>)node.get("properties");
			Map<String, String> innerMap = new HashMap<String, String>();
			innerMap.put(tableColumn, tableValue);
			nodeMapping.put(tableName, innerMap);

			List<String> propNames = new ArrayList<String>();
			List<Object> propValues = new ArrayList<Object>();
			List<String> types = new ArrayList<String>();
			for(int k = 0; k < properties.size(); k++) {
				Map<String, Object> property = properties.get(k);
				String propName = Utility.getInstanceName(property.get("propertyName").toString());
				if(!colNamesAndType.containsKey(propName.toUpperCase())) {
					throw new IllegalArgumentException("Table column, " + propName + ", within table name, " + tableName + ", cannot be found.");
				}
				propNames.add(propName);
				propValues.add(property.get("propertyValue"));
				types.add(colNamesAndType.get(propName.toUpperCase()));
			}

			if(override && conceptExists(engine, tableName, tableColumn, tableValue)) {
				String updateQuery = createUpdateStatement(tableName, tableColumn, tableValue, propNames, propValues, types);
				//TODO: need to enable modifying the actual instance name as opposed to only its properties.. this would set the updateQuery to never return back an empty string
				if(!updateQuery.isEmpty()) {
					engine.insertData(updateQuery);
				}
				if(!tablesToRemoveDuplicates.contains(tableName)) {
					tablesToRemoveDuplicates.add(tableName);
					colsForTablesToRemoveDuplicates.add(tableColumn);
				}
			} else {
				String insertQuery = createInsertStatement(tableName, tableColumn, tableValue, propNames, propValues, types);
				engine.insertData(insertQuery);
			}
		}
		
		String startTable;
		String startVal;
		String startCol;
		String endTable;
		String endCol;
		String endVal;
		String _FK = "_FK";

		Map<String, String> colNamesAndType = null;
		for(int r = 0; r < relationships.size(); r++) {
			Map<String, Object> relationship =  relationships.get(r);

			String startURI = relationship.get("startNodeType").toString();
			startTable = Utility.getInstanceName(startURI);
			startCol = Utility.getClassName(startURI);
			startVal = relationship.get("startNodeVal").toString();

			colNamesAndType = tableColTypesHash.get(startTable.toUpperCase());
			if(colNamesAndType == null) {
				throw new IllegalArgumentException("Table name, " + startTable + ", cannot be found.");
			}
			if(!colNamesAndType.containsKey(startCol.toUpperCase())) {
				throw new IllegalArgumentException("Table column, " + startCol + ", within table name, " + startTable + ", cannot be found.");
			}
			
			String endURI = relationship.get("endNodeType").toString();
			endTable = Utility.getInstanceName(endURI);
			endCol =  Utility.getClassName(endURI);
			endVal = relationship.get("endNodeVal").toString();

			colNamesAndType = tableColTypesHash.get(endTable.toUpperCase());
			if(colNamesAndType == null) {
				throw new IllegalArgumentException("Table name, " + endTable + ", cannot be found.");
			}
			if(!colNamesAndType.containsKey(endCol.toUpperCase())) {
				throw new IllegalArgumentException("Table column, " + endCol + ", within table name, " + endTable + ", cannot be found.");
			}
			
			boolean override = true;
			if(relationship.get("override") != null) {
				override = Boolean.parseBoolean(relationship.get("override").toString());
			}

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
				updateQuery.append(startCol);
				updateQuery.append("='");
				updateQuery.append(startVal);
				updateQuery.append("' WHERE ");
				updateQuery.append(endTable + _FK);
				updateQuery.append("='");
				updateQuery.append(endVal);
				updateQuery.append("';");
				
				if(override && conceptExists(engine, startTable, startCol, startVal)) {
					if(!tablesToRemoveDuplicates.contains(startTable)) {
						tablesToRemoveDuplicates.add(startTable);
						colsForTablesToRemoveDuplicates.add(startCol);
					}
				}
			} else {
				updateQuery.append("UPDATE ");
				updateQuery.append(endTable.toUpperCase());
				updateQuery.append(" SET " );
				updateQuery.append(endCol);
				updateQuery.append("='");
				updateQuery.append(endVal);
				updateQuery.append("' WHERE ");
				updateQuery.append(startTable + _FK);
				updateQuery.append("='");
				updateQuery.append(startVal);
				updateQuery.append("';");
				
				if(override && conceptExists(engine, endTable, endCol, endVal)) {
					if(!tablesToRemoveDuplicates.contains(endTable)) {
						tablesToRemoveDuplicates.add(endTable);
						colsForTablesToRemoveDuplicates.add(endCol);
					}
				}
			}
			engine.insertData(updateQuery.toString());
		}
		
		//remove duplicates for all tables affected
		removeDuplicates(engine, tablesToRemoveDuplicates, colsForTablesToRemoveDuplicates);
	}
	
	private static void removeDuplicates(IEngine engine, List<String> tablesToRemoveDuplicates, List<String> colsForTablesToRemoveDuplicates) {
		final String TEMP_EXTENSION = "____TEMP";
		
		for(int i = 0; i < tablesToRemoveDuplicates.size(); i++) {
			String tableName = tablesToRemoveDuplicates.get(i);
			String colName = colsForTablesToRemoveDuplicates.get(i);
			
			String query = "CREATE TABLE " + tableName + TEMP_EXTENSION + " AS (SELECT DISTINCT * FROM " + tableName + " WHERE " + colName + " IS NOT NULL AND TRIM(" + colName + ") <> '' )";
			engine.insertData(query);
			query = "DROP TABLE " + tableName;
			engine.insertData(query);
			query = "ALTER TABLE " + tableName + TEMP_EXTENSION + " RENAME TO " + tableName;
			engine.insertData(query);
		}
	}

	//TODO: need to make this generic for any rdbms engine
	//TODO: need to expose what the rdbms type is such that we can use the SQLQueryUtil
	private static Map<String, Map<String, String>> getExistingRDBMSStructure(IEngine rdbmsEngine) {
		Map<String, Map<String, String>> retMap = new HashMap<String, Map<String, String>>();
		
		// get all the tables names in the H2 database
		String getAllTablesQuery = "SHOW TABLES FROM PUBLIC";
		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(rdbmsEngine, getAllTablesQuery);
		String[] names = wrapper.getVariables();
		Set<String> tableNames = new HashSet<String>();
		while(wrapper.hasNext()) {
			ISelectStatement ss = wrapper.next();
			String tableName = ss.getVar("TABLE_NAME") + "";
			tableNames.add(tableName);
		}
		
		// get all the columns and their types for each table name
		String defaultColTypesQuery = "SHOW COLUMNS FROM ";
		for(String tableName : tableNames) {
			String getAllColTypesQuery = defaultColTypesQuery + tableName;
			wrapper = WrapperManager.getInstance().getSWrapper(rdbmsEngine, getAllColTypesQuery);
			names = wrapper.getVariables();
			Map<String, String> colTypeHash = new HashMap<String, String>();
			while(wrapper.hasNext()) {
				ISelectStatement ss = wrapper.next();
				String colName = ss.getVar("COLUMN_NAME") + "";
				String colType = ss.getVar("TYPE") + "";
				colTypeHash.put(colName, colType);
			}
			
			// add the table name and column type for the table name
			retMap.put(tableName, colTypeHash);
		}
		
		return retMap;
	}
	
	private static boolean conceptExists(IEngine engine, String tableName, String colName, String instanceValue) {
		String query = "SELECT DISTINCT " + colName + " FROM " + tableName + " WHERE " + colName + "='" + escapeForSQLStatement(instanceValue) + "'";
		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(engine, query);
		String[] names = wrapper.getVariables();
		while(wrapper.hasNext()) {
			return true;
		}
		return false;
	}
	
	private static String createInsertStatement(String tableName, String tableColumn, String tableValue, List<String> propNames, List<Object> propValues, List<String> types) {
		StringBuilder insertQuery = new StringBuilder();
		insertQuery.append("INSERT INTO ");
		insertQuery.append(tableName.toUpperCase());
		insertQuery.append(" (");
		insertQuery.append(tableColumn.toUpperCase());
		if(propNames.size() > 0) {
			insertQuery.append(",");
			for(int i = 0; i < propNames.size(); i++) {
				insertQuery.append(propNames.get(i).toUpperCase());
				if(i != propNames.size() - 1) {
					insertQuery.append(",");
				}
			}
		}

		insertQuery.append(") VALUES ('");
		insertQuery.append(tableValue);
		insertQuery.append("'");

		if(propNames.size() > 0) {
			insertQuery.append(",");
			for(int i = 0; i < propValues.size(); i++) {
				Object propertyValue = propValues.get(i);
				String type = types.get(i);
				if(type.contains("VARCHAR")) {
					insertQuery.append("'");
					insertQuery.append(propertyValue.toString().toUpperCase());
					insertQuery.append("'");
				} else if(type.contains("INT") || type.contains("DECIMAL") || type.contains("DOUBLE") || type.contains("LONG") || type.contains("BIGINT")
						|| type.contains("TINYINT") || type.contains("SMALLINT")){
					insertQuery.append(propertyValue);
				} else  if(type.contains("DATE")) {
					Date dateValue = null;
					try {
						dateValue = GENERIC_DF.parse(propertyValue + "");
					} catch (ParseException e) {
						e.printStackTrace();
						throw new IllegalArgumentException("Input value, " + propertyValue + " for column " + propNames.get(i) + " cannot be parsed as a date.");
					}
					propertyValue = SIMPLE_DATE_DF.format(dateValue);
					insertQuery.append("'");
					insertQuery.append(propertyValue);
					insertQuery.append("'");
				} else if(type.contains("TIMESTAMP")) {
					Date dateValue = null;
					try {
						dateValue = GENERIC_DF.parse(propertyValue + "");
					} catch (ParseException e) {
						e.printStackTrace();
						throw new IllegalArgumentException("Input value, " + propertyValue + " for column " + propNames.get(i) + " cannot be parsed as a date.");
					}
					propertyValue = DATE_DF.format(dateValue);
					insertQuery.append("'");
					insertQuery.append(propertyValue);
					insertQuery.append("'");
				}
				if(i != propNames.size() - 1) {
					insertQuery.append(", ");
				}
			}
		}
		insertQuery.append(");");
		
		return insertQuery.toString();
	}
	
	private static String createUpdateStatement(String tableName, String tableColumn, String tableValue, List<String> propNames, List<Object> propValues, List<String> types) {
		if(propNames.size() == 0) {
			return "";
		}
		
		StringBuilder insertQuery = new StringBuilder();
		insertQuery.append("UPDATE ");
		insertQuery.append(tableName.toUpperCase());
		insertQuery.append(" SET ");
		for(int i = 0; i < propNames.size(); i++) {
			String propName = propNames.get(i).toUpperCase();
			Object propertyValue = propValues.get(i);
			String type = types.get(i);
			insertQuery.append(propName);
			insertQuery.append("=");
			if(type.contains("VARCHAR")) {
				insertQuery.append("'");
				insertQuery.append(propertyValue.toString().toUpperCase());
				insertQuery.append("'");
			} else if(type.contains("INT") || type.contains("DECIMAL") || type.contains("DOUBLE") || type.contains("LONG") || type.contains("BIGINT")
					|| type.contains("TINYINT") || type.contains("SMALLINT")){
				insertQuery.append(propertyValue);
			} else  if(type.contains("DATE")) {
				Date dateValue = null;
				try {
					dateValue = GENERIC_DF.parse(propertyValue + "");
				} catch (ParseException e) {
					e.printStackTrace();
					throw new IllegalArgumentException("Input value, " + propertyValue + " for column " + propNames.get(i) + " cannot be parsed as a date.");
				}
				propertyValue = SIMPLE_DATE_DF.format(dateValue);
				insertQuery.append("'");
				insertQuery.append(propertyValue);
				insertQuery.append("'");
			} else if(type.contains("TIMESTAMP")) {
				Date dateValue = null;
				try {
					dateValue = GENERIC_DF.parse(propertyValue + "");
				} catch (ParseException e) {
					e.printStackTrace();
					throw new IllegalArgumentException("Input value, " + propertyValue + " for column " + propNames.get(i) + " cannot be parsed as a date.");
				}
				propertyValue = DATE_DF.format(dateValue);
				insertQuery.append("'");
				insertQuery.append(propertyValue);
				insertQuery.append("'");
			}
			if(i != propNames.size() - 1) {
				insertQuery.append(",");
			}
		}

		insertQuery.append(" WHERE ");
		insertQuery.append(tableColumn);
		insertQuery.append("='");
		insertQuery.append(tableValue);
		insertQuery.append("'");

		return insertQuery.toString();
	}
	
	public static List<Map<String, String>> getStagingData(IEngine formBuilderEng, String formTableName) {
		String sqlQuery = "SELECT ID, USER_ID, DATE_ADDED, DATA FROM " + formTableName;
		
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
	
	public static void deleteFromStaggingArea(IEngine formBuilderEng, String formName, String[] formIds) {
		formName = cleanTableName(formName);
		formName = escapeForSQLStatement(formName);
		String idsString = createIdString(formIds);
		String deleteQuery = "DELETE FROM " + formName + " WHERE ID IN " + idsString;
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
		while(s.contains("  ")){
			s = s.replace("  ", " ");
		}
		s = s.replaceAll(" ", "_");
		return s.replaceAll("[^a-zA-Z0-9\\_]", "");
	}
	
	public static String escapeForSQLStatement(String s) {
		return s.replaceAll("'", "''");
	}
}
