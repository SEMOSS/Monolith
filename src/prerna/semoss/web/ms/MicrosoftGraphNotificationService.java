package prerna.semoss.web.ms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import prerna.io.connector.ms.MicrosoftGraphSubscriptionRegistry;
import prerna.io.connector.ms.MicrosoftGraphSubscriptionRegistry.Subscription;
import prerna.io.connector.ms.calendar.MicrosoftCalendarHelper;
import prerna.io.connector.ms.outlook.MicrosoftOutlookMailHelper;
import prerna.io.connector.ms.outlook.MicrosoftOutlookMessageMapper;
import prerna.semoss.web.app.MicrosoftGraphApplication;
import prerna.web.services.util.WebUtility;

/**
 * The two endpoints Microsoft Graph posts change notifications to, served under
 * {@code /msgraph} (see {@link MicrosoftGraphApplication}).
 * <p>
 * One for a mailbox and one for a calendar. They are separate because a
 * notification says only that something changed and where, so what is worth
 * doing about it differs entirely: a message is read and answered, an event is
 * read and put on a schedule, and a single endpoint would only have to sort
 * them out again from the resource string.
 * <p>
 * Each endpoint does two unrelated jobs, because Graph posts both to the same
 * url:
 * <ol>
 * <li>A validation handshake, which arrives as a {@code validationToken} query
 * parameter when a subscription is being created and has to be echoed back as
 * plain text within ten seconds. Graph creates nothing until it is.</li>
 * <li>A notification, which arrives as a batch in the body, each carrying the
 * subscription id and the client state it was created with.</li>
 * </ol>
 * <p>
 * A delivery is authenticated by that client state matching what this instance
 * recorded when it created the subscription. That is a shared secret rather
 * than a signature, which is all Graph offers for a notification without
 * resource data, and it is enough to discard anything posted here by something
 * that never saw the subscription being made. What it does not do is prove the
 * body was not tampered with in flight, which is the reason the body is used
 * only to decide what to go and read, and never as the data itself.
 * <p>
 * Reading happens off the request thread. Graph expects an answer in seconds
 * and retries anything slower, so the delivery is acknowledged as soon as it is
 * recognized and the reading follows on its own.
 */
@Path("/")
public class MicrosoftGraphNotificationService {

	private static final Logger classLogger = LogManager.getLogger(MicrosoftGraphNotificationService.class);

	/**
	 * Reads the changed resource off the request thread so the endpoint can answer
	 * inside the window Graph allows. Daemon threads, so a read in flight never
	 * holds up shutdown; a notification lost that way is one notification, where a
	 * held shutdown is the whole instance.
	 */
	private static final ExecutorService DISPATCH_EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
		Thread thread = new Thread(runnable, "msgraph-notification-dispatch");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * How much of a body is read back. It is deliberately short: what a receiver
	 * does with a message is decide what to do next, and the whole of it is a read
	 * away for whatever ends up doing it.
	 */
	private static final int MAX_BODY_CHARS = 2_000;

	private static final String VALIDATION_TOKEN = "validationToken";
	private static final String CHANGE_TYPE = "changeType";
	private static final String DELETED = "deleted";

	/**
	 * Receives the notifications about a mailbox.
	 * <p>
	 * {@code POST /msgraph/notifications/messages}
	 *
	 * @param req the delivery
	 * @return the validation token when Graph is validating, otherwise an
	 *         acknowledgment
	 * @throws IOException if the body cannot be read
	 */
	@POST
	@Path("/notifications/messages")
	public Response messages(@Context HttpServletRequest req) throws IOException {
		return receive(req, true);
	}

	/**
	 * Receives the notifications about a calendar.
	 * <p>
	 * {@code POST /msgraph/notifications/events}
	 *
	 * @param req the delivery
	 * @return the validation token when Graph is validating, otherwise an
	 *         acknowledgment
	 * @throws IOException if the body cannot be read
	 */
	@POST
	@Path("/notifications/events")
	public Response events(@Context HttpServletRequest req) throws IOException {
		return receive(req, false);
	}

	/**
	 * Handles one delivery, whichever receiver it arrived at.
	 *
	 * @param req    the delivery
	 * @param isMail whether this is the mailbox receiver, which decides what the
	 *               changed resource is read as
	 * @return what to answer Graph with
	 * @throws IOException if the body cannot be read
	 */
	private Response receive(HttpServletRequest req, boolean isMail) throws IOException {
		// the handshake comes first, because it arrives before any subscription
		// exists to check a notification against
		String validationToken = req.getParameter(VALIDATION_TOKEN);
		if (validationToken != null && !validationToken.trim().isEmpty()) {
			classLogger.info("Answering a Microsoft Graph subscription validation handshake.");
			return Response.ok(validationToken, MediaType.TEXT_PLAIN).build();
		}

		String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		JsonObject payload;
		try {
			payload = JsonParser.parseString(body).getAsJsonObject();
		} catch (RuntimeException e) {
			classLogger.warn("Rejecting a Microsoft Graph notification whose body is not json.", e);
			return WebUtility.getResponse(Map.of("status", "error", "reason", "malformed body"), 400);
		}

		JsonArray notifications = payload.getAsJsonArray("value");
		if (notifications == null || notifications.isEmpty()) {
			return WebUtility.getResponse(Map.of("message", "Nothing to do"), 202);
		}

		int accepted = 0;
		for (JsonElement element : notifications) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject notification = element.getAsJsonObject();
			String subscriptionId = asString(notification, "subscriptionId");
			Subscription subscription = MicrosoftGraphSubscriptionRegistry.get(subscriptionId);
			if (subscription == null) {
				// this instance did not create it: another one behind the same load
				// balancer did, or it outlived a restart. Answering 202 stops Graph
				// retrying something nobody here can act on
				classLogger.warn("Ignoring a Microsoft Graph notification for the unknown subscription {}",
						subscriptionId);
				continue;
			}
			if (!subscription.matches(asString(notification, "clientState"))) {
				classLogger.warn("Discarding a Microsoft Graph notification for subscription {} whose client state "
						+ "does not match.", subscriptionId);
				continue;
			}

			String changeType = asString(notification, CHANGE_TYPE);
			String resourceId = resourceId(notification);
			accepted++;
			// execute rather than submit, so anything that escapes dispatch reaches
			// the thread's uncaught handler instead of being captured in a Future
			// nobody reads
			DISPATCH_EXECUTOR.execute(() -> dispatch(subscription, changeType, resourceId, isMail));
		}

		return WebUtility.getResponse(Map.of("message", "Accepted", "count", accepted), 202);
	}

	/**
	 * Reads what changed and does something with it.
	 *
	 * <p>
	 * This is where the platform gets told. For now it reads the message or the
	 * event and logs it, which is enough to prove the round trip: the subscription
	 * matched, the user's token still works, and the thing that changed can be
	 * read. Submitting an agent run, or writing to whatever should react to new
	 * mail, goes here, and nothing around it has to change when it does.
	 * </p>
	 *
	 * @param subscription the subscription the notification belongs to
	 * @param changeType   what happened to it
	 * @param resourceId   the id of what changed
	 * @param isMail       whether it is a message rather than an event
	 */
	private static void dispatch(Subscription subscription, String changeType, String resourceId, boolean isMail) {
		if (resourceId == null) {
			classLogger.warn("A Microsoft Graph notification for subscription {} named nothing that changed.",
					subscription.getId());
			return;
		}
		if (DELETED.equalsIgnoreCase(changeType)) {
			// there is nothing left to read, and the id is all anybody gets
			classLogger.info("Microsoft Graph says {} was deleted from '{}'", resourceId, subscription.getResource());
			return;
		}

		try {
			// the subscriber's token comes off the stored subscription rather than off
			// a session, since this container may never have seen them sign in. It
			// refreshes and writes the new pair back for whichever container is next
			String accessToken = subscription.accessToken();

			if (isMail) {
				Map<String, Object> message = new MicrosoftOutlookMailHelper().getMessage(accessToken, null,
						resourceId);
				Map<String, Object> described = MicrosoftOutlookMessageMapper.toMessage(message, true, MAX_BODY_CHARS);
				classLogger.info("Mail {} for subscription {}: from {}, subject '{}'", changeType, subscription.getId(),
						described.get("from"), described.get("subject"));
			} else {
				Map<String, Object> event = MicrosoftCalendarHelper.getEvent(accessToken, null, null, resourceId,
						MAX_BODY_CHARS, null);
				classLogger.info("Event {} for subscription {}: '{}' starting {}", changeType, subscription.getId(),
						event.get("subject"), event.get("start"));
			}
		} catch (Exception e) {
			// the usual cause is the subscriber's Microsoft login no longer being
			// refreshable, which the next renewal will fail on too
			classLogger.error("Failed to read {} for the Microsoft Graph subscription {}", resourceId,
					subscription.getId(), e);
		}
	}

	/**
	 * The id of what changed, which Graph carries on the resource data rather than
	 * in the resource path.
	 *
	 * @param notification one notification
	 * @return the id, or null when there is none to read
	 */
	private static String resourceId(JsonObject notification) {
		JsonElement resourceData = notification.get("resourceData");
		if (resourceData == null || !resourceData.isJsonObject()) {
			return null;
		}
		return asString(resourceData.getAsJsonObject(), "id");
	}

	/**
	 * @param json the object to read
	 * @param key  the field to read
	 * @return the value as a string, or null when it is absent or null
	 */
	private static String asString(JsonObject json, String key) {
		JsonElement element = json.get(key);
		if (element == null || element.isJsonNull()) {
			return null;
		}
		return element.getAsString();
	}

}
