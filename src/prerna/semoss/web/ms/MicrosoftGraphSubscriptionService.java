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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.io.connector.ms.MicrosoftGraphSubscriptionClient;
import prerna.io.connector.ms.MicrosoftGraphSubscriptionRegistry;
import prerna.io.connector.ms.MicrosoftGraphSubscriptionRegistry.Subscription;
import prerna.io.connector.ms.MicrosoftLoginUtils;
import prerna.semoss.web.app.MicrosoftGraphApplication;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.web.services.util.WebUtility;

/**
 * The Microsoft Graph change notification lifecycle, served under
 * {@code /msgraph} (see {@link MicrosoftGraphApplication}).
 * <p>
 * Subscribing is something a signed in user does for their own data: the
 * subscription is created with their delegated token, so Graph records them as
 * its creator and it can only ever watch what they can already read. Every
 * endpoint here therefore needs a session, which is what separates them from
 * the receivers in {@link MicrosoftGraphNotificationService}.
 * <p>
 * A session is not enough on its own: everything here is keyed on the person's
 * <em>Microsoft</em> login rather than on whichever login they signed in with,
 * since somebody can sign in natively and link Microsoft alongside it. Anyone
 * without a Microsoft login is turned away rather than filed under an id that
 * would mean nothing to Graph.
 * <p>
 * Two things are worth knowing before calling {@code /subscribe}. Graph posts a
 * validation token to the notification url and refuses to create anything
 * unless it is echoed back, so this instance has to already be reachable at a
 * public https url: locally that means a tunnel, named in the
 * {@code MS_PUBLIC_ORIGIN_OVERRIDE} property. And a subscription expires within
 * days at most, so something has to call {@code /renew} before it does. Nothing
 * renews on its own.
 */
@Path("/")
public class MicrosoftGraphSubscriptionService {

	private static final Logger classLogger = LogManager.getLogger(MicrosoftGraphSubscriptionService.class);

	/** Where a notification about a mailbox is posted. */
	public static final String MESSAGES_PATH = "/msgraph/notifications/messages";

	/** Where a notification about a calendar is posted. */
	public static final String EVENTS_PATH = "/msgraph/notifications/events";

	private static final String RESOURCE = "resource";
	private static final String STATUS = "status";
	private static final String ERROR = "error";
	private static final String REASON = "reason";

	/**
	 * Whether this deployment can subscribe to anything at all, and for whom.
	 * <p>
	 * {@code GET /msgraph/available}
	 * <p>
	 * A preflight so a screen can show the real state instead of a button that
	 * fails. Three separate things have to be true and each fails differently: the
	 * signed in user has to have a Microsoft login, the deployment has to request
	 * a scope that permits subscribing, and Graph has to be able to reach this
	 * deployment over public https to run its validation handshake. The last two
	 * are the administrator's to fix, so they come back as reasons rather than as
	 * a bare false.
	 * <p>
	 * The scope answer is what this deployment <em>asks</em> for at sign in. It
	 * cannot see what the tenant consented to, so {@code available: true} means
	 * nothing is obviously wrong rather than that Graph will agree.
	 *
	 * @param req the request, carrying the session
	 * @return what is and is not in place
	 */
	@GET
	@Path("/available")
	public Response available(@Context HttpServletRequest req) {
		User user = null;
		try {
			user = ResourceUtility.getUser(req);
		} catch (IllegalAccessException e) {
			classLogger.debug("Microsoft Graph availability was asked for without a session.", e);
		}

		boolean signedIntoMicrosoft = user != null && user.getAccessToken(AuthProvider.MICROSOFT) != null;
		boolean reachable = MicrosoftGraphSubscriptionClient.isPubliclyReachable();
		boolean mailScope = MicrosoftGraphSubscriptionClient.hasScopeFor("me/messages");
		boolean eventScope = MicrosoftGraphSubscriptionClient.hasScopeFor("me/events");

		List<String> reasons = new ArrayList<>();
		if (!signedIntoMicrosoft) {
			reasons.add("You are not signed in to Microsoft.");
		}
		if (!reachable) {
			reasons.add("Microsoft cannot reach this deployment. A subscription is only created after Microsoft "
					+ "posts a validation request to a public https address, and this one answers at "
					+ MicrosoftGraphSubscriptionClient.publicBaseUrl() + ".");
		}
		if (!mailScope && !eventScope) {
			reasons.add("This deployment does not request a Microsoft permission that allows subscribing. An "
					+ "administrator needs to add " + MicrosoftGraphSubscriptionClient.acceptableScopes("me/messages")
					+ " or " + MicrosoftGraphSubscriptionClient.acceptableScopes("me/events")
					+ " to the ms_scope property, after which everyone has to sign in to Microsoft again.");
		}

		Map<String, Object> output = new LinkedHashMap<>();
		output.put(STATUS, "ok");
		// available means a subscription could be attempted, not that Graph will
		// agree: only Graph knows what the tenant consented to
		output.put("available", signedIntoMicrosoft && reachable && (mailScope || eventScope));
		output.put("signedIntoMicrosoft", signedIntoMicrosoft);
		output.put("publiclyReachable", reachable);
		output.put("notificationBaseUrl", MicrosoftGraphSubscriptionClient.publicBaseUrl());
		output.put("canSubscribeToMail", mailScope);
		output.put("canSubscribeToEvents", eventScope);
		output.put("reasons", reasons);
		return WebUtility.getResponse(output, 200);
	}

	/**
	 * Starts a subscription for the signed in user.
	 * <p>
	 * {@code POST /msgraph/subscribe?resource=me/messages&changeType=created}
	 * <p>
	 * The receiver is chosen from the resource rather than passed: a mailbox goes
	 * to the message receiver and a calendar to the event one, and nothing else is
	 * accepted, because a notification nobody can receive is worse than a refusal.
	 *
	 * @param req the request, carrying the session whose user this is for
	 * @return the subscription, including the id that renewing and stopping take
	 */
	@POST
	@Path("/subscribe")
	public Response subscribe(@Context HttpServletRequest req) {
		User user;
		try {
			user = ResourceUtility.getUser(req);
		} catch (IllegalAccessException e) {
			classLogger.warn("A Microsoft Graph subscription was asked for without a session.", e);
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "user session is invalid"), 401);
		}

		Response noMicrosoft = requireMicrosoftLogin(user);
		if (noMicrosoft != null) {
			return noMicrosoft;
		}

		String resource = trimToNull(req.getParameter(RESOURCE));
		if (resource == null) {
			return WebUtility.getResponse(
					Map.of(STATUS, ERROR, REASON, "a resource such as me/messages or me/events is required"), 400);
		}
		String notificationPath = notificationPathFor(resource);
		if (notificationPath == null) {
			return WebUtility.getResponse(
					Map.of(STATUS, ERROR, REASON,
							"only a mailbox or a calendar can be subscribed to here, such as me/messages or me/events"),
					400);
		}

		// created is what somebody watching a mailbox almost always means, and
		// asking for all three is a lot of notifications to be surprised by
		String changeType = trimToNull(req.getParameter("changeType"));
		if (changeType == null) {
			changeType = "created";
		}
		int minutes = optionalInt(req.getParameter("minutes"));

		try {
			String accessToken = MicrosoftLoginUtils.getValidAccessToken(user);
			String notificationUrl = MicrosoftGraphSubscriptionClient.notificationUrl(notificationPath);
			// the secret Graph echoes back, made per subscription so one leaking says
			// nothing about the others
			String clientState = UUID.randomUUID().toString();

			Map<String, Object> created = MicrosoftGraphSubscriptionClient.create(accessToken, resource, changeType,
					notificationUrl, clientState, minutes);
			String subscriptionId = String.valueOf(created.get("id"));

			// recorded in the security database rather than in this container's
			// memory, because the container that receives the notifications is
			// whichever one the load balancer picks
			MicrosoftGraphSubscriptionRegistry.register(subscriptionId, resource, changeType, clientState,
					notificationUrl, expirationOf(created), user);

			// read back, so what is answered is what was actually persisted rather
			// than what was about to be
			Subscription subscription = MicrosoftGraphSubscriptionRegistry.get(subscriptionId);
			if (subscription == null) {
				classLogger.error("Microsoft Graph subscription {} was created but could not be read back.",
						subscriptionId);
				return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON,
						"the subscription was created at Microsoft but could not be recorded"), 500);
			}

			Map<String, Object> output = new LinkedHashMap<>(subscription.describe());
			output.put(STATUS, "ok");
			return WebUtility.getResponse(output, 200);
		} catch (IllegalArgumentException e) {
			classLogger.error("Microsoft Graph refused a subscription on '{}'", resource, e);
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, String.valueOf(e.getMessage())), 400);
		} catch (Exception e) {
			classLogger.error("Failed to subscribe to Microsoft Graph notifications on '{}'", resource, e);
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "unable to create the subscription"), 500);
		}
	}

	/**
	 * Pushes a subscription's expiry back out.
	 * <p>
	 * {@code POST /msgraph/renew?subscriptionId=...}
	 * <p>
	 * Renewing is the whole of keeping a subscription alive, and it has to happen
	 * before the expiry rather than after: once Graph has dropped it there is
	 * nothing to renew and a new subscription has to be created.
	 *
	 * @param req the request, carrying the session whose subscription this is
	 * @return what the subscription looks like now
	 */
	@POST
	@Path("/renew")
	public Response renew(@Context HttpServletRequest req) {
		User user;
		try {
			user = ResourceUtility.getUser(req);
		} catch (IllegalAccessException e) {
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "user session is invalid"), 401);
		}

		Response noMicrosoft = requireMicrosoftLogin(user);
		if (noMicrosoft != null) {
			return noMicrosoft;
		}

		String subscriptionId = trimToNull(req.getParameter("subscriptionId"));
		if (subscriptionId == null) {
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "a subscriptionId is required"), 400);
		}
		Subscription subscription = ownedSubscription(user, subscriptionId);
		if (subscription == null) {
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "no such subscription is recorded"), 404);
		}
		int minutes = optionalInt(req.getParameter("minutes"));

		try {
			String accessToken = MicrosoftLoginUtils.getValidAccessToken(user);
			Map<String, Object> renewed = MicrosoftGraphSubscriptionClient.renew(accessToken, subscriptionId,
					subscription.getResource(), minutes);
			Instant expiration = expirationOf(renewed);
			// Graph agreed first, so the record follows it rather than the other way
			// round
			MicrosoftGraphSubscriptionRegistry.renewed(subscriptionId, expiration);
			subscription.setExpiration(expiration);

			Map<String, Object> output = new LinkedHashMap<>(subscription.describe());
			output.put(STATUS, "ok");
			return WebUtility.getResponse(output, 200);
		} catch (Exception e) {
			classLogger.error("Failed to renew the Microsoft Graph subscription '{}'", subscriptionId, e);
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "unable to renew the subscription"), 500);
		}
	}

	/**
	 * Stops a subscription and forgets it.
	 * <p>
	 * {@code DELETE /msgraph/subscription?subscriptionId=...}
	 *
	 * @param req the request, carrying the session whose subscription this is
	 * @return what was stopped
	 */
	@DELETE
	@Path("/subscription")
	public Response unsubscribe(@Context HttpServletRequest req) {
		User user;
		try {
			user = ResourceUtility.getUser(req);
		} catch (IllegalAccessException e) {
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "user session is invalid"), 401);
		}

		Response noMicrosoft = requireMicrosoftLogin(user);
		if (noMicrosoft != null) {
			return noMicrosoft;
		}

		String subscriptionId = trimToNull(req.getParameter("subscriptionId"));
		if (subscriptionId == null) {
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "a subscriptionId is required"), 400);
		}
		if (ownedSubscription(user, subscriptionId) == null) {
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "no such subscription is recorded"), 404);
		}

		try {
			String accessToken = MicrosoftLoginUtils.getValidAccessToken(user);
			MicrosoftGraphSubscriptionClient.delete(accessToken, subscriptionId);
			// forgotten only once Graph has stopped sending, so a failure here leaves
			// something that can be tried again rather than an orphan
			MicrosoftGraphSubscriptionRegistry.remove(subscriptionId);
			return WebUtility.getResponse(Map.of(STATUS, "ok", "subscriptionId", subscriptionId, "deleted", true), 200);
		} catch (Exception e) {
			classLogger.error("Failed to stop the Microsoft Graph subscription '{}'", subscriptionId, e);
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "unable to stop the subscription"), 500);
		}
	}

	/**
	 * The subscriptions the signed in user has, as this deployment recorded them
	 * and as Graph holds them.
	 * <p>
	 * {@code GET /msgraph/subscriptions}
	 * <p>
	 * The two lists differ when they should, and the difference is the reason to
	 * show both: something recorded here but absent at Graph has expired or been
	 * stopped, and something at Graph with no record here cannot be acted on, since
	 * nothing holds the client state to recognize its notifications or the token to
	 * read what changed.
	 *
	 * @param req the request, carrying the session whose subscriptions these are
	 * @return what is recorded and what Graph reports
	 */
	@GET
	@Path("/subscriptions")
	public Response subscriptions(@Context HttpServletRequest req) {
		User user;
		try {
			user = ResourceUtility.getUser(req);
		} catch (IllegalAccessException e) {
			return WebUtility.getResponse(Map.of(STATUS, ERROR, REASON, "user session is invalid"), 401);
		}

		Response noMicrosoft = requireMicrosoftLogin(user);
		if (noMicrosoft != null) {
			return noMicrosoft;
		}

		List<Map<String, Object>> held = new ArrayList<>();
		for (Subscription subscription : MicrosoftGraphSubscriptionRegistry.forUser(userId(user))) {
			held.add(subscription.describe());
		}

		Map<String, Object> output = new LinkedHashMap<>();
		output.put(STATUS, "ok");
		output.put("recorded", held);
		try {
			String accessToken = MicrosoftLoginUtils.getValidAccessToken(user);
			output.put("atMicrosoft", MicrosoftGraphSubscriptionClient.list(accessToken));
		} catch (Exception e) {
			classLogger.error("Failed to read the Microsoft Graph subscriptions of the signed in user", e);
			output.put("atMicrosoft", "unavailable: " + e.getMessage());
		}
		return WebUtility.getResponse(output, 200);
	}

	/**
	 * Which receiver a resource's notifications belong at.
	 *
	 * @param resource what is being subscribed to
	 * @return the path of the receiver, or null when nothing here can take it
	 */
	private static String notificationPathFor(String resource) {
		String wanted = resource.toLowerCase(Locale.ROOT);
		if (wanted.contains("/messages") || wanted.endsWith("messages")) {
			return MESSAGES_PATH;
		}
		if (wanted.contains("/events") || wanted.endsWith("events") || wanted.contains("/calendarview")) {
			return EVENTS_PATH;
		}
		return null;
	}

	/**
	 * The subscription a user is allowed to act on, which is one they created.
	 *
	 * @param user           whoever is asking
	 * @param subscriptionId what they are asking about
	 * @return the subscription, or null when it is not theirs or not here
	 */
	private static Subscription ownedSubscription(User user, String subscriptionId) {
		Subscription subscription = MicrosoftGraphSubscriptionRegistry.get(subscriptionId);
		if (subscription == null) {
			return null;
		}
		// somebody else's subscription is answered the same way as one that does not
		// exist, so this does not confirm what other people are watching
		return subscription.getUserId() != null && subscription.getUserId().equals(userId(user)) ? subscription : null;
	}

	/**
	 * The id a subscription is filed under, which is the one on the person's
	 * Microsoft login.
	 *
	 * <p>
	 * Deliberately not their primary login. Somebody can sign in natively or
	 * through another provider and link Microsoft alongside it, and a subscription
	 * is a Microsoft thing: it is created with their Microsoft token and rebuilt
	 * later as a Microsoft identity. Filing it under a native id would mean
	 * reading the row back and putting that id on a Microsoft token, which is
	 * nobody.
	 * </p>
	 *
	 * @param user the signed in user
	 * @return the id of their Microsoft login, or null when they have none
	 */
	private static String userId(User user) {
		AccessToken token = user == null ? null : user.getAccessToken(AuthProvider.MICROSOFT);
		if (token == null) {
			return null;
		}
		String id = token.getId();
		return id == null || id.trim().isEmpty() ? null : id;
	}

	/**
	 * Refuses a request from somebody with no Microsoft login.
	 *
	 * @param user the signed in user
	 * @return the answer to give them, or null when they do have one
	 */
	private static Response requireMicrosoftLogin(User user) {
		if (userId(user) == null) {
			return WebUtility.getResponse(
					Map.of(STATUS, ERROR, REASON, "You are not signed in to Microsoft."), 400);
		}
		return null;
	}

	/**
	 * @param subscription the subscription as Graph returned it
	 * @return when it expires, or null when Graph did not say
	 */
	private static Instant expirationOf(Map<String, Object> subscription) {
		Object expiration = subscription == null ? null : subscription.get("expirationDateTime");
		if (expiration == null) {
			return null;
		}
		try {
			return Instant.parse(expiration.toString());
		} catch (RuntimeException e) {
			classLogger.debug("Could not read the expiry '{}' Microsoft Graph returned", expiration, e);
			return null;
		}
	}

	/**
	 * @param value the value to read
	 * @return the number, or 0 when there was nothing readable to read
	 */
	private static int optionalInt(String value) {
		String trimmed = trimToNull(value);
		if (trimmed == null) {
			return 0;
		}
		try {
			return Integer.parseInt(trimmed);
		} catch (NumberFormatException e) {
			classLogger.debug("Ignoring the unreadable number of minutes '{}'", trimmed, e);
			return 0;
		}
	}

	private static String trimToNull(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		return value.trim();
	}

}
