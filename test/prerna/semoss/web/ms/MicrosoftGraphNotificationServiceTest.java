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
package prerna.semoss.web.ms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.utils.SecurityExternalConnectorsUtils;

class MicrosoftGraphNotificationServiceTest {

	private static final String SECRET = "3f1c2b4e-client-state";

	private final MicrosoftGraphNotificationService service = new MicrosoftGraphNotificationService();

	@Test
	void echoesTheValidationTokenAsPlainTextWithoutReadingTheBody() throws IOException {
		HttpServletRequest req = delivery("{}");
		when(req.getParameter("validationToken")).thenReturn("Validation: Token=1&x");
		try (MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			Response response = service.messages(req);

			assertEquals(200, response.getStatus());
			assertEquals("text/plain", response.getMediaType().toString());
			assertEquals("Validation: Token=1&x", response.getEntity());
			verify(req, never()).getInputStream();
			connectors.verifyNoInteractions();
		}
	}

	@Test
	void treatsABlankValidationTokenAsANotification() throws IOException {
		HttpServletRequest req = delivery("{\"value\":[]}");
		when(req.getParameter("validationToken")).thenReturn("  ");

		Response response = service.events(req);

		assertEquals(202, response.getStatus());
		assertEquals("Nothing to do", json(response).get("message").getAsString());
	}

	@Test
	void rejectsABodyThatIsNotAJsonObject() throws IOException {
		for (String body : new String[] { "not json", "[1, 2]", "", "\"text\"" }) {
			Response response = service.messages(delivery(body));
			assertEquals(400, response.getStatus(), body);
			assertEquals("malformed body", json(response).get("reason").getAsString());
		}
	}

	@Test
	void acknowledgesABatchWithNothingInIt() throws IOException {
		for (String body : new String[] { "{}", "{\"value\":[]}" }) {
			Response response = service.messages(delivery(body));
			assertEquals(202, response.getStatus());
			assertEquals("Nothing to do", json(response).get("message").getAsString());
		}
	}

	@Test
	void acceptsOnlyNotificationsCarryingTheirSubscriptionsClientState() throws IOException {
		String body = "{\"value\":[" + notification("sub-1", SECRET) + "," + notification("sub-1", "guessed") + ","
				+ notification("sub-1", null) + "," + notification("sub-1", SECRET.toUpperCase()) + ","
				+ notification("sub-unknown", SECRET) + "," + notification(null, SECRET) + ",42,null]}";
		try (MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			connectors.when(() -> SecurityExternalConnectorsUtils.getMicrosoftGraphSubscription(anyString()))
					.thenReturn(null);
			connectors.when(() -> SecurityExternalConnectorsUtils.getMicrosoftGraphSubscription("sub-1"))
					.thenReturn(row("sub-1", "me/events", SECRET));

			Response response = service.events(delivery(body));

			assertEquals(202, response.getStatus(), "a batch is acknowledged even when parts of it are discarded");
			JsonObject answer = json(response);
			assertEquals("Accepted", answer.get("message").getAsString());
			assertEquals(1, answer.get("count").getAsInt());
		}
	}

	@Test
	void bothReceiversCheckTheClientState() throws IOException {
		try (MockedStatic<SecurityExternalConnectorsUtils> connectors = mockStatic(SecurityExternalConnectorsUtils.class)) {
			connectors.when(() -> SecurityExternalConnectorsUtils.getMicrosoftGraphSubscription("sub-1"))
					.thenReturn(row("sub-1", "me/mailFolders('archive')/messages", SECRET));

			String good = "{\"value\":[" + notification("sub-1", SECRET) + "]}";
			String bad = "{\"value\":[" + notification("sub-1", "nope") + "]}";
			assertEquals(1, json(service.messages(delivery(good))).get("count").getAsInt());
			assertEquals(0, json(service.messages(delivery(bad))).get("count").getAsInt());
			assertEquals(1, json(service.events(delivery(good))).get("count").getAsInt());
			assertEquals(0, json(service.events(delivery(bad))).get("count").getAsInt());
		}
	}

	private static Map<String, Object> row(String id, String resource, String clientState) {
		Map<String, Object> row = new HashMap<>();
		row.put("subscriptionId", id);
		row.put("resource", resource);
		row.put("changeType", "deleted");
		row.put("clientState", clientState);
		row.put("userId", "ms-1");
		row.put("userProvider", "MICROSOFT");
		return row;
	}

	/**
	 * One notification about a deletion, which the receiver acknowledges without
	 * reading anything back from Microsoft.
	 */
	private static String notification(String subscriptionId, String clientState) {
		JsonObject notification = new JsonObject();
		if (subscriptionId != null) {
			notification.addProperty("subscriptionId", subscriptionId);
		}
		if (clientState != null) {
			notification.addProperty("clientState", clientState);
		}
		notification.addProperty("changeType", "deleted");
		JsonObject resourceData = new JsonObject();
		resourceData.addProperty("id", "AAMkAD-1");
		notification.add("resourceData", resourceData);
		return notification.toString();
	}

	private static HttpServletRequest delivery(String body) throws IOException {
		HttpServletRequest req = mock(HttpServletRequest.class);
		ByteArrayInputStream bytes = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
		when(req.getInputStream()).thenReturn(new ServletInputStream() {
			@Override
			public int read() {
				return bytes.read();
			}

			@Override
			public boolean isFinished() {
				return bytes.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
				throw new UnsupportedOperationException();
			}
		});
		return req;
	}

	private static JsonObject json(Response response) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			((StreamingOutput) response.getEntity()).write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return JsonParser.parseString(out.toString(StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
