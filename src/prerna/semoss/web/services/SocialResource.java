package prerna.semoss.web.services;

import java.util.HashMap;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;

import prerna.auth.User;
import prerna.nameserver.NameServerProcessor;
import prerna.util.Constants;
import prerna.web.services.util.WebUtility;

@Path("social")
public class SocialResource {
	Logger logger = Logger.getLogger(SocialResource.class.getName());
	
	/**
	 * Retrieve top executed insights.
	 * 
	 * @param engine	Optional, engine to restrict query to
	 * @param limit		Optional, number of top insights to retrieve, default is 6
	 * @param request	HttpServletRequest object
	 * 
	 * @return			Top insights and total execution count for all to be used for normalization of popularity
	 */
//	@GET
//	@Path("/topinsights")
//	@Produces("application/json")
//	public Response getTopInsights(@QueryParam("engine") String engine, @QueryParam("limit") String limit, @Context HttpServletRequest request) {
//		if(engine == null) {
//			engine = "";
//		}
//		if(limit == null) {
//			//Default limit for # of top insights to return
//			limit = "6";
//		}
//		
//		NameServerProcessor ns = new NameServerProcessor();
//		HashMap<String, Object> insights = ns.getTopInsights(engine, limit);
//		
//		return Response.status(200).entity(WebUtility.getSO(insights)).build();
//	}
	
	/**
	 * Retrieve top executed insights.
	 * 
	 * @param engine	Optional, engine to restrict query to
	 * @param limit		Optional, number of top insights to retrieve, default is 6
	 * @param request	HttpServletRequest object
	 * 
	 * @return			Top insights and total execution count for all to be used for normalization of popularity
	 */
//	@GET
//	@Path("/feedInsights")
//	@Produces("application/json")
//	public Response getInsightsForFeed(@QueryParam("visibility") String visibility, @QueryParam("limit") String limit, @Context HttpServletRequest request) {
//		User user = (User) request.getSession().getAttribute(Constants.SESSION_USER);
//		if(visibility == null) {
//			visibility = "friends";
//		}
//		if(limit == null) {
//			//Default limit for # of published insights to return
//			limit = "50";
//		}
//		
//		NameServerProcessor ns = new NameServerProcessor();
//		HashMap<String, Object> insights = ns.getFeedInsights(user.getId(), visibility, limit);
//		
//		return Response.status(200).entity(WebUtility.getSO(insights)).build();
//	}
}
