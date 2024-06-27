package prerna.websocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.auth.User;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.util.Constants;

@ServerEndpoint(value="/insightSocket", configurator=WSConfigurator.class)
public class InsightWebsocket {

	private static final Logger classLogger = LogManager.getLogger(InsightWebsocket.class);
	private static final String INSIGHT_ID = "INSIGHT_ID";
	
    @OnOpen
    public void onOpen(Session session, EndpointConfig config){
		classLogger.info("Creating new socket session");
		User user = (User) config.getUserProperties().get(Constants.SESSION_USER);
		if (user == null) {
			throw new IllegalAccessError("User session is invalid");
		}

    	Map<String, List<String>> params = session.getRequestParameterMap();
    	List<String> id = params.get("insightId");
    	if(id == null || id.isEmpty()) {
			throw new IllegalAccessError("Must pass in insightId");
    	}
    	String insightId = id.get(0);
    	{
    		Insight in = InsightStore.getInstance().get(insightId);
    		if(in == null) {
    			in = new Insight();
    			in.setInsightId(insightId);
    			InsightStore.getInstance().put(in);
    		}
    	}
		session.getUserProperties().put(Constants.SESSION_USER, user);
		session.getUserProperties().put(INSIGHT_ID, insightId);

		SocketSessionHandlerFactory.getHandler(insightId).addSession(session);
	}
	
	@OnClose
	public void close(Session session) {
		classLogger.info("Closing socket session");
		String insightId = (String) session.getUserProperties().get(INSIGHT_ID);
		SocketSessionHandlerFactory.getHandler(insightId).removeSession(session);
	}
	
	@OnError
	public void onError(Throwable error) {
		throw new RuntimeException(error);
	}
	
	@OnMessage
	public void handleMessage(String message, Session session) {
		User user = (User) session.getUserProperties().get(Constants.SESSION_USER);
		String insightId = (String) session.getUserProperties().get(INSIGHT_ID);

		JSONObject json = new JSONObject(message);
		String pixelString = json.getString("pixel").trim();
		if(!pixelString.endsWith(";")) {
			pixelString = pixelString + ";";
		}
		
		Insight in = null;
		if(insightId == null || (insightId = insightId.trim()).isEmpty()
				|| insightId.equalsIgnoreCase("new")) {
			in = new Insight();
			InsightStore.getInstance().put(in);
		} else {
			in = InsightStore.getInstance().get(insightId);
		}
		// set the user
		in.setUser(user);
				
		PixelRunner runner = in.runPixel(pixelString);
		StreamingOutput streamingOutput = PixelStreamUtility.collectPixelData(runner);
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			streamingOutput.write(baos);
		} catch (WebApplicationException e) {
			classLogger.error(Constants.STACKTRACE, e);
		} catch (IOException e) {
			classLogger.error(Constants.STACKTRACE, e);
		}
		String returnData = new String(baos.toByteArray());
		SocketSessionHandler handler = SocketSessionHandlerFactory.getHandler(insightId);
		handler.updateRecipe(returnData);
	}
}
