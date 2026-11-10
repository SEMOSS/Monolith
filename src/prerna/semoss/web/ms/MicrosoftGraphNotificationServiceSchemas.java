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
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link MicrosoftGraphNotificationService}, for
 * its OpenAPI annotations.
 */
public final class MicrosoftGraphNotificationServiceSchemas {

	private MicrosoftGraphNotificationServiceSchemas() {
	}

	@Schema(description = "A batch of Microsoft Graph change notifications")
	public record MsGraphNotificationBatch(@Schema(description = "The notifications", requiredMode = REQUIRED) List<MsGraphNotification> value) {
	}

	@Schema(description = "One Microsoft Graph change notification; only the fields read here are listed")
	public record MsGraphNotification(@Schema(description = "The subscription it belongs to", requiredMode = REQUIRED) String subscriptionId,
			@Schema(description = "The client state the subscription was created with, which authenticates the delivery", requiredMode = REQUIRED) String clientState,
			@Schema(description = "What happened: created, updated or deleted") String changeType,
			@Schema(description = "Graph's resource data, an object whose id names what changed") Map<String, Object> resourceData) {
	}

	@Schema(description = "A delivery was acknowledged")
	public record MsGraphNotificationAck(@Schema(description = "\"Accepted\", or \"Nothing to do\" for an empty batch", allowableValues = {
			"Accepted", "Nothing to do" }, requiredMode = REQUIRED) String message,
			@Schema(description = "How many notifications were accepted; absent for an empty batch") Integer count) {
	}
}
