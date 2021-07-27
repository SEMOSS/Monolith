package prerna.websocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.websocket.Session;

import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;

public class SocketSessionHandler {

	private List<Session> sessions = new ArrayList<>();

	public void addSession(Session session) {
		sessions.add(session);
	}

	public void removeSession(Session session) {
		sessions.remove(session);
	}

	private void sendReturnData(PixelRunner runner) {
		String message = PixelStreamUtility.collectPixelData(runner, null).toString();
		for(Session session : sessions) {
			try {
				session.getBasicRemote().sendText(message);
			} catch (IOException e) {
				removeSession(session);
			}
		}
	}

	public void updateRecipe(PixelRunner runner) {
		sendReturnData(runner);
	}

}
