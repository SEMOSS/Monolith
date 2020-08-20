package prerna.semoss.web.services.local;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import prerna.rpa.config.JobConfigKeys;
import prerna.web.requests.OverrideParametersServletRequest;

@Path("/schedule")
public class SchedulerResource {

	@POST
	@Path("/executePixel")
	@Produces("application/json")
	public Response executePixel(@Context HttpServletRequest request) {
		// we will flush the user object inside
		// and make sure the 
		String pixel = request.getParameter(JobConfigKeys.PIXEL);
		return runPixel(request, pixel);
	}
	
	/**
	 * Utility method to execute the pixel on the insight
	 * @param request
	 * @param pixel
	 * @return
	 */
	private Response runPixel(@Context HttpServletRequest request, String pixel) {
		if(pixel.endsWith(";")) {
			pixel = pixel + "DropInsight();";
		} else {
			pixel = pixel + ";DropInsight();";
		}
		
		NameServer ns = new NameServer();
		OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
		Map<String, String> paramMap = new HashMap<String, String>();
		paramMap.put("expression", pixel);
		requestWrapper.setParameters(paramMap);
		return ns.runPixelSync(requestWrapper);
	}
	
	
}