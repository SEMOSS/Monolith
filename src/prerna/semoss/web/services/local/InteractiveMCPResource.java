package prerna.semoss.web.services.local;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.security.PermitAll;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.mcp.MCPSSEReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.util.Constants;
import prerna.util.Utility;

@Singleton
@Path("/ext/mcp/{toolbox_id}")
@PermitAll
public class InteractiveMCPResource {

    private static final Logger classLogger = LogManager.getLogger(InteractiveMCPResource.class);
    private Map<String, Insight> mcpThread = new HashMap<>();

    @POST
    @Path("/interactive-comms")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void interactiveComms(@PathParam("toolbox_id") String toolbox_id,
            @QueryParam("access_key") String access,
            @Context SseEventSink eventSink,
            @Context Sse sse, InputStream is,
            @Context HttpServletRequest request) {
        classLogger.debug("Starting interactive tool session for toolbox: " + toolbox_id);

        String authorization = request.getHeader("Authorization");
        HttpSession session = request.getSession(true); // always create a session
        String sessionId = session.getId();
        Insight insight = null;
        User user = null;

        // Use a synchronized block to ensure thread-safe access to the map
        synchronized (mcpThread) {
            if (!mcpThread.containsKey(authorization)) {
                insight = initSession(session);
                user = insight.getUser();
                mcpThread.put(authorization, insight);
            } else {
                insight = mcpThread.get(authorization);
                user = insight.getUser();
            }
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        MCPSSEReaper reaper = new MCPSSEReaper(user, insight, sessionId, reader, eventSink, sse, toolbox_id);
        Thread t = new Thread(reaper);
        t.setName("InteractiveMCP-Thread-" + sessionId);
        t.start();
    }

    private Insight initSession(HttpSession session) {
        User user = (User) session.getAttribute(Constants.SESSION_USER);
        String insightId = (String) session.getAttribute(Constants.INSIGHT);
        String sessionId = session.getId();
        Insight insight = null;

        if (insightId == null) {
            Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(sessionId);
            if (sessionInsights == null || sessionInsights.isEmpty()) {
                insight = new Insight();
                InsightStore.getInstance().put(insight);
                insightId = insight.getInsightId();
                InsightStore.getInstance().addToSessionHash(sessionId, insightId);
            } else {
                insightId = sessionInsights.iterator().next();
                insight = InsightStore.getInstance().get(insightId);
            }
            ZoneId zoneId = ZoneId.of(Utility.getApplicationZoneId());
            if(user != null) {
            	user.setZoneId(zoneId);
            }
            session.setAttribute(Constants.INSIGHT, insightId);
        } else {
            insight = InsightStore.getInstance().get(insightId);
        }

        if(insight != null) {
        	insight.setUser(user);
        //	insight.getVarStore().put(JobReactor.JOB_KEY, new NounMetadata(insightId, PixelDataType.CONST_STRING));
        }
        
        return insight;
    }
}
