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
 * The requests and responses of {@link PermissionBatchResource} and
 * {@link AdminPermissionBatchResource}, for their OpenAPI annotations. They
 * mirror the maps {@code SecurityAccessPermissionBatchUtils},
 * {@code PermissionBatchUtils}, {@code EffectivePermissionUtils} and
 * {@code ModelUsagePathView} build in Semoss.
 */
public final class PermissionBatchResourceSchemas {

	private PermissionBatchResourceSchemas() {
	}

	@Schema(description = "The permission batches to validate or save")
	public record PermissionBatchBatchesRequest(@Schema(description = "JSON-encoded list of permission batches, each {\"target\": {\"resourceType\", \"id\", \"projectId\"}, \"changes\": [{\"action\": \"ADD\"|\"CHANGE\"|\"REMOVE\", \"subject\": {\"category\", \"id\", \"type\"}, \"to\": {\"level\", \"endDate\", \"usageLimit\"}}]}", requiredMode = REQUIRED) String batches) {
	}

	@Schema(description = "What a permission batch changes: a project, an engine, an insight, or a custom team's members or managers")
	public record PermissionBatchTarget(
			@Schema(description = "The type of target", allowableValues = { "PROJECT", "ENGINE", "INSIGHT", "TEAM",
					"TEAM_MANAGERS" }) String resourceType,
			@Schema(description = "The project, engine or insight id, or the custom team's name") String id,
			@Schema(description = "The project an insight belongs to") String projectId) {
	}

	@Schema(description = "Whether one change of a permission batch can be made")
	public record PermissionBatchChangeValidation(
			@Schema(description = "The change's index in its batch", requiredMode = REQUIRED) int index,
			@Schema(description = "Whether the change can be made", requiredMode = REQUIRED) boolean allowed,
			@Schema(description = "Why the change cannot be made, when it cannot") String reason) {
	}

	@Schema(description = "What validating one permission batch found")
	public record PermissionBatchValidation(
			@Schema(description = "The batch's target, as sent", requiredMode = REQUIRED) PermissionBatchTarget target,
			@Schema(description = "Why none of the batch can be made, such as a target in two batches") String reason,
			@Schema(description = "Each change, in the batch's order", requiredMode = REQUIRED) List<PermissionBatchChangeValidation> changes) {
	}

	@Schema(description = "What saving permission batches did")
	public record PermissionBatchSaveResult(
			@Schema(description = "How many targets changed", requiredMode = REQUIRED) int targets,
			@Schema(description = "How many changes were saved", requiredMode = REQUIRED) int changes) {
	}

	@Schema(description = "One rule of a model usage limit, such as 100,000 tokens a minute")
	public record PermissionBatchUsageLimitRule(
			@Schema(description = "What the rule counts", allowableValues = { "TOKENS", "COST",
					"COMPUTE_TIME" }, requiredMode = REQUIRED) String metric,
			@Schema(description = "Tokens, millionths of a US dollar or milliseconds", requiredMode = REQUIRED) Long amount,
			@Schema(description = "How long the rule's count runs", allowableValues = { "MINUTE", "HOUR", "DAY", "WEEK",
					"MONTH", "YEAR" }, requiredMode = REQUIRED) String window,
			@Schema(description = "How many minutes or hours the window spans; left out for 1") Integer windowLength,
			@Schema(description = "Whose use the rule counts", allowableValues = { "EACH_PERSON",
					"SHARED" }, requiredMode = REQUIRED) String counts) {
	}

	@Schema(description = "What one source allows on a model: Unlimited, or rules that must all have room. Left out wherever no limit is defined")
	public record PermissionBatchUsageLimit(
			@Schema(description = "An explicit choice of no cap; the limit then has no rules", requiredMode = REQUIRED) boolean unlimited,
			@Schema(description = "The rules, every one of which a call must pass") List<PermissionBatchUsageLimitRule> rules) {
	}

	@Schema(description = "A person, with the details Bulk Access Management shows")
	public record PermissionBatchPerson(
			@Schema(description = "Always USER", allowableValues = "USER", requiredMode = REQUIRED) String category,
			@Schema(description = "The user id", requiredMode = REQUIRED) String id,
			@Schema(description = "The person's login type, such as NATIVE") String type,
			@Schema(description = "The display name") String name,
			@Schema(description = "The email address") String email) {
	}

	@Schema(description = "A team that has access to a project or engine")
	public record PermissionBatchTeamSubject(
			@Schema(description = "Always TEAM", allowableValues = "TEAM", requiredMode = REQUIRED) String category,
			@Schema(description = "The team's name", requiredMode = REQUIRED) String id,
			@Schema(description = "The team's type, such as CUSTOM", requiredMode = REQUIRED) String type) {
	}

	@Schema(description = "The whole access a subject has, in the shape batches use")
	public record PermissionBatchGrant(
			@Schema(description = "The access level", allowableValues = { "OWNER", "EDIT", "READ_ONLY" }) String level,
			@Schema(description = "When the access ends, as an ISO date and time in UTC; left out for never") String endDate,
			@Schema(description = "On an engine, the grant's usage limit; left out for no limit defined") PermissionBatchUsageLimit usageLimit) {
	}

	@Schema(description = "A person and the access they have now")
	public record PermissionBatchPersonAccess(
			@Schema(description = "The person", requiredMode = REQUIRED) PermissionBatchPerson subject,
			@Schema(description = "Their access; left out on a custom team's members or managers") PermissionBatchGrant access) {
	}

	@Schema(description = "A team and the access it has now")
	public record PermissionBatchTeamAccess(
			@Schema(description = "The team", requiredMode = REQUIRED) PermissionBatchTeamSubject subject,
			@Schema(description = "The team's access", requiredMode = REQUIRED) PermissionBatchGrant access) {
	}

	@Schema(description = "What the signed in user may change on the target")
	public record PermissionBatchAllowedChanges(
			@Schema(description = "Whether they can give, change and take away people's access", requiredMode = REQUIRED) boolean canChangePeople,
			@Schema(description = "Whether they can give or change Author (OWNER) access", requiredMode = REQUIRED) boolean canGiveAuthorAccess,
			@Schema(description = "Whether they can change which teams have access", requiredMode = REQUIRED) boolean canChangeTeams,
			@Schema(description = "Whether only admins can give people access to it", requiredMode = REQUIRED) boolean addsAreAdminOnly,
			@Schema(description = "On a model, the access levels whose people's usage limits the user may set: every level for owners and admin mode, none for anyone else; left out on anything else") List<String> limitLevels,
			@Schema(description = "On a model, whether the user can set team limits, the public use limit and the model total; left out on anything else") Boolean canChangeModelLimits) {
	}

	@Schema(description = "A model's public use limit, and whether the model is public")
	public record PermissionBatchPublicUse(
			@Schema(description = "Whether the model is public now", requiredMode = REQUIRED) boolean isPublic,
			@Schema(description = "The public use limit; left out for no limit defined") PermissionBatchUsageLimit usageLimit) {
	}

	@Schema(description = "A model's total budget, for everyone together")
	public record PermissionBatchModelTotal(
			@Schema(description = "The model total; left out for no limit defined") PermissionBatchUsageLimit usageLimit) {
	}

	@Schema(description = "The price entry from the model's pricing metadata that its cost rules use, in US dollars per million tokens, passed through as stored")
	public record PermissionBatchPrice(
			@Schema(description = "The serving provider the entry is for") String servingProvider,
			@Schema(description = "The provider's model id") String modelId,
			@Schema(description = "The uncached input rate", requiredMode = REQUIRED) Double input,
			@Schema(description = "The output rate", requiredMode = REQUIRED) Double output,
			@Schema(description = "The cache read rate") Double cache_read,
			@Schema(description = "The cache write rate") Double cache_write,
			@Schema(description = "The thinking rate") Double reasoning) {
	}

	@Schema(description = "Who can use a project or an engine now, or who is in or manages a custom team, and what the signed in user may change")
	public record PermissionBatchPermissions(
			@Schema(description = "The people given access directly, or the team's members or managers", requiredMode = REQUIRED) List<PermissionBatchPersonAccess> people,
			@Schema(description = "The teams that have access; empty on a custom team", requiredMode = REQUIRED) List<PermissionBatchTeamAccess> teams,
			@Schema(description = "What the signed in user may change", requiredMode = REQUIRED) PermissionBatchAllowedChanges allowedPermissionChanges,
			@Schema(description = "On a model, its public use limit; left out on anything else") PermissionBatchPublicUse publicUse,
			@Schema(description = "On a model, its total; left out on anything else") PermissionBatchModelTotal modelTotal,
			@Schema(description = "On a model, whether it has known prices, so its limits can have cost rules; left out on anything else") Boolean costLimitsAvailable,
			@Schema(description = "On a model, the price entry its cost rules use; left out when it has no known prices") PermissionBatchPrice price) {
	}

	@Schema(description = "A person to copy from a source, with their access on the source and the target")
	public record PermissionBatchPermissionToCopy(
			@Schema(description = "The person", requiredMode = REQUIRED) PermissionBatchPerson subject,
			@Schema(description = "Their access on the source; left out when the source is a custom team") PermissionBatchGrant source,
			@Schema(description = "Their access on the target; left out when they have none or the target is a custom team") PermissionBatchGrant target,
			@Schema(description = "Whether they are already in the target", requiredMode = REQUIRED) boolean inTarget) {
	}

	@Schema(description = "Where part of a person's access comes from")
	public record PermissionBatchEffectiveSource(
			@Schema(description = "DIRECT when given to them, TEAM when through a custom team they are in", allowableValues = {
					"DIRECT", "TEAM" }, requiredMode = REQUIRED) String sourceType,
			@Schema(description = "The team's name, for a team") String id,
			@Schema(description = "The team's type, for a team") String type,
			@Schema(description = "The access this source gives", allowableValues = { "OWNER", "EDIT",
					"READ_ONLY" }) String level,
			@Schema(description = "When this source's access ends, as an ISO date and time in UTC; left out for never") String endDate) {
	}

	@Schema(description = "The source a person's calls to a model are billed to")
	public record PermissionBatchBilledTo(
			@Schema(description = "The type of limit billed", allowableValues = { "DIRECT", "TEAM", "PUBLIC",
					"ALL_MODELS", "PLATFORM" }, requiredMode = REQUIRED) String limitType,
			@Schema(description = "The team's name, for a team") String id,
			@Schema(description = "The team's type, for a team") String type) {
	}

	@Schema(description = "A team a person's calls move on to, in billing order")
	public record PermissionBatchNextTeam(@Schema(description = "The team's name") String id,
			@Schema(description = "The team's type") String type,
			@Schema(description = "Whether its limit is Unlimited", requiredMode = REQUIRED) boolean unlimited,
			@Schema(description = "Its rules", requiredMode = REQUIRED) List<PermissionBatchUsageLimitRule> rules) {
	}

	@Schema(description = "A limit that exists for a person but is replaced by another")
	public record PermissionBatchNotApplied(
			@Schema(description = "The type of limit, such as TEAM", requiredMode = REQUIRED) String limitType,
			@Schema(description = "The team's name, for a team") String id,
			@Schema(description = "The team's type, for a team") String type,
			@Schema(description = "Why it does not apply", allowableValues = { "DIRECT_WINS", "TEAM_WINS",
					"UNLIMITED_TEAM_WINS", "PUBLIC_WINS", "ALL_MODELS_WINS",
					"NOT_PUBLIC" }, requiredMode = REQUIRED) String reason) {
	}

	@Schema(description = "Which usage limit a person's calls to a model are billed to")
	public record PermissionBatchEffectiveUsage(
			@Schema(description = "The source billed now; left out when no limit applies") PermissionBatchBilledTo billedTo,
			@Schema(description = "Whether the billed source is Unlimited", requiredMode = REQUIRED) boolean unlimited,
			@Schema(description = "The billed source's rules", requiredMode = REQUIRED) List<PermissionBatchUsageLimitRule> rules,
			@Schema(description = "For a team, the other teams in billing order", requiredMode = REQUIRED) List<PermissionBatchNextTeam> nextTeams,
			@Schema(description = "Limits that exist but are replaced", requiredMode = REQUIRED) List<PermissionBatchNotApplied> notApplied) {
	}

	@Schema(description = "A person who can use a project or engine, and where their access comes from")
	public record PermissionBatchEffectivePerson(
			@Schema(description = "The person", requiredMode = REQUIRED) PermissionBatchPerson subject,
			@Schema(description = "The most access any source gives them", allowableValues = { "OWNER", "EDIT",
					"READ_ONLY" }) String level,
			@Schema(description = "Every source of their access, the most access first", requiredMode = REQUIRED) List<PermissionBatchEffectiveSource> sources,
			@Schema(description = "On a model, which usage limit their calls are billed to; left out otherwise") PermissionBatchEffectiveUsage usage) {
	}

	@Schema(description = "A team whose members come from a login provider, so they are not listed")
	public record PermissionBatchUnlistedTeam(@Schema(description = "The team's name", requiredMode = REQUIRED) String id,
			@Schema(description = "The team's type, such as MICROSOFT", requiredMode = REQUIRED) String type,
			@Schema(description = "The access the team gives", allowableValues = { "OWNER", "EDIT",
					"READ_ONLY" }) String level,
			@Schema(description = "On a model, the team's limit; left out for no limit defined") PermissionBatchUsageLimit usageLimit) {
	}

	@Schema(description = "A cap checked on every call to a model, and never billed")
	public record PermissionBatchCeiling(
			@Schema(description = "Which cap: the model total", allowableValues = "MODEL_TOTAL", requiredMode = REQUIRED) String limitType,
			@Schema(description = "Its limit; left out for no limit defined") PermissionBatchUsageLimit usageLimit) {
	}

	@Schema(description = "Everyone who can use a project or an engine in the end")
	public record PermissionBatchEffectivePermissions(
			@Schema(description = "One row per person, sorted by name", requiredMode = REQUIRED) List<PermissionBatchEffectivePerson> people,
			@Schema(description = "Teams whose members come from a login provider, so they are not listed", requiredMode = REQUIRED) List<PermissionBatchUnlistedTeam> unlistedTeams,
			@Schema(description = "On a model, its public use limit and whether it is public; left out otherwise") PermissionBatchPublicUse publicUse,
			@Schema(description = "On a model, the caps on every call to it; left out otherwise") List<PermissionBatchCeiling> ceilings) {
	}

	@Schema(description = "One rule of a usage limit step, with how much of it is used")
	public record PermissionBatchUsageRuleUsage(
			@Schema(description = "What the rule counts", allowableValues = { "TOKENS", "COST",
					"COMPUTE_TIME" }) String metric,
			@Schema(description = "Tokens, millionths of a US dollar or milliseconds") Long amount,
			@Schema(description = "How long the rule's count runs", allowableValues = { "MINUTE", "HOUR", "DAY", "WEEK",
					"MONTH", "YEAR" }) String window,
			@Schema(description = "How many minutes or hours the window spans; left out for 1") Integer windowLength,
			@Schema(description = "Whose use the rule counts", allowableValues = { "EACH_PERSON",
					"SHARED" }) String counts,
			@Schema(description = "How much is used in the current window; left out when it is not known") Long used,
			@Schema(description = "When the window starts again, as an ISO date and time in UTC; left out when not known") String resetsAt) {
	}

	@Schema(description = "A team's total across all models")
	public record PermissionBatchTeamTotal(
			@Schema(description = "Whether the team's total is Unlimited", requiredMode = REQUIRED) boolean unlimited,
			@Schema(description = "Its rules, with what is used", requiredMode = REQUIRED) List<PermissionBatchUsageRuleUsage> rules) {
	}

	@Schema(description = "One step of how usage limits apply to a person on a model")
	public record PermissionBatchUsageLimitStep(
			@Schema(description = "CEILING for a cap checked on every call, BILLED for a source the call may be billed to", allowableValues = {
					"CEILING", "BILLED" }, requiredMode = REQUIRED) String layer,
			@Schema(description = "Which ceiling or source the step checks", allowableValues = { "MODEL_TOTAL", "DIRECT",
					"TEAM", "PUBLIC", "ALL_MODELS", "PLATFORM" }, requiredMode = REQUIRED) String limitType,
			@Schema(description = "The team's name, for a team") String id,
			@Schema(description = "The team's type, for a team") String type,
			@Schema(description = "Where the call stands at this step", allowableValues = { "DEFINED", "NOT_DEFINED",
					"SKIPPED", "NOT_REACHED", "CHECKED", "BILLED", "NEXT", "REACHED" }) String state,
			@Schema(description = "Why the step is skipped, such as NOT_PUBLIC or NO_GRANT") String reason,
			@Schema(description = "For a team, when the person got access through it, in UTC") String accessSince,
			@Schema(description = "Whether its limit is Unlimited", requiredMode = REQUIRED) boolean unlimited,
			@Schema(description = "Its rules, with what is used; left out when rulesHidden is true") List<PermissionBatchUsageRuleUsage> rules,
			@Schema(description = "True when the rules of a step that spans every model (ALL_MODELS, PLATFORM) are hidden from a model editor; left out otherwise") Boolean rulesHidden,
			@Schema(description = "For a team, its total across all models; left out when it has none or when hidden from a model editor") PermissionBatchTeamTotal teamTotal) {
	}

	@Schema(description = "The person a usage limit path is for")
	public record PermissionBatchUsagePerson(@Schema(description = "The user id", requiredMode = REQUIRED) String id,
			@Schema(description = "The login type") String type) {
	}

	@Schema(description = "How usage limits apply to one person on one model, step by step")
	public record PermissionBatchUsageLimitPath(
			@Schema(description = "The model", requiredMode = REQUIRED) String engineId,
			@Schema(description = "The person", requiredMode = REQUIRED) PermissionBatchUsagePerson person,
			@Schema(description = "Which type of source the person's calls are billed to; left out when none", allowableValues = {
					"DIRECT", "TEAM", "PUBLIC", "ALL_MODELS", "PLATFORM" }) String billedLimitType,
			@Schema(description = "The ceilings, checked on every call", requiredMode = REQUIRED) List<PermissionBatchUsageLimitStep> ceilings,
			@Schema(description = "The sources, in the order they are tried", requiredMode = REQUIRED) List<PermissionBatchUsageLimitStep> steps) {
	}

	@Schema(description = "How usage limits apply to the signed in person on one model they can use")
	public record PermissionBatchMyUsageLimitModel(
			@Schema(description = "The model's id", requiredMode = REQUIRED) String engineId,
			@Schema(description = "The model's display name, else its name, else its id", requiredMode = REQUIRED) String name,
			@Schema(description = "The model's engine subtype") String subtype,
			@Schema(description = "How usage limits apply on it", requiredMode = REQUIRED) PermissionBatchUsageLimitPath path) {
	}

	@Schema(description = "How usage limits apply to the signed in person on every model they can use")
	public record PermissionBatchMyUsageLimitPaths(
			@Schema(description = "One entry per model; a model whose path cannot be read is left out", requiredMode = REQUIRED) List<PermissionBatchMyUsageLimitModel> models) {
	}
}
