package prerna.semoss.web.services.specific;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.StringTokenizer;
import java.util.ArrayList;
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
	
	String taxClickQuery = "SELECT DISTINCT (CONCAT(REPLACE(STR(?ClientReportingUnit),'^(.*[/])',''),'-',REPLACE(STR(?Study),'^[^_]+(?=_)_','')) AS ?ClientName) ?ClientMetricValue (REPLACE(STR(?PeerGroupDataType),'^(.*[/])','') AS ?PeerDataType) ?PeerGroupValue (CONCAT(SAMPLE(STR(?MetricID)),'+++',SAMPLE(REPLACE(STR(?DisplayDescription),'^(.*[/])',''))) AS ?Name) (SAMPLE(?Title) AS ?Title) (SAMPLE(?Flipped) AS ?Flipped) (SAMPLE(?Grouped) AS ?Grouped) (SAMPLE(?Combination) AS ?Combination) WHERE { BIND(<@PASSED_TAX_URI@> AS ?TaxonomyCategoryHeader) BIND(<@ClientReportingUnit-http://semoss.org/ontologies/Concept/ClientReportingUnit@> AS ?ClientReportingUnit) {?ClientReportingUnit <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/ClientReportingUnit>} {?ClientUniqueID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/ClientUniqueID> } {?MetricID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricID> } {?PeerGroupUniqueID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/PeerGroupUniqueID> } {?PeerGroupDataType <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/PeerGroupDataType> } {?Study <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Study> } {?StudyClientReportingUnit <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/StudyClientReportingUnit> } {?ClientUniqueID <http://semoss.org/ontologies/Relation/IdentifiesAs> ?MetricID } {?ClientReportingUnit <http://semoss.org/ontologies/Relation/ReportsIn> ?StudyClientReportingUnit } {?StudyClientReportingUnit <http://semoss.org/ontologies/Relation/Includes> ?ClientUniqueID } FILTER(?PeerGroupDataType = <@PeerGroupDataType-http://semoss.org/ontologies/Concept/PeerGroupDataType@> || ?PeerGroupDataType = <@PeerGroupDataType2-http://semoss.org/ontologies/Concept/PeerGroupDataType@> ) {?PeerGroupDataType <http://semoss.org/ontologies/Relation/MadeUpOf> ?PeerGroupUniqueID }  {?ChartID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/ChartID> } {?ChartID <http://semoss.org/ontologies/Relation/Has> ?TaxonomyCategoryHeader } {?ChartID <http://semoss.org/ontologies/Relation/Holds> ?MetricID } {?PeerGroupUniqueID <http://semoss.org/ontologies/Relation/BelongsTo> ?MetricID } {?ClientUniqueID <http://semoss.org/ontologies/Relation/Contains/ClientMetricValue> ?ClientMetricValue } {?PeerGroupUniqueID <http://semoss.org/ontologies/Relation/Contains/PeerGroupValue> ?PeerGroupValue } {?Study <http://semoss.org/ontologies/Relation/PartOf> ?StudyClientReportingUnit } {?DisplayDescription <http://semoss.org/ontologies/Relation/DescribesOne> ?MetricID} {?DisplayDescription <http://semoss.org/ontologies/Relation/DescribesA> ?ChartID} {?ChartID <http://semoss.org/ontologies/Relation/Contains/Title> ?Title} {?ChartID <http://semoss.org/ontologies/Relation/Contains/Flipped> ?Flipped} {?ChartID <http://semoss.org/ontologies/Relation/Contains/Grouped> ?Grouped} {?ChartID <http://semoss.org/ontologies/Relation/Contains/Combination> ?Combination} } GROUP BY ?ClientReportingUnit ?Study ?ClientMetricValue ?PeerGroupDataType ?PeerGroupValue  ORDER BY ?ClientName ?Name BINDINGS ?Study {(<@Study-http://semoss.org/ontologies/Concept/Study@>)(<@Study2-http://semoss.org/ontologies/Concept/Study@>)}";
//	String taxCategoryClickQuery = "SELECT DISTINCT (CONCAT(REPLACE(STR(?ClientReportingUnit),'^(.*[/])',''),'-',REPLACE(STR(?Study),'^[^_]+(?=_)_','')) AS ?ClientName) ?ClientMetricValue (REPLACE(STR(?PeerGroupDataType),'^(.*[/])','') AS ?PeerDataType) ?PeerGroupValue (CONCAT(SAMPLE(STR(?MetricID)),'+++',COALESCE(SAMPLE(REPLACE(STR(?TaxonomyCategory),'^(.*[/])','')), SAMPLE(?MetricDescription), SAMPLE(?MetricName), SAMPLE(?MetricID))) AS ?Name) WHERE { BIND(<@PASSED_TAX_URI@> AS ?BoundTaxonomyCategory) BIND(<@ClientReportingUnit-http://semoss.org/ontologies/Concept/ClientReportingUnit@> AS ?ClientReportingUnit)  {?ClientReportingUnit <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/ClientReportingUnit>} {?ClientUniqueID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/ClientUniqueID> } {?MetricID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricID> } {?PeerGroupUniqueID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/PeerGroupUniqueID> } {?PeerGroupDataType <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/PeerGroupDataType> } {?TaxonomyCategoryHeader <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/TaxonomyCategory> } {?TaxonomyCategory <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/TaxonomyCategory> } {?BoundTaxonomyCategory <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/TaxonomyCategory> } {?Study <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/Study> } {?StudyClientReportingUnit <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/StudyClientReportingUnit> }  {?Study <http://semoss.org/ontologies/Relation/PartOf> ?StudyClientReportingUnit } {?TaxonomyCategoryHeader <http://semoss.org/ontologies/Relation/DrillsInto> ?BoundTaxonomyCategory } {?TaxonomyCategoryHeader <http://semoss.org/ontologies/Relation/DrillsInto> ?TaxonomyCategory } {?ClientReportingUnit <http://semoss.org/ontologies/Relation/ReportsIn> ?StudyClientReportingUnit }{?ClientUniqueID <http://semoss.org/ontologies/Relation/Contains/ClientMetricValue> ?ClientMetricValue } {?MetricID <http://semoss.org/ontologies/Relation/Categorized> ?TaxonomyCategory} {?StudyClientReportingUnit <http://semoss.org/ontologies/Relation/Includes> ?ClientUniqueID } {?ClientUniqueID <http://semoss.org/ontologies/Relation/IdentifiesAs> ?MetricID } {?PeerGroupDataType <http://semoss.org/ontologies/Relation/MadeUpOf> ?PeerGroupUniqueID }  FILTER(?PeerGroupDataType = <@PeerGroupDataType-http://semoss.org/ontologies/Concept/PeerGroupDataType@> || ?PeerGroupDataType = <@PeerGroupDataType2-http://semoss.org/ontologies/Concept/PeerGroupDataType@> ){?PeerGroupUniqueID <http://semoss.org/ontologies/Relation/BelongsTo> ?MetricID } {?PeerGroupUniqueID <http://semoss.org/ontologies/Relation/Contains/PeerGroupValue> ?PeerGroupValue } OPTIONAL{?DisplayDescription <http://semoss.org/ontologies/Relation/DescribesOne> ?MetricID} OPTIONAL{?MetricID <http://semoss.org/ontologies/Relation/Contains/MetricDescription> ?MetricDescription} OPTIONAL{ {?MetricID <http://semoss.org/ontologies/Relation/Contains/MetricName> ?MetricName } } } GROUP BY ?ClientReportingUnit ?Study ?ClientMetricValue ?PeerGroupDataType ?PeerGroupValue ORDER BY ?ClientName BINDINGS ?Study {(<@Study-http://semoss.org/ontologies/Concept/Study@>)(<@Study2-http://semoss.org/ontologies/Concept/Study@>)}";	
	String metricDrillDownQuery = "SELECT DISTINCT ?TopMetricID ?ChartID WHERE { {?TopMetricID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricID> } {?ChartID <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/ChartID> } {?TopMetricID <http://semoss.org/ontologies/Relation/BreaksInto> ?ChartID} } BINDINGS ?TopMetricID {@PASSED_METRICIDS@}";	
	String metricFromTaxCatQuery = "SELECT DISTINCT ?entity WHERE { BIND(<@PASSED_TAX_URI@> AS ?BoundTaxonomyCategory) {?BoundTaxonomyCategory <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/TaxonomyCategory> } {?entity <http://semoss.org/ontologies/Relation/Categorized> ?BoundTaxonomyCategory } {?entity <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://semoss.org/ontologies/Concept/MetricID> } }";
	
	public void setEngine(IEngine engine){
		this.coreEngine = engine;
	}

	// for clicking top row in inital grid/
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
		
		String query = taxClickQuery;
		query = query.replace("@PASSED_TAX_URI@", uri);
		
		GBCPresentationPlaySheet playsheet = (GBCPresentationPlaySheet) this.preparePlaySheet(query, presentationClass, "Drilling " + uri, uri, request);		
		playsheet.setPlaySheetClassName(this.comparisonColChartClass);
		
		Hashtable retHash = new Hashtable();
		retHash.put("mainViz", this.runPlaySheet(playsheet, true));
		
		return Response.status(200).entity(getSO(retHash)).build();
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

		String query = taxClickQuery;
		query = query.replace("@PASSED_TAX_URI@", uri);
		
		GBCPresentationPlaySheet playsheet = (GBCPresentationPlaySheet) this.preparePlaySheet(query, presentationClass, "Drilling " + uri, uri, request);		
		playsheet.setPlaySheetClassName(this.comparisonColChartClass);
		
		Hashtable retHash = new Hashtable();
		retHash.put("mainViz", this.runPlaySheet(playsheet, false));
		
		// get the metric id associated with this taxonomy uri
		String entityQuery = this.metricFromTaxCatQuery.replace("@PASSED_TAX_URI@", uri);
		Vector<String> metric = this.coreEngine.getEntityOfType(entityQuery); // this should only return one!!
		if(metric.size()>0){
			Hashtable<String, ArrayList<String>> downstreamGraphs = getDownstreamGraphs(new ArrayList<String>(metric)); // should only be one inner hash as there was only one metric in array
			ArrayList drillData = this.getDownstreamGraphData(downstreamGraphs.get(metric.get(0)), request);
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
		Gson gson = new Gson();
		ArrayList downstreamGraphs = gson.fromJson(form.getFirst("downstreamGraphs"), ArrayList.class);
//		ArrayList<String> groupGraphs = gson.fromJson(form.getFirst("downstreamGroups"), ArrayList.class);
		
		// when clicking specific cell in grid or clicking top formula part of high level bar chart
		
		// 1. use metric break down to get children metrics
		// 2. show bar chart of each one of them
		// 3. if a metric group is returned... throw into metric group query
		
		ArrayList masterList = getDownstreamGraphData(downstreamGraphs, request);
		
		return getSO(masterList);
	}	
	
	private Hashtable<String, ArrayList<String>> getDownstreamGraphs(ArrayList<String> uris){
		// two possibilities for what the downstream graph will be
		// either it will be a specific metric or a metric group that points to a couple of metrics
		// need to keep these separate as the queries will be different
		
		// create bindings
		String bindings = "";
		for(String uri : uris){
			bindings = bindings + "(<" + uri + ">)";
		}
		
		String query = this.metricDrillDownQuery;
		query = query.replace("@PASSED_METRICIDS@", bindings);
		SesameJenaSelectWrapper sjsw = Utility.processQuery(this.coreEngine, query);

		Hashtable<String, ArrayList<String>> masterHash = new Hashtable<String, ArrayList<String>>();
		//instatiate so never return empty
		for(String uri : uris){
			masterHash.put(uri, new ArrayList<String>());
		}
		
		// query returns in the order ?headMetricID ?childMetricID ?childMetricGroup
		String[] names = sjsw.getVariables();
		while (sjsw.hasNext()){
			SesameJenaSelectStatement sjss = sjsw.next();
			String topId = sjss.getRawVar(names[0])+"";
			ArrayList<String> chartArray = masterHash.get(topId);
			if(sjss.getRawVar(names[1]) != null){
				chartArray.add(sjss.getRawVar(names[1])+"");
			}
		}
		return masterHash;
	}
	
	private IPlaySheet preparePlaySheet(String sparql, String playsheet, String title, String id, HttpServletRequest request){

		ExecuteQueryProcessor exQueryProcessor = new ExecuteQueryProcessor();
        exQueryProcessor.prepareQueryOutputPlaySheet(coreEngine, sparql, playsheet, title, id);
        IPlaySheet playSheet= exQueryProcessor.getPlaySheet();
        
        return playSheet;
	}
	
	private Object runPlaySheet(IPlaySheet playSheet, boolean getDownstreamGraphs){
        Object obj = null;
        try {
               PlaysheetCreateRunner playRunner = new PlaysheetCreateRunner(playSheet);
               playRunner.runWeb();
               
               obj = playSheet.getData();
               
               if(getDownstreamGraphs) { 
	               //add downstream graphs so we know what legend items are clickable
		   			ArrayList<String> drilledUris = (ArrayList<String>) ((Hashtable)((Hashtable)obj).get("specificData")).get("seriesList");
		   			Hashtable<String, ArrayList<String>> downstreamGraphs = getDownstreamGraphs(drilledUris);
		   			((Hashtable)obj).put("downstreamGraphs", downstreamGraphs);
               }

        } catch (Exception ex) { //need to specify the different exceptions 
               ex.printStackTrace();
               return new Hashtable<String, String>();
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
	
	private ArrayList getDownstreamGraphData(ArrayList<String> chartArray, HttpServletRequest request){
		
		ArrayList masterList = new ArrayList();
		Hashtable<String, String> passedParams = new Hashtable<String, String>();
		for(String chartId : chartArray){
			String queryKey = "QUAD_COMPARISON_CHART_ID_COL_CHART_QUERY";
			passedParams.put("ChartID", chartId);

			GBCPresentationPlaySheet playsheet = (GBCPresentationPlaySheet) this.preparePlaySheet("", presentationClass, "Drilling " + chartId, chartId, request);		
			playsheet.setPlaySheetClassName(this.comparisonColChartClass);
			playsheet.setGenericQuery(queryKey, passedParams);
			
			Hashtable data = (Hashtable) runPlaySheet(playsheet, true);
			masterList.add(data);
		}

		return masterList;
	}
	
}
