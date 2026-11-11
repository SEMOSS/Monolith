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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.io.connector.ms.MicrosoftGraphSubscriptionClient;
import prerna.io.connector.ms.MicrosoftGraphSubscriptionRegistry;
import prerna.io.connector.ms.MicrosoftGraphSubscriptionRegistry.Subscription;
import prerna.io.connector.ms.MicrosoftLoginUtils;
import prerna.util.Constants;

class MicrosoftGraphSubscriptionServiceTest {

	private static final String MS_ID = "ms-1";
	private static final String NOT_SIGNED_IN = "You are not signed in to Microsoft.";
	private static final String BASE = "https://semoss.example.com/Monolith";

	private final MicrosoftGraphSubscriptionService service = new MicrosoftGraphSubscriptionService();

	@Test
	void availableNeedsASignedInUser() {
		assertEquals(401, service.available(request(null, Map.of())).getStatus());

		User notLoggedIn = mock(User.class);
		Response response = service.available(request(notLoggedIn, Map.of()));
		assertEquals(401, response.getStatus());
		assertEquals("user session is invalid", json(response).get("reason").getAsString());
	}

	@Test
	void availableSaysWhatIsMissingWithoutNamingScopes() {
		User user = microsoftUser(MS_ID);
		try (MockedStatic<MicrosoftGraphSubscriptionClient> client = mockStatic(MicrosoftGraphSubscriptionClient.class)) {
			client.when(MicrosoftGraphSubscriptionClient::publicBaseUrl).thenReturn(BASE);
			client.when(MicrosoftGraphSubscriptionClient::isPubliclyReachable).thenReturn(true);
			client.when(() -> MicrosoftGraphSubscriptionClient.hasScopeFor("me/messages")).thenReturn(true);

			JsonObject ready = json(service.available(request(user, Map.of())));
			assertTrue(ready.get("available").getAsBoolean());
			assertTrue(ready.get("canSubscribeToMail").getAsBoolean());
			assertFalse(ready.get("canSubscribeToEvents").getAsBoolean());
			assertEquals(BASE, ready.get("notificationBaseUrl").getAsString());
			assertEquals(0, ready.getAsJsonArray("reasons").size());

			client.when(MicrosoftGraphSubscriptionClient::isPubliclyReachable).thenReturn(false);
			client.when(() -> MicrosoftGraphSubscriptionClient.hasScopeFor(anyString())).thenReturn(false);
			User nativeOnly = mock(User.class);
			when(nativeOnly.isLoggedIn()).thenReturn(true);
			JsonObject missing = json(service.available(request(nativeOnly, Map.of())));
			assertFalse(missing.get("available").getAsBoolean());
			assertFalse(missing.get("signedIntoMicrosoft").getAsBoolean());
			JsonArray reasons = missing.getAsJsonArray("reasons");
			assertEquals(3, reasons.size());
			assertEquals(NOT_SIGNED_IN, reasons.get(0).getAsString());
			assertTrue(reasons.get(1).getAsString().contains(BASE));
			assertFalse(missing.toString().contains("Mail.Read"), "no scope is named");
		}
	}

	@Test
	void everySubscriptionEndpointNeedsASession() {
		HttpServletRequest anonymous = request(null, Map.of("resource", "me/messages", "subscriptionId", "s1"));
		try (MockedStatic<MicrosoftGraphSubscriptionRegistry> registry = mockStatic(
				MicrosoftGraphSubscriptionRegistry.class)) {
			for (Response response : List.of(service.subscribe(anonymous), service.renew(anonymous),
					service.unsubscribe(anonymous), service.subscriptions(anonymous))) {
				assertEquals(401, response.getStatus());
				assertEquals("user session is invalid", json(response).get("reason").getAsString());
			}
			registry.verifyNoInteractions();
		}
	}

	@Test
	void everySubscriptionEndpointNeedsAMicrosoftLogin() {
		User nativeOnly = mock(User.class);
		when(nativeOnly.isLoggedIn()).thenReturn(true);
		User blankId = microsoftUser("  ");
		try (MockedStatic<MicrosoftGraphSubscriptionRegistry> registry = mockStatic(
				MicrosoftGraphSubscriptionRegistry.class);
				MockedStatic<MicrosoftGraphSubscriptionClient> client = mockStatic(MicrosoftGraphSubscriptionClient.class)) {
			for (User user : List.of(nativeOnly, blankId)) {
				HttpServletRequest req = request(user, Map.of("resource", "me/messages", "subscriptionId", "s1"));
				for (Response response : List.of(service.subscribe(req), service.renew(req), service.unsubscribe(req),
						service.subscriptions(req))) {
					assertEquals(400, response.getStatus());
					assertEquals(NOT_SIGNED_IN, json(response).get("reason").getAsString());
				}
			}
			registry.verifyNoInteractions();
			client.verifyNoInteractions();
		}
	}

	@Test
	void subscribeOnlyTakesAMailboxOrACalendar() {
		User user = microsoftUser(MS_ID);
		try (MockedStatic<MicrosoftGraphSubscriptionClient> client = mockStatic(MicrosoftGraphSubscriptionClient.class)) {
			assertEquals("a resource such as me/messages or me/events is required",
					json(service.subscribe(request(user, Map.of("resource", " ")))).get("reason").getAsString());
			Response drive = service.subscribe(request(user, Map.of("resource", "me/drive/root")));
			assertEquals(400, drive.getStatus());
			client.verify(() -> MicrosoftGraphSubscriptionClient.create(any(), any(), any(), any(), any(), anyInt()),
					never());
		}
	}

	@Test
	void subscribeRecordsTheSecretItGaveMicrosoft() {
		User user = microsoftUser(MS_ID);
		Subscription recorded = subscription(MS_ID, "me/messages");
		when(recorded.describe()).thenReturn(new LinkedHashMap<>(Map.of("subscriptionId", "sub-1")));
		try (MockedStatic<MicrosoftGraphSubscriptionClient> client = mockStatic(MicrosoftGraphSubscriptionClient.class);
				MockedStatic<MicrosoftGraphSubscriptionRegistry> registry = mockStatic(
						MicrosoftGraphSubscriptionRegistry.class);
				MockedStatic<MicrosoftLoginUtils> login = mockStatic(MicrosoftLoginUtils.class)) {
			login.when(() -> MicrosoftLoginUtils.getValidAccessToken(user)).thenReturn("graph-token");
			client.when(() -> MicrosoftGraphSubscriptionClient.notificationUrl(anyString()))
					.thenAnswer(inv -> BASE + inv.getArgument(0));
			client.when(() -> MicrosoftGraphSubscriptionClient.create(eq("graph-token"), anyString(), anyString(),
					anyString(), anyString(), anyInt()))
					.thenReturn(Map.of("id", "sub-1", "expirationDateTime", "2026-11-12T10:00:00Z"));
			registry.when(() -> MicrosoftGraphSubscriptionRegistry.get("sub-1")).thenReturn(recorded);

			Response response = service.subscribe(request(user, Map.of("resource", "me/messages", "minutes", "abc")));

			assertEquals(200, response.getStatus());
			JsonObject body = json(response);
			assertEquals("ok", body.get("status").getAsString());
			assertEquals("sub-1", body.get("subscriptionId").getAsString());

			ArgumentCaptor<String> secret = ArgumentCaptor.forClass(String.class);
			client.verify(() -> MicrosoftGraphSubscriptionClient.create(eq("graph-token"), eq("me/messages"),
					eq("created"), eq(BASE + MicrosoftGraphSubscriptionService.MESSAGES_PATH), secret.capture(),
					eq(0)));
			UUID.fromString(secret.getValue());
			registry.verify(() -> MicrosoftGraphSubscriptionRegistry.register("sub-1", "me/messages", "created",
					secret.getValue(), BASE + MicrosoftGraphSubscriptionService.MESSAGES_PATH,
					Instant.parse("2026-11-12T10:00:00Z"), user));
			assertFalse(body.toString().contains(secret.getValue()), "the secret is not sent back");
		}
	}

	@Test
	void subscribeSendsACalendarToTheEventReceiver() {
		User user = microsoftUser(MS_ID);
		try (MockedStatic<MicrosoftGraphSubscriptionClient> client = mockStatic(MicrosoftGraphSubscriptionClient.class);
				MockedStatic<MicrosoftGraphSubscriptionRegistry> registry = mockStatic(
						MicrosoftGraphSubscriptionRegistry.class);
				MockedStatic<MicrosoftLoginUtils> login = mockStatic(MicrosoftLoginUtils.class)) {
			client.when(() -> MicrosoftGraphSubscriptionClient.notificationUrl(anyString()))
					.thenAnswer(inv -> BASE + inv.getArgument(0));
			client.when(() -> MicrosoftGraphSubscriptionClient.create(any(), any(), any(), any(), any(), anyInt()))
					.thenReturn(Map.of("id", "sub-2"));

			Response response = service.subscribe(request(user,
					Map.of("resource", "me/calendarView", "changeType", "updated", "minutes", " 90 ")));

			assertEquals(500, response.getStatus(), "a subscription that cannot be read back is reported");
			client.verify(() -> MicrosoftGraphSubscriptionClient.create(any(), eq("me/calendarView"), eq("updated"),
					eq(BASE + MicrosoftGraphSubscriptionService.EVENTS_PATH), anyString(), eq(90)));
			registry.verify(() -> MicrosoftGraphSubscriptionRegistry.register(eq("sub-2"), eq("me/calendarView"),
					eq("updated"), anyString(), anyString(), eq(null), eq(user)));
		}
	}

	@Test
	void subscribeTellsARefusalFromAFailure() {
		User user = microsoftUser(MS_ID);
		try (MockedStatic<MicrosoftGraphSubscriptionClient> client = mockStatic(MicrosoftGraphSubscriptionClient.class);
				MockedStatic<MicrosoftGraphSubscriptionRegistry> registry = mockStatic(
						MicrosoftGraphSubscriptionRegistry.class);
				MockedStatic<MicrosoftLoginUtils> login = mockStatic(MicrosoftLoginUtils.class)) {
			HttpServletRequest req = request(user, Map.of("resource", "me/events"));
			client.when(() -> MicrosoftGraphSubscriptionClient.create(any(), any(), any(), any(), any(), anyInt()))
					.thenThrow(new IllegalArgumentException("Subscription validation request failed"));
			Response refused = service.subscribe(req);
			assertEquals(400, refused.getStatus());
			assertEquals("Subscription validation request failed", json(refused).get("reason").getAsString());

			client.when(() -> MicrosoftGraphSubscriptionClient.create(any(), any(), any(), any(), any(), anyInt()))
					.thenThrow(new IOException("connection reset"));
			Response failed = service.subscribe(req);
			assertEquals(500, failed.getStatus());
			assertEquals("unable to create the subscription", json(failed).get("reason").getAsString());
			registry.verify(() -> MicrosoftGraphSubscriptionRegistry.register(any(), any(), any(), any(), any(),
					any(), any()), never());
		}
	}

	@Test
	void renewAndUnsubscribeAnswerSomeoneElsesSubscriptionAsMissing() {
		User user = microsoftUser(MS_ID);
		when(user.getAccessToken(AuthProvider.NATIVE)).thenReturn(token(AuthProvider.NATIVE, "native-1"));
		Subscription theirs = subscription("native-1", "me/messages");
		try (MockedStatic<MicrosoftGraphSubscriptionClient> client = mockStatic(MicrosoftGraphSubscriptionClient.class);
				MockedStatic<MicrosoftGraphSubscriptionRegistry> registry = mockStatic(
						MicrosoftGraphSubscriptionRegistry.class)) {
			registry.when(() -> MicrosoftGraphSubscriptionRegistry.get("sub-9")).thenReturn(theirs);

			for (Response response : List.of(
					service.renew(request(user, Map.of("subscriptionId", "sub-9"))),
					service.unsubscribe(request(user, Map.of("subscriptionId", "sub-9"))),
					service.renew(request(user, Map.of("subscriptionId", "nope"))))) {
				assertEquals(404, response.getStatus());
				assertEquals("no such subscription is recorded", json(response).get("reason").getAsString());
			}
			assertEquals(400, service.renew(request(user, Map.of())).getStatus());
			assertEquals(400, service.unsubscribe(request(user, Map.of("subscriptionId", " "))).getStatus());
			client.verifyNoInteractions();
		}
	}

	@Test
	void renewPushesTheExpiryAndRecordsWhatMicrosoftAgreed() {
		User user = microsoftUser(MS_ID);
		Subscription mine = subscription(MS_ID, "me/messages");
		when(mine.describe()).thenReturn(Map.of("subscriptionId", "sub-1"));
		try (MockedStatic<MicrosoftGraphSubscriptionClient> client = mockStatic(MicrosoftGraphSubscriptionClient.class);
				MockedStatic<MicrosoftGraphSubscriptionRegistry> registry = mockStatic(
						MicrosoftGraphSubscriptionRegistry.class);
				MockedStatic<MicrosoftLoginUtils> login = mockStatic(MicrosoftLoginUtils.class)) {
			registry.when(() -> MicrosoftGraphSubscriptionRegistry.get("sub-1")).thenReturn(mine);
			login.when(() -> MicrosoftLoginUtils.getValidAccessToken(user)).thenReturn("graph-token");
			client.when(() -> MicrosoftGraphSubscriptionClient.renew("graph-token", "sub-1", "me/messages", 60))
					.thenReturn(Map.of("expirationDateTime", "2026-11-13T00:00:00Z"));

			Response response = service.renew(request(user, Map.of("subscriptionId", "sub-1", "minutes", "60")));

			assertEquals(200, response.getStatus());
			assertEquals("ok", json(response).get("status").getAsString());
			Instant expiry = Instant.parse("2026-11-13T00:00:00Z");
			registry.verify(() -> MicrosoftGraphSubscriptionRegistry.renewed("sub-1", expiry));
			verify(mine).setExpiration(expiry);

			client.when(() -> MicrosoftGraphSubscriptionClient.renew(any(), any(), any(), anyInt()))
					.thenThrow(new IOException("gone"));
			assertEquals(500, service.renew(request(user, Map.of("subscriptionId", "sub-1"))).getStatus());
		}
	}

	@Test
	void unsubscribeForgetsASubscriptionOnlyOnceMicrosoftStopsIt() {
		User user = microsoftUser(MS_ID);
		Subscription mine = subscription(MS_ID, "me/messages");
		try (MockedStatic<MicrosoftGraphSubscriptionClient> client = mockStatic(MicrosoftGraphSubscriptionClient.class);
				MockedStatic<MicrosoftGraphSubscriptionRegistry> registry = mockStatic(
						MicrosoftGraphSubscriptionRegistry.class);
				MockedStatic<MicrosoftLoginUtils> login = mockStatic(MicrosoftLoginUtils.class)) {
			registry.when(() -> MicrosoftGraphSubscriptionRegistry.get("sub-1")).thenReturn(mine);
			login.when(() -> MicrosoftLoginUtils.getValidAccessToken(user)).thenReturn("graph-token");
			client.when(() -> MicrosoftGraphSubscriptionClient.delete("graph-token", "sub-1"))
					.thenThrow(new IOException("throttled"));

			assertEquals(500, service.unsubscribe(request(user, Map.of("subscriptionId", "sub-1"))).getStatus());
			registry.verify(() -> MicrosoftGraphSubscriptionRegistry.remove(anyString()), never());

			client.reset();
			Response response = service.unsubscribe(request(user, Map.of("subscriptionId", "sub-1")));
			assertEquals(200, response.getStatus());
			assertTrue(json(response).get("deleted").getAsBoolean());
			client.verify(() -> MicrosoftGraphSubscriptionClient.delete("graph-token", "sub-1"));
			registry.verify(() -> MicrosoftGraphSubscriptionRegistry.remove("sub-1"));
		}
	}

	@Test
	void subscriptionsListsWhatIsRecordedForTheMicrosoftLoginAndWhatMicrosoftHolds() {
		User user = microsoftUser(MS_ID);
		Subscription mine = subscription(MS_ID, "me/messages");
		when(mine.describe()).thenReturn(Map.of("subscriptionId", "sub-1"));
		try (MockedStatic<MicrosoftGraphSubscriptionClient> client = mockStatic(MicrosoftGraphSubscriptionClient.class);
				MockedStatic<MicrosoftGraphSubscriptionRegistry> registry = mockStatic(
						MicrosoftGraphSubscriptionRegistry.class);
				MockedStatic<MicrosoftLoginUtils> login = mockStatic(MicrosoftLoginUtils.class)) {
			registry.when(() -> MicrosoftGraphSubscriptionRegistry.forUser(MS_ID)).thenReturn(List.of(mine));
			login.when(() -> MicrosoftLoginUtils.getValidAccessToken(user)).thenReturn("graph-token");
			client.when(() -> MicrosoftGraphSubscriptionClient.list("graph-token"))
					.thenReturn(List.of(Map.of("id", "sub-1"), Map.of("id", "sub-orphan")));

			JsonObject body = json(service.subscriptions(request(user, Map.of())));
			assertEquals("sub-1",
					body.getAsJsonArray("recorded").get(0).getAsJsonObject().get("subscriptionId").getAsString());
			assertEquals(2, body.getAsJsonArray("atMicrosoft").size());

			login.when(() -> MicrosoftLoginUtils.getValidAccessToken(user))
					.thenThrow(new IllegalStateException("refresh failed"));
			Response response = service.subscriptions(request(user, Map.of()));
			assertEquals(200, response.getStatus());
			JsonObject unavailable = json(response);
			assertEquals(1, unavailable.getAsJsonArray("recorded").size());
			assertTrue(unavailable.get("atMicrosoft").getAsString().startsWith("unavailable"));
		}
	}

	private static User microsoftUser(String microsoftId) {
		User user = mock(User.class);
		when(user.isLoggedIn()).thenReturn(true);
		AccessToken token = token(AuthProvider.MICROSOFT, microsoftId);
		when(user.getAccessToken(AuthProvider.MICROSOFT)).thenReturn(token);
		return user;
	}

	private static AccessToken token(AuthProvider provider, String id) {
		AccessToken token = new AccessToken();
		token.setProvider(provider);
		token.setId(id);
		return token;
	}

	private static Subscription subscription(String userId, String resource) {
		Subscription subscription = mock(Subscription.class);
		when(subscription.getUserId()).thenReturn(userId);
		when(subscription.getResource()).thenReturn(resource);
		return subscription;
	}

	private static HttpServletRequest request(User user, Map<String, String> params) {
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getParameter(anyString())).thenAnswer(inv -> params.get(inv.getArgument(0)));
		if (user != null) {
			HttpSession session = mock(HttpSession.class);
			when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);
			when(req.getSession(false)).thenReturn(session);
		}
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
