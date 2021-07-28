package prerna.websocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

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

import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;

@ServerEndpoint("/pixelSocket")
public class PixelWebsocket {

	private static final Logger logger = LogManager.getLogger(PixelWebsocket.class);
	
	@OnOpen
	public void open(Session session) {
		logger.info("Creating new socket session");
		SocketSessionHandlerFactory.getHandler().addSession(session);
	}
	
	@OnClose
	public void close(Session session) {
		logger.info("Closing socket session");
		SocketSessionHandlerFactory.getHandler().removeSession(session);
	}
	
	@OnError
	public void onError(Throwable error) {
		throw new RuntimeException(error);
	}
	
	@OnMessage
	public void handleMessage(String message, Session session) {
		JSONObject json = new JSONObject(message);
		String insightId = json.getString("insightId");
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
		PixelRunner runner = in.runPixel(pixelString);
		StreamingOutput streamingOutput = PixelStreamUtility.collectPixelData(runner, null);
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			streamingOutput.write(baos);
		} catch (WebApplicationException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		String returnData = new String(baos.toByteArray());

		SocketSessionHandler handler = SocketSessionHandlerFactory.getHandler();
		handler.updateRecipe(returnData);
	}
}
