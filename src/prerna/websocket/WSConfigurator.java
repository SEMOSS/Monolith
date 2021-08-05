package prerna.websocket;

import javax.servlet.http.HttpSession;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.ServerEndpointConfig.Configurator;

import prerna.util.Constants;

public class WSConfigurator extends Configurator {

	@Override
	public void modifyHandshake(ServerEndpointConfig sec,
			HandshakeRequest request,
			HandshakeResponse resp) {
		HttpSession session = (HttpSession) request.getHttpSession();
		if(session != null) {
			sec.getUserProperties().put(Constants.SESSION_USER, session.getAttribute(Constants.SESSION_USER));
		}
	}

}
