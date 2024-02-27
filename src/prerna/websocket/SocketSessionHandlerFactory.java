package prerna.websocket;

import java.util.concurrent.ConcurrentHashMap;

public class SocketSessionHandlerFactory {

    private static ConcurrentHashMap<String, SocketSessionHandler> sessionHandlers = new ConcurrentHashMap<>();
	
	public static SocketSessionHandler getHandler(String insightId) {
		SocketSessionHandler socketSession = sessionHandlers.get(insightId) ;
		if(socketSession == null) {
			socketSession = new SocketSessionHandler();
			sessionHandlers.put(insightId, socketSession);
		}
		return socketSession;
	}
}
