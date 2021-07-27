package prerna.websocket;

public class SocketSessionHandlerFactory {

	private static SocketSessionHandler handler;
	
	public static SocketSessionHandler getHandler() {
		if(handler == null) {
			handler = new SocketSessionHandler();
		}
		return handler;
	}
}
