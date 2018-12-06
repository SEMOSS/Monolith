package prerna.semoss.web.services.local;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import prerna.om.Insight;
import prerna.web.requests.OverrideParametersServletRequest;

public class ShareInsight {

	private Insight in;
	
	public ShareInsight(Insight in) {
		this.in = in;
	}

	@GET
	@Path("/getTableData")
	@Produces("application/json")
	public Response getInsightData(@Context HttpServletRequest request) {
		String pixel = "QueryAll()|Collect(-1);FrameHeaders();";
		
		NameServer ns = new NameServer();
		OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
		Map<String, String> paramMap = new HashMap<String, String>();
		paramMap.put("insightId", in.getInsightId());
		paramMap.put("expression", pixel);
		requestWrapper.setParameters(paramMap);
		return ns.runPixelSync(requestWrapper);
	}
	
}
