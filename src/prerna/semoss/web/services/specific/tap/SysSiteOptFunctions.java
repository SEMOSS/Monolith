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
import prerna.ui.components.specific.tap.DHMSMDeploymentStrategyPlaySheet;
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
	public StreamingOutput refreshDataRegion(MultivaluedMap<String, String> form, 
            @Context HttpServletRequest request) {
		Hashtable retHash = new Hashtable();
		Gson gson = new Gson();
        Hashtable<String, Object> webDataHash = gson.fromJson(form.getFirst("data"), new TypeToken<Hashtable<String, Object>>() {}.getType());
        //Hashtable retHash = ((SysSiteOptPlaySheet) playsheet).method();
		return WebUtility.getSO(retHash);
	}

}
