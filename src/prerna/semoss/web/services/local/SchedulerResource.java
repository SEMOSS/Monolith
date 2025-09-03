package prerna.semoss.web.services.local;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import prerna.om.ThreadStore;
import prerna.rpa.config.JobConfigKeys;
import prerna.web.requests.OverrideParametersServletRequest;

@Path("/schedule")
@PermitAll
@Tag(name = "Scheduler", description = "Execute Pixel in scheduler context.")
public class SchedulerResource {

	@POST
	@Path("/executePixel")
	@Produces("application/json")
	@Operation(
		summary = "Execute Pixel (scheduler)",
		description = "Executes a Pixel expression in the scheduler context and returns the result.",
		requestBody = @RequestBody(required = true, content = @Content(mediaType = "application/x-www-form-urlencoded",
			schema = @Schema(implementation = ExecutePixelForm.class))),
		responses = {
			@ApiResponse(responseCode = "200", description = "Pixel executed",
				content = @Content(mediaType = "application/json"))
		}
	)
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
		// do not need this - will invalidate the session
//		if(pixel.endsWith(";")) {
//			pixel = pixel + "DropInsight();";
//		} else {
//			pixel = pixel + ";DropInsight();";
//		}
		pixel = pixel.trim();
		if(!pixel.endsWith(";")) {
			pixel = pixel + ";";
		}
		// set we are scheduler mode
		ThreadStore.setSchedulerMode(true);
		
		NameServer ns = new NameServer();
		OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
		Map<String, String> paramMap = new HashMap<String, String>();
		paramMap.put("expression", pixel);
		requestWrapper.setParameters(paramMap);
		try {
			return ns.runPixelSync(requestWrapper);
		} finally {
			request.getSession().invalidate();
		}
	}
	
}

// DTO for OpenAPI documentation
class ExecutePixelForm {
	@Schema(description = "Pixel expression to execute", required = true)
	public String pixel;
}
