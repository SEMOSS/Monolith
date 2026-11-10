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

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link MicrosoftGraphSubscriptionService}, for
 * its OpenAPI annotations.
 */
public final class MicrosoftGraphSubscriptionServiceSchemas {

	private MicrosoftGraphSubscriptionServiceSchemas() {
	}

	@Schema(description = "A refused Microsoft Graph request")
	public record MsGraphError(@Schema(description = "Always \"error\"", allowableValues = "error", requiredMode = REQUIRED) String status,
			@Schema(description = "Why the request was refused", requiredMode = REQUIRED) String reason) {
	}

	@Schema(description = "Whether this deployment can subscribe to Microsoft Graph changes for the signed in user")
	public record MsGraphAvailable(@Schema(description = "Always \"ok\"", allowableValues = "ok", requiredMode = REQUIRED) String status,
			@Schema(description = "True when a subscription could be attempted: a Microsoft login, a public address and a permitting scope. Graph may still refuse", requiredMode = REQUIRED) boolean available,
			@Schema(description = "True when the user has a Microsoft login", requiredMode = REQUIRED) boolean signedIntoMicrosoft,
			@Schema(description = "True when Microsoft can reach this deployment over public https", requiredMode = REQUIRED) boolean publiclyReachable,
			@Schema(description = "The public base url notifications are posted under", requiredMode = REQUIRED) String notificationBaseUrl,
			@Schema(description = "True when the deployment's Microsoft sign in asks for a scope that allows subscribing to mail", requiredMode = REQUIRED) boolean canSubscribeToMail,
			@Schema(description = "True when the deployment's Microsoft sign in asks for a scope that allows subscribing to calendar events", requiredMode = REQUIRED) boolean canSubscribeToEvents,
			@Schema(description = "Why a subscription cannot be attempted; empty when it can", requiredMode = REQUIRED) List<String> reasons) {
	}

	@Schema(description = "A Microsoft Graph subscription as this deployment recorded it")
	public record MsGraphSubscriptionDetails(
			@Schema(description = "\"ok\"; sent by subscribe and renew, absent from the subscriptions list", allowableValues = "ok") String status,
			@Schema(description = "The id Graph gave the subscription, which renew and unsubscribe take", requiredMode = REQUIRED) String subscriptionId,
			@Schema(description = "What it watches, such as me/messages") String resource,
			@Schema(description = "Which changes it hears about, such as created") String changeType,
			@Schema(description = "Where Graph posts its notifications") String notificationUrl,
			@Schema(description = "The id of the subscriber's Microsoft login") String userId,
			@Schema(description = "The login provider of the subscriber") String userProvider,
			@Schema(description = "When Graph stops sending, as an ISO-8601 instant; absent when unknown") String expirationDateTime) {
	}

	@Schema(description = "The form a subscription is started with")
	public record MsGraphSubscribeRequest(
			@Schema(description = "What to watch: a mailbox or a calendar, such as me/messages or me/events", requiredMode = REQUIRED) String resource,
			@Schema(description = "Which changes to hear about, such as created or created,updated; created when not sent") String changeType,
			@Schema(description = "How many minutes the subscription should last; Graph's longest when not sent") Integer minutes) {
	}

	@Schema(description = "The form a subscription is renewed with")
	public record MsGraphRenewRequest(@Schema(description = "The subscription to renew", requiredMode = REQUIRED) String subscriptionId,
			@Schema(description = "How many more minutes it should last; Graph's longest when not sent") Integer minutes) {
	}

	@Schema(description = "A stopped subscription")
	public record MsGraphUnsubscribeResult(@Schema(description = "Always \"ok\"", allowableValues = "ok", requiredMode = REQUIRED) String status,
			@Schema(description = "The subscription that was stopped", requiredMode = REQUIRED) String subscriptionId,
			@Schema(description = "Always true", requiredMode = REQUIRED) boolean deleted) {
	}

	@Schema(description = "The signed in user's subscriptions, as recorded here and as Graph holds them")
	public record MsGraphSubscriptions(@Schema(description = "Always \"ok\"", allowableValues = "ok", requiredMode = REQUIRED) String status,
			@Schema(description = "The subscriptions this deployment recorded for the user", requiredMode = REQUIRED) List<MsGraphSubscriptionDetails> recorded,
			@Schema(description = "The subscriptions Graph holds for the user, as Graph's own subscription objects passed through as is; a string starting \"unavailable: \" when Graph could not be read", types = {
					"array", "string" }, requiredMode = REQUIRED) Object atMicrosoft) {
	}
}
