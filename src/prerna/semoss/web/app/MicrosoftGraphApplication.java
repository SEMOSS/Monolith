package prerna.semoss.web.app;

import java.util.HashSet;
import java.util.Set;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import prerna.semoss.web.ms.MicrosoftGraphNotificationService;
import prerna.semoss.web.ms.MicrosoftGraphSubscriptionService;

/**
 * JAX-RS application that exposes the Microsoft Graph change notification
 * endpoints under {@code /msgraph}.
 * <p>
 * Registered by a dedicated RESTEasy dispatcher mapped to {@code /msgraph/*}
 * (see web.xml), so the feature lives off {@code /api} and outside that auth
 * filter chain, the same way the GitHub and Teams integrations do. Graph posts
 * its notifications with no session and no bearer token of ours, so the
 * receivers have to be reachable without one and authenticate each delivery
 * themselves.
 * <p>
 * {@link MicrosoftGraphSubscriptionService} holds the lifecycle a signed in
 * user drives, and every endpoint on it needs a session;
 * {@link MicrosoftGraphNotificationService} holds the two inbound receivers,
 * which authenticate each delivery by the client state the subscription was
 * created with.
 */
@ApplicationPath("/msgraph")
public class MicrosoftGraphApplication extends Application {

	private Set<Object> singletons = new HashSet<Object>();

	public MicrosoftGraphApplication() {
		singletons.add(new MicrosoftGraphSubscriptionService());
		singletons.add(new MicrosoftGraphNotificationService());
	}

	@Override
	public Set<Object> getSingletons() {
		return singletons;
	}
}
