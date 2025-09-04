package prerna.semoss.web.services.local;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.parameters.RequestBody;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.mcp.MCPReaper;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.util.Constants;
import prerna.util.Utility;

@Singleton
@Path("/ext/mcp/{toolbox_id}")
@PermitAll
@SecurityRequirement(name = "basicAuth")
@Tag(name = "MCP", description = "Endpoints for Model Context Protocol streaming and event communication")
public class MCPResource {

	// MCP remote communication - https://www.npmjs.com/package/mcp-remote

	private static final Logger classLogger = LogManager.getLogger(MCPResource.class);
	private Map<String, Insight> mcpThread = new HashMap<>();

	@POST
	@Path("/it")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(
        summary = "Process insight data and stream incremental plain text output",
        description = "Accepts a JSON payload and returns a streamed plain text response where each line represents incremental processing output.",
        tags = { "MCP" },
        parameters = {
            @Parameter(name="toolbox_id", in = ParameterIn.PATH, required = true,
                       description = "Identifier of the MCP toolbox",
                       schema = @Schema(type="string"))
        },
        requestBody = @RequestBody(
            required = true,
            description = "Arbitrary JSON payload to be processed",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(type="object", description="Input command or data object")
            )
        )
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Streaming text response; each line is an incremental processing result",
            content = @Content(
                mediaType = "text/plain",
                schema = @Schema(type="string", description="Newline-delimited streaming text")
            )
        ),
        @ApiResponse(responseCode = "400", description = "Bad request", content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    public Response getInsightData(
            @RequestBody(
                description = "JSON payload to process",
                required = true,
                content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(type="object")
                )
            )
            InputStream is)
    {
        classLogger.debug("Came into the MCP");
        StreamingOutput stream = output -> {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                // Simulate processing input and generating streamed response
                for (int i = 0; i < 10; i++) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is));                	
                    String outputLine = "Processed: " + reader.readLine() + " - Item: " + i;
                    writer.write(outputLine + "\n");
                    writer.flush();  // Flush after each write to ensure streaming
                    Thread.sleep(500); // Simulate some processing time
                }
            }
            catch (IOException | InterruptedException e) {
                throw new WebApplicationException(e); // Handle exception appropriately
            }
        };
        return Response.ok(stream).build();
    }

	@POST
	@Path("/comms")
	@Produces(MediaType.SERVER_SENT_EVENTS)
	@Operation(
        summary = "Open an SSE channel for MCP tool communications",
        description = "Establishes a Server-Sent Events (SSE) stream used for real-time interaction with a toolbox. The stream emits event data as processing progresses.",
        tags = { "MCP" },
        parameters = {
            @Parameter(name="toolbox_id", in=ParameterIn.PATH, required=true,
                description="Identifier of the MCP toolbox",
                schema=@Schema(type="string")),
            @Parameter(name="access_key", in=ParameterIn.QUERY, required=false,
                description="Optional access key for additional authorization",
                schema=@Schema(type="string")),
            @Parameter(name="Authorization", in=ParameterIn.HEADER, required=false,
                description="Authorization header (e.g., Basic or Bearer token)",
                schema=@Schema(type="string"))
        },
        requestBody = @RequestBody(
            required = false,
            description = "Optional JSON command payload sent at connection start",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(type="object")
            )
        )
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "SSE stream established; events will be pushed until completion or disconnect",
            content = @Content(
                mediaType = "text/event-stream",
                schema = @Schema(
                    type="string",
                    description="Server-Sent Events stream (event: <type>\\n data: <payload>)"
                )
            )
        ),
        @ApiResponse(responseCode = "400", description = "Bad request", content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @ApiResponse(responseCode = "410", description = "Connection closed", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    public void comms(
            @PathParam("toolbox_id")
            @Parameter(description="Toolbox identifier", required=true)
            String toolbox_id, 
            @QueryParam("access_key")
            @Parameter(description="Optional access key")
            String access,
            @Context
            @Parameter(hidden = true)
            SseEventSink eventSink, 
            @Context
            @Parameter(hidden = true)
            Sse sse, 
            @RequestBody(
                description = "Optional JSON command payload at stream initiation",
                required = false,
                content = @Content(mediaType="application/json", schema=@Schema(type="object"))
            )
            InputStream is,
            @Context
            @Parameter(hidden = true)
            HttpServletRequest request) 
    {
        classLogger.debug("Runing tool.. " + toolbox_id);
        // initialize session
        String authorization = request.getHeader("Authorization");
        HttpSession session = request.getSession(false);
        String sessionId = session.getId();
        Insight insight = null;
        User user = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));

        if(!mcpThread.containsKey(authorization))
        {
            insight = initSession(session);
            user = insight.getUser();
            mcpThread.put(authorization, insight);			
        }
        else
        {
            insight = mcpThread.get(authorization);
            user = insight.getUser();
        }
        MCPReaper reaper = new MCPReaper(user, insight, sessionId, reader, eventSink, sse, toolbox_id);
        Thread t = new Thread(reaper);
        t.start();
    }

	/**
	 * 
	 * @param session
	 * @return
	 */
	private Insight initSession(HttpSession session) {
		if(session != null)
		{
			User user = (User) session.getAttribute(Constants.SESSION_USER);
			String insightId = (String) session.getAttribute(Constants.INSIGHT);
			String sessionId = session.getId();		
			Insight insight = null;
			// insight id could be null
			if (insightId == null)
			{
				Set<String> sessionInsights = InsightStore.getInstance().getInsightIDsForSession(sessionId);
				if (sessionInsights == null || sessionInsights.isEmpty()) {
					// need to make a new insight here
					insight = new Insight();
					InsightStore.getInstance().put(insight);
					insightId = insight.getInsightId();
					InsightStore.getInstance().addToSessionHash(sessionId, insightId);
				} else {
					// pull the insight id from the session set
					insightId = sessionInsights.iterator().next();
					insight = InsightStore.getInstance().get(insightId);
				}			
				// get the zone id
				ZoneId zoneId = ZoneId.of(Utility.getApplicationZoneId());;
				user.setZoneId(zoneId);
				session.setAttribute(Constants.INSIGHT, insightId);
			}
			else
			{
				insight = InsightStore.getInstance().get(insightId);				
			}

			// set the user
			insight.setUser(user);		
			return insight;
		}
		return null;
	}

}
