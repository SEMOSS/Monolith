package prerna.semoss.web.services.specific;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import prerna.algorithm.impl.SearchMasterDB;
import prerna.om.GraphDataModel;
import prerna.om.SEMOSSVertex;
import prerna.rdf.engine.api.IEngine;
import prerna.rdf.engine.impl.AbstractEngine;
import prerna.rdf.engine.impl.SesameJenaSelectStatement;
import prerna.rdf.engine.impl.SesameJenaSelectWrapper;
import prerna.ui.components.ExecuteQueryProcessor;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.AbstractRDFPlaySheet;
import prerna.ui.components.playsheets.GraphPlaySheet;
import prerna.ui.components.specific.cbp.GBCPresentationPlaySheet;
import prerna.ui.helpers.PlaysheetCreateRunner;
import prerna.util.Constants;
import prerna.util.DIHelper;
import prerna.util.QuestionPlaySheetStore;
import prerna.util.Utility;

public class GBCPlaySheetResource {

	IEngine coreEngine;
	String output = "";
	Logger logger = Logger.getLogger(GBCPlaySheetResource.class.getName());
	String className = GBCPlaySheetResource.class.getName();
	String comparisonColChartClass = "prerna.ui.components.playsheets.ComparisonColumnChartPlaySheet";
	String presentationClass = "prerna.ui.components.specific.cbp.GBCPresentationPlaySheet";
	
	String topGridClickQuery = "SELECT DISTINCT (CONCAT(REPLACE(STR(?ReportingUnit),'^(.*[/])',''),'-',REPLACE(STR(?Study),'^(.*[/])','')) AS ?ClientName) ?ClientMetricValue ?PeerGroupDataType ?PeerGroupValue (CONCAT(STR(?MetricID),'+++',COALESCE(?MetricDescription, ?MetricName, ?MetricID)) AS ?Name) WHERE { BIND(<@PASSED_TAX_HEADER_URI@> AS ?TaxonomyCategoryHeader) BIND(<@ReportingUnit-http://semoss.org/ontologies/Concept/ReportingUnit@> AS ?ReportingUnit) {?ReportingUnit <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/ReportingUnit>} {?ClientUniqueID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/ClientUniqueID> } {?MetricID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricID> } {?PeerGroupUniqueID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/PeerGroupUniqueID> } {?PeerGroupDataType <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/PeerGroupDataType> } {?Study <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Study> } {?StudyReportingUnit <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/StudyReportingUnit> } {?ClientUniqueID <http://semoss.org/ontologies/Relation/IdentifiesAs> ?MetricID } {?ReportingUnit <http://semoss.org/ontologies/Relation/ReportsIn> ?StudyReportingUnit } {?StudyReportingUnit <http://semoss.org/ontologies/Relation/Includes> ?ClientUniqueID } FILTER(?PeerGroupDataType = <@PeerGroupDataType-http://semoss.org/ontologies/Concept/PeerGroupDataType@> || ?PeerGroupDataType = <@PeerGroupDataType2-http://semoss.org/ontologies/Concept/PeerGroupDataType@> ) {?PeerGroupDataType <http://semoss.org/ontologies/Relation/MadeUpOf> ?PeerGroupUniqueID } {?HeaderMetricID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricID> } {?HeaderMetricID <http://semoss.org/ontologies/Relation/Categorized> ?TaxonomyCategoryHeader } {?HeaderMetricID <http://semoss.org/ontologies/Relation/BreaksInto> ?MetricID } {?PeerGroupUniqueID <http://semoss.org/ontologies/Relation/BelongsTo> ?MetricID } {?ClientUniqueID <http://semoss.org/ontologies/Relation/Contains/ClientMetricValue> ?ClientMetricValue } {?PeerGroupUniqueID <http://semoss.org/ontologies/Relation/Contains/PeerGroupValue> ?PeerGroupValue } {?Study <http://semoss.org/ontologies/Relation/PartOf> ?StudyReportingUnit } OPTIONAL{?MetricID <http://semoss.org/ontologies/Relation/Contains/MetricDescription> ?MetricDescription} OPTIONAL{ {?MetricID <http://semoss.org/ontologies/Relation/Contains/MetricName> ?MetricName } } } ORDER BY ?ClientName BINDINGS ?Study {(<@Study-http://semoss.org/ontologies/Concept/Study@>)(<@Study2-http://semoss.org/ontologies/Concept/Study@>)}";
	String taxCategoryClickQuery = "SELECT DISTINCT (CONCAT(REPLACE(STR(?ReportingUnit),'^(.*[/])',''),'-',REPLACE(STR(?Study),'^(.*[/])','')) AS ?ClientName) ?ClientMetricValue ?PeerGroupDataType ?PeerGroupValue (COALESCE(?MetricDescription, ?MetricName, ?MetricID) AS ?Name)  WHERE { BIND(<@PASSED_TAX_URI@> AS ?BoundTaxonomyCategory) BIND(<@ReportingUnit-http://semoss.org/ontologies/Concept/ReportingUnit@> AS ?ReportingUnit)  {?ReportingUnit <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/ReportingUnit>} {?ClientUniqueID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/ClientUniqueID> } {?MetricID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricID> } {?PeerGroupUniqueID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/PeerGroupUniqueID> } {?PeerGroupDataType <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/PeerGroupDataType> } {?TaxonomyCategoryHeader <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/TaxonomyCategory> } {?TaxonomyCategory <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/TaxonomyCategory> } {?BoundTaxonomyCategory <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/TaxonomyCategory> } {?Study <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Study> } {?StudyReportingUnit <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/StudyReportingUnit> }  {?Study <http://semoss.org/ontologies/Relation/PartOf> ?StudyReportingUnit } {?TaxonomyCategoryHeader <http://semoss.org/ontologies/Relation/DrillsInto> ?BoundTaxonomyCategory } {?TaxonomyCategoryHeader <http://semoss.org/ontologies/Relation/DrillsInto> ?TaxonomyCategory } {?ReportingUnit <http://semoss.org/ontologies/Relation/ReportsIn> ?StudyReportingUnit }{?ClientUniqueID <http://semoss.org/ontologies/Relation/Contains/ClientMetricValue> ?ClientMetricValue } {?MetricID <http://semoss.org/ontologies/Relation/Categorized> ?TaxonomyCategory} {?StudyReportingUnit <http://semoss.org/ontologies/Relation/Includes> ?ClientUniqueID } {?ClientUniqueID <http://semoss.org/ontologies/Relation/IdentifiesAs> ?MetricID } {?PeerGroupDataType <http://semoss.org/ontologies/Relation/MadeUpOf> ?PeerGroupUniqueID }  FILTER(?PeerGroupDataType = <@PeerGroupDataType-http://semoss.org/ontologies/Concept/PeerGroupDataType@> || ?PeerGroupDataType = <@PeerGroupDataType2-http://semoss.org/ontologies/Concept/PeerGroupDataType@> ){?PeerGroupUniqueID <http://semoss.org/ontologies/Relation/BelongsTo> ?MetricID } {?PeerGroupUniqueID <http://semoss.org/ontologies/Relation/Contains/PeerGroupValue> ?PeerGroupValue } } ORDER BY ?ClientName BINDINGS ?Study {(<@Study-http://semoss.org/ontologies/Concept/Study@>)(<@Study2-http://semoss.org/ontologies/Concept/Study@>)}";
	String metricDrillDownQuery = "SELECT DISTINCT ?TopMetricID ?MetricID ?MetricGroup WHERE { BIND(<@PASSED_METRICID@> AS ?TopMetricID) {?TopMetricID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricID> } { {?MetricID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricID> } {?TopMetricID <http://semoss.org/ontologies/Relation/BreaksInto> ?MetricID} } UNION { {?MetricGroup <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricGroup> } {?TopMetricID <http://semoss.org/ontologies/Relation/BreaksInto> ?MetricGroup} } }";
	String metricFromTaxCatQuery = "SELECT DISTINCT ?entity WHERE { BIND(<@PASSED_TAX_URI@> AS ?BoundTaxonomyCategory) {?BoundTaxonomyCategory <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/TaxonomyCategory> } {?entity <http://semoss.org/ontologies/Relation/Categorized> ?BoundTaxonomyCategory } {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricID> } }";
	
	public void setEngine(IEngine engine){
		this.coreEngine = engine;
	}

	// for clicking top row in inital grid
	@POST
	@Path("top-grid-click")
	@Produces("application/json")
	public Object registerTopLevelGridClick(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		String uri = form.getFirst("clickedUri");
		
		// when clicking the top row of the grid
		// the logic is:
		// 1. use uri of clicked tax category to get metric ID of tax category
		// 2. use metric break down to get all metrics below that metric (should be LOTO metrics)
		// 3. feed into comparison col chart query and set into comparison col chart
		// 4. let her run
		
		String query = topGridClickQuery;
		query = query.replace("@PASSED_TAX_HEADER_URI@", uri);
		
		GBCPresentationPlaySheet playsheet = (GBCPresentationPlaySheet) this.preparePlaySheet(query, presentationClass, "Drilling " + uri, uri, request);		
		playsheet.setPlaySheetClassName(this.comparisonColChartClass);
		
		return Response.status(200).entity(getSO(this.runPlaySheet(playsheet))).build();
	}	

	// for clicking specific cell in grid or clicking top formula part of high level bar chart
	@POST
	@Path("lower-tax-click")
	@Produces("application/json")
	public Object registerLowerTaxCatClick(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		String uri = form.getFirst("clickedUri");
		
		// when clicking specific cell in grid or clicking top formula part of high level bar chart
		// the logic is:
		// 1. use uri of clicked tax category to get all neighboring tax categories (go up to highest level then down to all direct children)
		// 2. get metrics of neighboring tax categories
		// 3. feed into comparison col chart query and set into comparison col chart
		// 4. let her run
		// 5. ALSO need children graphs... 
		// 6. get metric id of clicked tax category 
		// set into other function........
		// 7. use metric break down to get children metrics
		// 8. show bar chart of each one of them

		String query = taxCategoryClickQuery;
		query = query.replace("@PASSED_TAX_URI@", uri);
		
		GBCPresentationPlaySheet playsheet = (GBCPresentationPlaySheet) this.preparePlaySheet(query, presentationClass, "Drilling " + uri, uri, request);		
		playsheet.setPlaySheetClassName(this.comparisonColChartClass);
		
		Hashtable retHash = new Hashtable();
		retHash.put("mainViz", this.runPlaySheet(playsheet));
		
		// get the metric id associated with this taxonomy uri
		String entityQuery = this.metricFromTaxCatQuery.replace("@PASSED_TAX_URI@", uri);
		Vector<String> metric = this.coreEngine.getEntityOfType(entityQuery); // this should only return one!!
		if(metric.size()>0){
			ArrayList drillData = this.getDownstreamGraphData(metric.get(0), request);
			retHash.put("drillViz", drillData);
		}
		
		return getSO(retHash);
	}	

	// for any subsequent drill downs once metric ids are known
	@POST
	@Path("metric-click")
	@Produces("application/json")
	public Object registerMetricClick(MultivaluedMap<String, String> form, @Context HttpServletRequest request)
	{
		String uri = form.getFirst("clickedUri");
		
		// when clicking specific cell in grid or clicking top formula part of high level bar chart
		
		// 1. use metric break down to get children metrics
		// 2. show bar chart of each one of them
		// 3. if a metric group is returned... throw into metric group query
		
		ArrayList masterList = getDownstreamGraphData(uri, request);
		
		return getSO(masterList);
	}	
	
	private Hashtable<String, ArrayList<String>> getDownstreamGraphs(String uri){
		ArrayList<String> metricGraphs = new ArrayList<String>();
		ArrayList<String> groupGraphs = new ArrayList<String>();
		
		// two possibilities for what the downstream graph will be
		// either it will be a specific metric or a metric group that points to a couple of metrics
		// need to keep these separate as the queries will be different
		
		String query = this.metricDrillDownQuery;
		query = query.replace("@PASSED_METRICID@", uri);
		SesameJenaSelectWrapper sjsw = Utility.processQuery(this.coreEngine, query);
		
		// query returns in the order ?headMetricID ?childMetricID ?childMetricGroup
		String[] names = sjsw.getVariables();
		while (sjsw.hasNext()){
			SesameJenaSelectStatement sjss = sjsw.next();
			if(sjss.getRawVar(names[1]) != null){
				metricGraphs.add(sjss.getRawVar(names[1])+"");
			}
			else if(sjss.getRawVar(names[2]) != null){
				groupGraphs.add(sjss.getRawVar(names[2])+"");
			}
		}
		Hashtable<String, ArrayList<String>> retHash = new Hashtable<String, ArrayList<String>>();
		retHash.put("metricGraphs", metricGraphs);
		retHash.put("groupGraphs", groupGraphs);
		return retHash;
	}
	
	private IPlaySheet preparePlaySheet(String sparql, String playsheet, String title, String id, HttpServletRequest request){

		ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
        exQueryProcessor.prepareQueryOutputPlaySheet(coreEngine, sparql, playsheet, title, id);
        IPlaySheet playSheet= exQueryProcessor.getPlaySheet();
        
        return playSheet;
	}
	
	private Object runPlaySheet(IPlaySheet playSheet){
        Object obj = null;
        try {
               PlaysheetCreateRunner playRunner = new PlaysheetCreateRunner(playSheet);
               playRunner.runWeb();
               
               obj = playSheet.getData();

        } catch (Exception ex) { //need to specify the different exceptions 
               ex.printStackTrace();
               Hashtable<String, String> errorHash = new Hashtable<String, String>();
               errorHash.put("Message", "Error occured processing question.");
               errorHash.put("Class", className);
               return Response.status(500).entity(getSO(errorHash)).build();
        }
        
        return obj;
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
	
	private ArrayList getDownstreamGraphData(String uri, HttpServletRequest request){
		Hashtable<String, ArrayList<String>> downstreamGraphs = getDownstreamGraphs(uri);
		
		ArrayList masterList = new ArrayList();
		Hashtable<String, String> passedParams = new Hashtable<String, String>();
		
		ArrayList<String> metricGraphs = downstreamGraphs.get("metricGraphs");
		for(String metricUri : metricGraphs){
			String queryKey = "QUAD_COMPARISON_COL_CHART_QUERY";
			passedParams.put("MetricID", metricUri);

			GBCPresentationPlaySheet playsheet = (GBCPresentationPlaySheet) this.preparePlaySheet("", presentationClass, "Drilling " + uri, uri, request);		
			playsheet.setPlaySheetClassName(this.comparisonColChartClass);
			playsheet.setGenericQuery(queryKey, passedParams);
			
			masterList.add(runPlaySheet(playsheet));
		}
		
		ArrayList<String> groupGraphs = downstreamGraphs.get("groupGraphs");
		for(String groupUri : groupGraphs) {
			String queryKey = "GROUP_QUAD_COMPARISON_COL_CHART_QUERY";
			passedParams.put("MetricGroup", groupUri);

			GBCPresentationPlaySheet playsheet = (GBCPresentationPlaySheet) this.preparePlaySheet("", presentationClass, "Drilling " + uri, uri, request);		
			playsheet.setPlaySheetClassName(this.comparisonColChartClass);
			playsheet.setGenericQuery(queryKey, passedParams);
			
			masterList.add(runPlaySheet(playsheet));
		}
		return masterList;
	}
	
}
