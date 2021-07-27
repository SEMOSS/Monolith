package prerna.websocket;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.json.JSONObject;

import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.PixelRunner;

@ServerEndpoint("/pixelSocket")
public class PixelWebsocket {

	@OnOpen
	public void open(Session session) {
		SocketSessionHandlerFactory.getHandler().addSession(session);
	}
	
	@OnClose
	public void close(Session session) {
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
		String pixelString = json.getString("pixel");
		
		Insight in = InsightStore.getInstance().get(insightId);
		PixelRunner dataReturn = in.runPixel(pixelString);
		
		SocketSessionHandler handler = SocketSessionHandlerFactory.getHandler();
		handler.updateRecipe(dataReturn);
	}
}
