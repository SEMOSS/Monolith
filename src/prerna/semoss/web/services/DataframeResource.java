package prerna.semoss.web.services;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;

import com.google.gson.Gson;

import prerna.algorithm.api.ITableDataFrame;
import prerna.ds.BTreeDataFrame;
import prerna.ds.Probablaster;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.ui.components.playsheets.datamakers.IDataMaker;
import prerna.web.services.util.WebUtility;

public class DataframeResource {
	Logger logger = Logger.getLogger(DataframeResource.class.getName());
	Insight insight = null;
	/**
	 * Retrieve top executed insights.
	 * 
	 * @param engine	Optional, engine to restrict query to
	 * @param limit		Optional, number of top insights to retrieve, default is 6
	 * @param request	HttpServletRequest object
	 * 
	 * @return			Top insights and total execution count for all to be used for normalization of popularity
	 */
	@GET
	@Path("bic")
	@Produces("application/json")
	public Response runBIC(@Context HttpServletRequest request) {
		
		System.out.println("Failed.. after here.. ");
		String insights = "Mysterious";
		if(insight != null)
		{
			System.out.println("Running basic checks.. ");
			IDataMaker maker = insight.getDataMaker();
			if(maker instanceof BTreeDataFrame)
			{
				System.out.println("Hit the second bogie.. ");
				BTreeDataFrame daFrame = (BTreeDataFrame)maker;
				Probablaster pb = new Probablaster();
				pb.setDataFrame(daFrame);
				pb.runBIC();
			}
			insights = "Ok.. came here.. all good";
		}
		
		
		return Response.status(200).entity(WebUtility.getSO(insights)).build();
	}
	
	
	@GET
	@Path("/getFilterModel")
	@Produces("application/json")
	public Response getFilterModel(MultivaluedMap<String, String> form,
			@Context HttpServletRequest request)
	{	
		String insightID = form.getFirst("insightID");

		Insight existingInsight = null;
		if(insightID != null && !insightID.isEmpty()) {
			existingInsight = InsightStore.getInstance().get(insightID);
			if(existingInsight == null) {
				Map<String, String> errorHash = new HashMap<String, String>();
				errorHash.put("errorMessage", "Existing insight based on passed insightID is not found");
				return Response.status(400).entity(WebUtility.getSO(errorHash)).build();
			}
		}

		ITableDataFrame mainTree = (ITableDataFrame) existingInsight.getDataMaker();
		if(mainTree == null) {
			return Response.status(400).entity(WebUtility.getSO("table not found for insight id. Data not found")).build();
		}
		
		if(!(mainTree instanceof BTreeDataFrame)) {
			return Response.status(400).entity(WebUtility.getSO("table not instance of BTreeDataFrame, cannot grab filter model")).build();
		}

		Gson gson = new Gson();

		Map<String, Object> retMap = new HashMap<String, Object>();

		Object[] returnFilterModel = ((BTreeDataFrame)mainTree).getFilterModel();
		((BTreeDataFrame)mainTree).printTree();
		retMap.put("unfilteredValues", returnFilterModel[0]);
		retMap.put("filteredValues", returnFilterModel[1]);
		
		return Response.status(200).entity(WebUtility.getSO(retMap)).build();
	}
}
