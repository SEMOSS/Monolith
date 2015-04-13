package prerna.semoss.web.services.specific.tap;

import java.util.ArrayList;
import java.util.Hashtable;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.StreamingOutput;

import org.apache.log4j.Logger;

import prerna.algorithm.impl.DataLatencyPerformer;
import prerna.algorithm.impl.IslandIdentifierProcessor;
import prerna.algorithm.impl.LoopIdentifierProcessor;
import prerna.om.SEMOSSVertex;
import prerna.semoss.web.services.NameServer;
import prerna.ui.components.specific.tap.InterfaceGraphPlaySheet;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class InterfaceNetworkGraphFunctions extends AbstractControlClick {
	@Context ServletContext context;
	Logger logger = Logger.getLogger(NameServer.class.getName());

	@POST
	@Path("loop")
    @Produces("application/json")
	public StreamingOutput runLoopIdentifer(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {
		Gson gson = new Gson();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
        Hashtable retHash = new Hashtable();
        LoopIdentifierProcessor pro = new LoopIdentifierProcessor();
		pro.setGraphDataModel(((InterfaceGraphPlaySheet) playsheet).gdm);
		pro.setPlaySheet((InterfaceGraphPlaySheet) playsheet);	
		pro.executeWeb();
		retHash = pro.getLoopEdges();
		return WebUtility.getSO(retHash);
	}
	
	@POST
	@Path("island")
    @Produces("application/json")
	public StreamingOutput runIslandIdentifier(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {
		Gson gson = new Gson();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
        Hashtable retHash = new Hashtable();
        IslandIdentifierProcessor pro = new IslandIdentifierProcessor();
		if (!(webDataHash.get("selectedNodes") == (null))) {
			ArrayList<Hashtable<String, Object>> nodesArray = gson.fromJson(gson.toJson(webDataHash.get("selectedNodes")), new TypeToken<ArrayList<Hashtable<String, Object>>>() {}.getType());
			SEMOSSVertex[] pickedVertex = new SEMOSSVertex[1];
			pickedVertex[0] = ((InterfaceGraphPlaySheet) playsheet).gdm.getVertStore().get(nodesArray.get(0).get("uri"));
			pro.setSelectedNodes(pickedVertex);
		} else {
			SEMOSSVertex[] pickedVertex = new SEMOSSVertex[]{};
			pro.setSelectedNodes(pickedVertex);
		}
		pro.setGraphDataModel(((InterfaceGraphPlaySheet) playsheet).gdm);			
		pro.setPlaySheet((InterfaceGraphPlaySheet) playsheet);	
		pro.executeWeb();
		retHash = pro.getIslandEdges();
		return WebUtility.getSO(retHash);
	}
	
	@POST
	@Path("datalatency")
    @Produces("application/json")
	public StreamingOutput runDataLatency(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {
		Gson gson = new Gson();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
        Hashtable retHash = new Hashtable();
        SEMOSSVertex[] pickedVertex;
		if (!(webDataHash.get("selectedNodes") == (null))) {
			ArrayList<Hashtable<String, Object>> nodesArray = gson.fromJson(gson.toJson(webDataHash.get("selectedNodes")), new TypeToken<ArrayList<Hashtable<String, Object>>>() {}.getType());
			pickedVertex = new SEMOSSVertex[1];
			pickedVertex[0] = ((InterfaceGraphPlaySheet) playsheet).gdm.getVertStore().get(nodesArray.get(0).get("uri"));
		} else {
			pickedVertex = new SEMOSSVertex[]{};
		}
		DataLatencyPerformer performer = new DataLatencyPerformer((InterfaceGraphPlaySheet) playsheet, pickedVertex);
		double sliderValue = 1000;
		performer.setValue(sliderValue);			
		performer.executeWeb();			
		retHash = performer.getEdgeScores();     
		return WebUtility.getSO(retHash);
	}
}
