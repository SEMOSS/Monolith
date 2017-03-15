package prerna.semoss.web.form;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.RDFS;

import prerna.engine.api.IEngine;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Utility;

public class RdfFormBuilder extends AbstractFormBuilder {

	/////////////////////////////////////////////RDF CODE/////////////////////////////////////////////

	protected RdfFormBuilder(IEngine engine) {
		super(engine);
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
	 * @param auditLogTableName 
	 * @param formEng 
	 * @param user 
	 */
	protected void saveFormData(
			String baseURI,
			String conceptBaseURI,
			String relationBaseURI, 
			String propertyBaseURI, 
			List<HashMap<String, Object>> nodes, 
			List<HashMap<String, Object>> relationships, 
			List<HashMap<String, Object>> removeNodes, 
			List<HashMap<String, Object>> removeRelationships)
	{
		// get all the necessary values
		String startNode;
		String endNode;
		String subject;
		String instanceSubjectURI;
		String object;
		String instanceObjectURI;
		String relationType;
		String baseRelationshipURI;
		String instanceRel;
		String instanceRelationshipURI;
		String conceptType;
		String conceptValue;
		String instanceConceptURI;
		Object propertyValue;
		String propertyURI;

		// for deleting existing relationships
		for(int i = 0; i < removeRelationships.size(); i++) {
			Map<String, Object> deleteRelationships = removeRelationships.get(i);
			startNode = Utility.cleanString(deleteRelationships.get("startNodeVal").toString(), true);
			endNode = Utility.cleanString(deleteRelationships.get("endNodeVal").toString(), true);
			subject = deleteRelationships.get("startNodeType").toString();
			object =  deleteRelationships.get("endNodeType").toString(); 
			instanceSubjectURI = baseURI + "/Concept/" + Utility.getInstanceName(subject) + "/" + startNode;
			instanceObjectURI = baseURI + "/Concept/" + Utility.getInstanceName(object) + "/" +endNode;

			relationType = Utility.getInstanceName(deleteRelationships.get("relType").toString());
			baseRelationshipURI = relationBaseURI + "/" + relationType;
			instanceRel = startNode + ":" + endNode;
			instanceRelationshipURI =  baseURI + "/Relation/" + relationType + "/" + instanceRel;
			
			overrideRDFRelationship(instanceSubjectURI, subject, instanceObjectURI, object, baseRelationshipURI);
		}
		
		// for deleting existing concepts
		for(int i = 0; i < removeNodes.size(); i++) {
			Map<String, Object> deleteConcept = removeNodes.get(i);
			conceptType = deleteConcept.get("conceptName").toString();
			conceptValue = Utility.cleanString(deleteConcept.get("conceptValue").toString(), true);
			instanceConceptURI = baseURI + "/Concept/" + Utility.getInstanceName(conceptType) + "/" + conceptValue;
			
			boolean removeConcept = false;
			if(deleteConcept.get(OVERRIDE) != null) {
				removeConcept = Boolean.parseBoolean(deleteConcept.get(OVERRIDE).toString());
			}
			
			if(removeConcept){
				// need to delete all properties before deleting concept
				Set<String> uriBindingList = new HashSet<String>();
				uriBindingList.add(instanceConceptURI);
				deleteAllRDFConnectionsToConcept(uriBindingList);
				removeRDFNodeAndAllProps(uriBindingList);
			} else if(deleteConcept.containsKey("properties")) {
				List<HashMap<String, Object>> properties = (List<HashMap<String, Object>>) deleteConcept.get("properties");

				for(int j = 0; j < properties.size(); j++) {
					Map<String, Object> property = properties.get(j);
					propertyValue = property.get("propertyValue");
					if(propertyValue instanceof String) {
						// check if string val is a date
						SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
							try {
								dateFormat.setLenient(false);
								propertyValue= (Date) dateFormat.parse(((String) propertyValue).trim());
							} catch (ParseException e) {
								propertyValue = propertyValue.toString();
							}
					}
					propertyURI = property.get("propertyName").toString();

					this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{instanceConceptURI, propertyURI, propertyValue, false});
					// ugh... we need to push forms
					// values being passed are not properly keeping track of things that have underscores and things that don't
					// just going to try both versions
					this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{instanceConceptURI, propertyURI, Utility.cleanString(propertyValue.toString(), true, false, true), false});

					// add audit log statement
					Calendar cal = Calendar.getInstance();
					String currTime = DATE_DF.format(cal.getTime());
					addAuditLog(REMOVE, instanceConceptURI, "", "", propertyURI, propertyValue + "", currTime);
					this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{propertyURI, RDF.TYPE, propertyBaseURI, true});
				}
			}
		}
		
		// for adding new relationships
		for(int i = 0; i < relationships.size(); i++) {
			Map<String, Object> relationship = relationships.get(i);
			startNode = Utility.cleanString(relationship.get("startNodeVal").toString(), true);
			endNode = Utility.cleanString(relationship.get("endNodeVal").toString(), true);
			subject = relationship.get("startNodeType").toString();
			object =  relationship.get("endNodeType").toString(); 
			instanceSubjectURI = baseURI + "/Concept/" + Utility.getInstanceName(subject) + "/" + startNode;
			instanceObjectURI = baseURI + "/Concept/" + Utility.getInstanceName(object) + "/" +endNode;

			relationType = Utility.getInstanceName(relationship.get("relType").toString());
			baseRelationshipURI = relationBaseURI + "/" + relationType;
			instanceRel = startNode + ":" + endNode;
			instanceRelationshipURI =  baseURI + "/Relation/" + relationType + "/" + instanceRel;

			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, RDF.TYPE, subject, true});
			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceObjectURI, RDF.TYPE, object, true});
			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, relationBaseURI, instanceObjectURI, true});
			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, baseRelationshipURI, instanceObjectURI, true});
			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceSubjectURI, instanceRelationshipURI, instanceObjectURI, true});
			// add audit log statement
			Calendar cal = Calendar.getInstance();
			String currTime = DATE_DF.format(cal.getTime());
			addAuditLog(ADD, instanceSubjectURI, baseRelationshipURI, instanceObjectURI, "", "", currTime);
			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDFS.SUBPROPERTYOF, baseRelationshipURI, true});
			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDFS.SUBPROPERTYOF, relationBaseURI, true});
			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDF.TYPE, RDF.PROPERTY, true});
			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceRelationshipURI, RDFS.LABEL, instanceRel, false});
		}
		
		//for adding concepts and properties of nodes
		for(int i = 0; i < nodes.size(); i++) {
			Map<String, Object> concept = nodes.get(i);
			conceptType = concept.get("conceptName").toString();
			conceptValue = Utility.cleanString(concept.get("conceptValue").toString(), true);

			instanceConceptURI = baseURI + "/Concept/" + Utility.getInstanceName(conceptType) + "/" + conceptValue;
			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, RDF.TYPE, conceptType, true});
			this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, RDFS.LABEL, conceptValue, false});
			if(concept.containsKey("properties")) {
				List<HashMap<String, Object>> properties = (List<HashMap<String, Object>>) concept.get("properties");

				for(int j = 0; j < properties.size(); j++) {
					Map<String, Object> property = properties.get(j);
					propertyValue = property.get("propertyValue");
					if(propertyValue instanceof String) {
						// check if string val is a date
						SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
						try {
							dateFormat.setLenient(true);
							propertyValue= (Date) dateFormat.parse(((String) propertyValue).trim());
						} catch (ParseException e) {
							propertyValue = propertyValue.toString();
						}
					}
					propertyURI = property.get("propertyName").toString();

					this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{instanceConceptURI, propertyURI, propertyValue, false});
					// add audit log statement
					Calendar cal = Calendar.getInstance();
					String currTime = DATE_DF.format(cal.getTime());
					addAuditLog(ADD, instanceConceptURI, "", "", propertyURI, propertyValue + "", currTime);
					this.engine.doAction(IEngine.ACTION_TYPE.ADD_STATEMENT, new Object[]{propertyURI, RDF.TYPE, propertyBaseURI, true});
				}
			}
		}
	}
	
	/**
	 * Deletes the relationship and relationship properties that exist between an instance and all other instances of a specified type
	 * @param engine
	 * @param instanceSubjectURI
	 * @param subjectTypeURI
	 * @param instanceObjectURI
	 * @param objectTypeURI
	 * @param baseRelationshipURI
	 * @param deleteDownstream
	 * @param removeNode 
	 * @param deleteUnconnectedConcepts 
	 * @param removeNode 
	 * @param deleteUnconnectedConcepts 
	 */
	private void overrideRDFRelationship(
			String instanceSubjectURI, 
			String subjectTypeURI, 
			String instanceObjectURI, 
			String objectTypeURI, 
			String baseRelationshipURI)
	{
		// generate the query to override existing rdf relationships
		StringBuilder query = new StringBuilder("SELECT DISTINCT ?SUB ?PRED ?OBJ ?LABEL ?PROP ?VAL WHERE { ");
		query.append("BIND(<" + instanceSubjectURI + "> AS ?SUB) ");
		query.append("BIND(<" + instanceObjectURI + "> AS ?OBJ) ");
		query.append("{?SUB <").append(RDF.TYPE).append("> <" + subjectTypeURI + ">} ");
		query.append("{?OBJ <").append(RDF.TYPE).append("> <" + objectTypeURI + ">} ");
		query.append("{ ");
		query.append("{?PRED <").append(RDFS.SUBPROPERTYOF).append("> <" + baseRelationshipURI + ">} ");
		query.append("{?SUB ?PRED ?OBJ} ");
		query.append("OPTIONAL{ ?PRED <").append(RDFS.LABEL).append("> ?LABEL} ");
		query.append("} UNION { ");
		query.append("{?PRED <").append(RDFS.SUBPROPERTYOF).append("> <" + baseRelationshipURI + ">} ");
		query.append("{?SUB ?PRED ?OBJ} ");
		query.append("OPTIONAL{ ?PRED <").append(RDFS.LABEL).append("> ?LABEL} ");
		query.append("{?PROP <").append(RDF.TYPE).append("> <http://semoss.org/ontologies/Relation/Contains>} ");
		query.append("{?PRED ?PROP ?VAL} ");
		query.append("} }");

		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(this.engine, query.toString());
		String[] names = wrapper.getVariables();
		String baseRelationURI = "http://semoss.org/ontologies/Relation";
		while(wrapper.hasNext()) {
			ISelectStatement ss = wrapper.next();
			String subURI = ss.getRawVar(names[0]) + "";
			String predURI = ss.getRawVar(names[1]) + "";
			String objURI = ss.getRawVar(names[2]) + "";
			Object label = ss.getVar(names[3]);
			Object propURI = ss.getRawVar(names[4]);
			Object propVal = ss.getVar(names[5]);

			this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{subURI, predURI, objURI, true});
			this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{subURI, baseRelationshipURI, objURI, true});
			this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{subURI, baseRelationURI, objURI, true});
			// add audit log statement
			Calendar cal = Calendar.getInstance();
			String currTime = DATE_DF.format(cal.getTime());
			addAuditLog(REMOVE, subURI, baseRelationshipURI, objURI, "", "", currTime);
						
			this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{predURI, RDFS.SUBPROPERTYOF, baseRelationshipURI, true});
			this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{predURI, RDFS.SUBPROPERTYOF, baseRelationURI, true});
			this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{predURI, RDF.TYPE, RDF.PROPERTY, true});
			if(label != null && !label.toString().isEmpty()) {
				this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{predURI, RDFS.LABEL, label.toString(), false});
			}
			if(propURI != null && !propURI.toString().isEmpty()) {
				this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{predURI, propURI.toString(), propVal, false});
				// add audit log statement
				currTime = DATE_DF.format(cal.getTime());
				addAuditLog(REMOVE, "", predURI, "", propURI.toString(), propVal + "", currTime);
			}
		}
	}
	
	private void deleteAllRDFConnectionsToConcept(Set<String> uriBindingList) {
		String[] queries = new String[]{
				generateDeleteAllRDFConnectionsToConceptQuery(uriBindingList, true),
				generateDeleteAllRDFConnectionsToConceptQuery(uriBindingList, false)};
		
		for(String query : queries) {
			if(query == null) {
				continue;
			}
			ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(this.engine, query);
			String[] names = wrapper.getVariables();
			while(wrapper.hasNext()) {
				ISelectStatement ss = wrapper.next();
				String subURI = ss.getRawVar(names[0]) + "";
				String predURI = ss.getRawVar(names[1]) + "";
				String objURI = ss.getRawVar(names[2]) + "";
				Object label = ss.getVar(names[3]);
				Object propURI = ss.getRawVar(names[4]);
				Object propVal = ss.getVar(names[5]);
	
				this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{subURI, predURI, objURI, true});
				// add audit log statement
				Calendar cal = Calendar.getInstance();
				String currTime = DATE_DF.format(cal.getTime());
				addAuditLog(REMOVE, subURI, predURI, objURI, "", "", currTime);
				if(label != null && label.toString().isEmpty()) {
					this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{predURI, RDFS.LABEL, label, false});
				}
				if(propURI != null && !propURI.toString().isEmpty()) {
					this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{predURI, propURI, propVal, false});
					// add audit log statement
					cal = Calendar.getInstance();
					currTime = DATE_DF.format(cal.getTime());
					addAuditLog(REMOVE, "", predURI, "", propURI.toString(), propVal + "", currTime);
				}
			}
		}
		
		// lastly, remove the node and all its props
		removeRDFNodeAndAllProps(uriBindingList);
	}

	private String generateDeleteAllRDFConnectionsToConceptQuery(Set<String> conceptURI, boolean downstream) {
		if(conceptURI.isEmpty()) {
			return null;
		}
		StringBuilder query = new StringBuilder("SELECT DISTINCT ?SUB ?PRED ?OBJ ?LABEL ?PROP ?VAL WHERE { ");
		query.append("{ ");
		query.append("{?PRED <").append(RDFS.SUBPROPERTYOF).append("> <http://semoss.org/ontologies/Relation>} ");
		query.append("{?SUB ?PRED ?OBJ} ");
		query.append("OPTIONAL{ ?PRED <").append(RDFS.LABEL).append("> ?LABEL} ");
		query.append("} UNION { ");
		query.append("{?PRED <").append(RDFS.SUBPROPERTYOF).append("> <http://semoss.org/ontologies/Relation>} ");
		query.append("{?SUB ?PRED ?OBJ} ");
		query.append("OPTIONAL{ ?PRED <").append(RDFS.LABEL).append("> ?LABEL} ");
		query.append("{?PROP <").append(RDF.TYPE).append("> <http://semoss.org/ontologies/Relation/Contains>} ");
		query.append("{?PRED ?PROP ?VAL} ");
		query.append("} }");
		if(downstream) {
			query.append("BINDINGS ?SUB {");
		} else {
			query.append("BINDINGS ?OBJ {");
		}
		for(String concept : conceptURI) {
			query.append("(<");
			query.append(concept);
			query.append(">)");
		}
		query.append("}");

		return query.toString();
	}
	
	private void removeRDFNodeAndAllProps(Set<String> uriBindingList) {
		if(uriBindingList.isEmpty()) {
			return;
		}
		
		// delete the properties for the instances
		StringBuilder query = new StringBuilder("SELECT DISTINCT ?NODE ?PROP ?VAL WHERE { ");
		query.append("{?PROP <").append(RDF.TYPE).append("> <http://semoss.org/ontologies/Relation/Contains>} ");
		query.append("{?NODE ?PROP ?VAL} } ");
		query.append("BINDINGS ?NODE {");
		for(String concept : uriBindingList) {
			query.append("(<");
			query.append(concept);
			query.append(">)");
		}
		query.append("}");
		
		ISelectWrapper wrapper = WrapperManager.getInstance().getSWrapper(this.engine, query.toString());
		String[] names = wrapper.getVariables();
		while(wrapper.hasNext()) {
			ISelectStatement ss = wrapper.next();
			String nodeURI = ss.getRawVar(names[0]) + "";
			String propURI = ss.getRawVar(names[1]) + "";
			Object propVal = ss.getVar(names[2]);
			
			this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{nodeURI, propURI, propVal, false});
			// add audit log statement
			Calendar cal = Calendar.getInstance();
			String currTime = DATE_DF.format(cal.getTime());
			addAuditLog(REMOVE, nodeURI, "", "", propURI, propVal + "", currTime);
		}
		
		// deletes the instances
		String semossBaseConcept = "http://semoss.org/ontologies/Concept";
		for(String nodeURI : uriBindingList) {
			String typeURI = semossBaseConcept + "/" + Utility.getClassName(nodeURI);
			this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{nodeURI, RDF.TYPE, typeURI, true});
			// add audit log statement
			Calendar cal = Calendar.getInstance();
			String currTime = DATE_DF.format(cal.getTime());
			addAuditLog(REMOVE, nodeURI, "", "", "", "", currTime);
			this.engine.doAction(IEngine.ACTION_TYPE.REMOVE_STATEMENT, new Object[]{nodeURI, RDFS.LABEL, Utility.getInstanceName(nodeURI), false});
		}
	}

}
