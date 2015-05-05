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

import prerna.semoss.web.services.NameServer;
import prerna.ui.components.specific.tap.SysSimHeatMapSheet;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class SysDupFunctions extends AbstractControlClick {

	@Context ServletContext context;
	Logger logger = Logger.getLogger(NameServer.class.getName());

	@POST
	@Path("refresh")
    @Produces("application/json")
	public StreamingOutput refreshData(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {
		Gson gson = new Gson();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
		Hashtable retHash = new Hashtable();
		ArrayList<String> selectedVarsList = (ArrayList<String>) webDataHash.get("selectedVars");
		Hashtable<String, Double> specifiedWeights = gson.fromJson(gson.toJson(webDataHash.get("specifiedWeights")), new TypeToken<Hashtable<String, Double>>() {}.getType());
		ArrayList<Hashtable<String, Hashtable<String, Double>>> calculatedHash = ((SysSimHeatMapSheet) playsheet).calculateHash(selectedVarsList, specifiedWeights);
		ArrayList<Object[]> table = ((SysSimHeatMapSheet) playsheet).flattenData(calculatedHash, false);
		retHash.put("data", table);
		return WebUtility.getSO(retHash);
	}
	
	@POST
	@Path("popover")
    @Produces("application/json")
	public StreamingOutput getSysDupBarData(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {
		Gson gson = new Gson();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
		Hashtable retHash = new Hashtable();
		ArrayList<String> categoryArray = (ArrayList<String>) webDataHash.get("categoryArray");
		Hashtable<String, Double> thresh = gson.fromJson(gson.toJson(webDataHash.get("thresh")), new TypeToken<Hashtable<String, Double>>() {}.getType());
		String cellKey = (String) webDataHash.get("cellKey");
		retHash.put("barData", ((SysSimHeatMapSheet) playsheet).getSimBarChartData(cellKey, categoryArray, thresh));
		return WebUtility.getSO(retHash);
	}
}
