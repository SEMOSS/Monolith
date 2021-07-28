package prerna.websocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.Session;

public class SocketSessionHandler {

	private List<Session> sessions = new ArrayList<>();

	public void addSession(Session session) {
		sessions.add(session);
	}

	public void removeSession(Session session) {
		sessions.remove(session);
	}

	private void sendReturnData(String message) {
		for(Session session : sessions) {
			try {
				session.getBasicRemote().sendText(message);
			} catch (IOException e) {
				removeSession(session);
			}
		}
	}

	public void updateRecipe(String message) {
		sendReturnData(message);
	}

}
