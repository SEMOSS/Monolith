package prerna.semoss.web.services.local;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import prerna.om.Insight;
import prerna.web.requests.OverrideParametersServletRequest;

public class RunInsight {

	private Insight in = null;
	private boolean drop = false;
	
	public RunInsight(Insight in) {
		this.in = in;
	}

	public void dropInsight(boolean drop) {
		this.drop = drop;
	}
	
	@POST
	@Path("/getTableData")
	@Produces("application/json")
	public Response getInsightData(@Context HttpServletRequest request) {
		String pixel = "QueryAll()|Collect(-1);FrameHeaders();";
		return runPixel(request, pixel);
	}
	
	/**
	 * Utility method to execute the pixel on the insight
	 * @param request
	 * @param pixel
	 * @return
	 */
	private Response runPixel(@Context HttpServletRequest request, String pixel) {
		if(this.drop) {
			if(pixel.endsWith(";")) {
				pixel = pixel + "DropInsight();";
			} else {
				pixel = pixel + ";DropInsight();";
			}
		}
		NameServer ns = new NameServer();
		OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
		Map<String, String> paramMap = new HashMap<String, String>();
		paramMap.put("insightId", in.getInsightId());
		paramMap.put("expression", pixel);
		requestWrapper.setParameters(paramMap);
		return ns.runPixelSync(requestWrapper);
	}
}
