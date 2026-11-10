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
package prerna.semoss.web.services.local.auth;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link AdminModelUsageResource}, for its OpenAPI
 * annotations. The usage limit records also describe the {@code usage_limit} of
 * an engine's members.
 */
public final class AdminModelUsageResourceSchemas {

	private AdminModelUsageResourceSchemas() {
	}

	@Schema(description = "A model usage limit: either an explicit Unlimited or rules that must all have usage left for a call to go through")
	public record AuthAdminModelUsageLimit(
			@Schema(description = "True for an explicit Unlimited, which ends the search for the source billed; the model and team totals still apply", requiredMode = REQUIRED) boolean unlimited,
			@Schema(description = "The rules of the limit, empty when unlimited") List<AuthAdminModelUsageLimitRule> rules) {
	}

	@Schema(description = "One rule of a model usage limit: how much of a metric may be used in a window")
	public record AuthAdminModelUsageLimitRule(
			@Schema(description = "What the rule measures", allowableValues = { "TOKENS", "COST",
					"COMPUTE_TIME" }) String metric,
			@Schema(description = "How much of the metric may be used in the window", types = "integer", format = "int64") Long amount,
			@Schema(description = "The unit of the window the amount is counted over", allowableValues = { "MINUTE", "HOUR",
					"DAY", "WEEK", "MONTH", "YEAR" }) String window,
			@Schema(description = "How many window units the window spans; absent for 1", types = "integer") Integer windowLength,
			@Schema(description = "Whether each person gets the amount or everyone the limit applies to shares it", allowableValues = {
					"EACH_PERSON", "SHARED" }) String counts) {
	}

	@Schema(description = "The General Platform Limit or a team's Team Total")
	public record AuthAdminModelUsageLimitBody(
			@Schema(description = "The limit; absent when no limit is defined") AuthAdminModelUsageLimit usageLimit) {
	}

	@Schema(description = "The new General Platform Limit")
	public record AuthAdminModelUsageSetPlatformDefaultRequest(
			@Schema(description = "JSON-encoded usage limit ({\"unlimited\": boolean, \"rules\": [{metric, amount, window, windowLength, counts}]}); empty or absent removes the limit") String usageLimit) {
	}

	@Schema(description = "The new Team Total of a group")
	public record AuthAdminModelUsageSetTeamTotalRequest(
			@Schema(description = "The group id", requiredMode = REQUIRED) String groupId,
			@Schema(description = "The group type, such as the login provider of the group", requiredMode = REQUIRED) String type,
			@Schema(description = "JSON-encoded usage limit ({\"unlimited\": boolean, \"rules\": [{metric, amount, window, windowLength, counts}]}); empty or absent removes the limit") String usageLimit) {
	}
}
