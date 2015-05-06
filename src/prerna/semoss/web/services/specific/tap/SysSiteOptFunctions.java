package prerna.semoss.web.services.specific.tap;

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
import prerna.ui.components.specific.tap.SysSiteOptPlaySheet;
import prerna.web.services.util.WebUtility;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class SysSiteOptFunctions extends AbstractControlClick {
	@Context ServletContext context;
	Logger logger = Logger.getLogger(NameServer.class.getName());

	@POST
	@Path("update")
    @Produces("application/json")
	public StreamingOutput updateSystemListData(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {

		Gson gson = new Gson();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
        Hashtable retHash = ((SysSiteOptPlaySheet) playsheet).getSystems(webDataHash);
		return WebUtility.getSO(retHash);
	}
	
	@POST
	@Path("runopt")
    @Produces("application/json")
	public StreamingOutput runSysSiteOptimization(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {
		Gson gson = new Gson();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
        Hashtable retHash = ((SysSiteOptPlaySheet) playsheet).runOpt(webDataHash);
		return WebUtility.getSO(retHash);
	}
	
	@POST
	@Path("overview")
    @Produces("application/json")
	public StreamingOutput getOverviewPageData(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {
		Gson gson = new Gson();
		Hashtable retHash = new Hashtable();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
        String type = (String) webDataHash.get("type");
        if (type.equals("info"))
        	retHash = ((SysSiteOptPlaySheet) playsheet).getOverviewInfoData();
        if (type.equals("cost"))
        	retHash = ((SysSiteOptPlaySheet) playsheet).getOverviewCostData();
        if (type.equals("map"))
        	retHash = ((SysSiteOptPlaySheet) playsheet).getOverviewSiteMapData("");
        if (type.equals("healthGrid"))
        	retHash = ((SysSiteOptPlaySheet) playsheet).getHealthGrid("");
		return WebUtility.getSO(retHash);
	}
	
	@POST
	@Path("capability")
    @Produces("application/json")
	public StreamingOutput getCapabilityPageData(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {
		Gson gson = new Gson();
		Hashtable retHash = new Hashtable();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
        String type = (String) webDataHash.get("type");
        String capability = (String) webDataHash.get("cap");
        if (type.equals("info"))
        	retHash = ((SysSiteOptPlaySheet) playsheet).getCapabilityInfoData(capability);
        if (type.equals("map"))
        	retHash = ((SysSiteOptPlaySheet) playsheet).getOverviewSiteMapData(capability);
        if (type.equals("coverage"))
        	retHash = ((SysSiteOptPlaySheet) playsheet).getCapabilityCoverageData(capability);
		return WebUtility.getSO(retHash);
	}
	
	@POST
	@Path("system")
    @Produces("application/json")
	public StreamingOutput getSustainedPageData(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {
		Gson gson = new Gson();
		Hashtable retHash = new Hashtable();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
        String type = (String) webDataHash.get("type");
        String system = (String) webDataHash.get("system");
        String ind = (String) webDataHash.get("ind");
        if (type.equals("info"))
        	retHash = ((SysSiteOptPlaySheet) playsheet).getSystemInfoData(system, true);
        if (type.equals("map"))
        	retHash = ((SysSiteOptPlaySheet) playsheet).getSystemSiteMapData(system, true);
        if (type.equals("coverage"))
        	retHash = ((SysSiteOptPlaySheet) playsheet).getSystemCoverageData(system, true);
		return WebUtility.getSO(retHash);
	}


}
