/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpSession;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import prerna.auth.User;
import prerna.util.Constants;

class WSConfiguratorTest {

	private static ServerEndpointConfig config(Map<String, Object> properties) {
		ServerEndpointConfig config = mock(ServerEndpointConfig.class);
		when(config.getUserProperties()).thenReturn(properties);
		return config;
	}

	@Test
	void copiesTheSignedInUserFromTheHttpSession() {
		User user = mock(User.class);
		HttpSession session = mock(HttpSession.class);
		when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
		when(session.getId()).thenReturn("http-1");
		HandshakeRequest request = mock(HandshakeRequest.class);
		when(request.getHttpSession()).thenReturn(session);
		Map<String, Object> properties = new HashMap<>();

		new WSConfigurator().modifyHandshake(config(properties), request, mock(HandshakeResponse.class));

		assertSame(user, properties.get(Constants.SESSION_USER));
		assertEquals("http-1", properties.get(WSConfigurator.HTTP_SESSION_ID));
	}

	@Test
	void leavesNoUserWhenTheHandshakeHasNoHttpSession() {
		Map<String, Object> properties = new HashMap<>();

		new WSConfigurator().modifyHandshake(config(properties), mock(HandshakeRequest.class),
				mock(HandshakeResponse.class));

		assertFalse(properties.containsKey(Constants.SESSION_USER));
		assertFalse(properties.containsKey(WSConfigurator.HTTP_SESSION_ID));
	}
}
