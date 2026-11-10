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
package prerna.semoss.web.services.local;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link NameServer}, for its OpenAPI
 * annotations.
 */
public final class NameServerSchemas {

	private NameServerSchemas() {
	}

	/*
	 * Running pixel
	 */

	@Schema(description = "The pixel to run, as form fields or a JSON object")
	public record EngineRunPixelRequest(
			@Schema(description = "The pixel to run; a trailing semicolon is added when missing", requiredMode = REQUIRED) String expression,
			@Schema(description = "The insight to run it in; a new insight is made when this is missing, \"new\" or \"undefined\"") String insightId,
			@Schema(description = "The user's IANA time zone, such as America/New_York; the server's time zone when missing or invalid") String tz,
			@Schema(description = "\"false\" to keep the job's console output after the response; the output is dropped otherwise") String dropLogging) {
	}

	@Schema(description = "The Python to run, as form fields or a JSON object")
	public record EngineRunPythonRequest(
			@Schema(description = "The Python source to run; it is run as the pixel Py(code=...)", requiredMode = REQUIRED) String code,
			@Schema(description = "The insight to run it in; a new insight is made when this is missing, \"new\" or \"undefined\"") String insightId,
			@Schema(description = "The user's IANA time zone, such as America/New_York; the server's time zone when missing or invalid") String tz,
			@Schema(description = "\"false\" to keep the job's console output after the response; the output is dropped otherwise") String dropLogging) {
	}

	@Schema(description = "The results of a pixel run")
	public record EnginePixelResponse(
			@JsonProperty("insightID") @Schema(description = "The insight the pixel ran in", requiredMode = REQUIRED) String insightID,
			@JsonProperty("pixelReturn") @Schema(description = "One result per pixel statement, in order, then any delayed messages", requiredMode = REQUIRED) List<EnginePixelReturn> pixelReturn) {
	}

	@Schema(description = "The result of one pixel statement")
	public record EnginePixelReturn(
			@JsonProperty("pixelId") @Schema(description = "The id of the statement in the insight's recipe; only on top-level results") String pixelId,
			@JsonProperty("pixelExpression") @Schema(description = "The statement that ran; only on top-level results") String pixelExpression,
			@JsonProperty("isMeta") @Schema(description = "Whether the statement is a meta statement the recipe does not keep; only on top-level results") Boolean isMeta,
			@JsonProperty("timeToRun") @Schema(description = "How long the statement took, in milliseconds; only on top-level results", types = "integer", format = "int64") Long timeToRun,
			@JsonProperty("output") @Schema(description = "What the statement returned; its shape depends on the reactor and the operation types (a frame, a task with data, a list of nested results for code, a message, ...)", requiredMode = REQUIRED) Object output,
			@JsonProperty("operationType") @Schema(description = "What the statement did, such as OPERATION, FRAME, TASK_DATA or ERROR; ERROR marks a failed statement", requiredMode = REQUIRED) List<String> operationType,
			@JsonProperty("additionalOutput") @Schema(description = "Further results the statement returned, such as the new frame headers") List<EnginePixelAdditionalOutput> additionalOutput) {
	}

	@Schema(description = "A further result a pixel statement returned")
	public record EnginePixelAdditionalOutput(
			@JsonProperty("output") @Schema(description = "What was returned; its shape depends on the operation types", requiredMode = REQUIRED) Object output,
			@JsonProperty("operationType") @Schema(description = "What the result is, such as FRAME_HEADERS", requiredMode = REQUIRED) List<String> operationType,
			@JsonProperty("additionalOutput") @Schema(description = "Further results, each with this same shape") List<Map<String, Object>> additionalOutput) {
	}

	@Schema(description = "A request that could not run")
	public record EnginePixelError(
			@Schema(description = "Why the request failed", requiredMode = REQUIRED) String errorMessage,
			@Schema(description = "INSIGHT_NOT_FOUND when the insight id is unknown, EXPRESSION_NOT_FOUND when nothing was passed to run", allowableValues = {
					"INSIGHT_NOT_FOUND", "EXPRESSION_NOT_FOUND" }) String errorType) {
	}

	@Schema(description = "A failure while writing the results")
	public record EnginePixelUnexpectedError(
			@Schema(description = "Always \"unknown\"", requiredMode = REQUIRED) String errorType,
			@Schema(description = "The cause of the failure") String message) {
	}

	@Schema(description = "The insight to read the pipeline of")
	public record EngineGetPipelineRequest(
			@Schema(description = "The insight id", requiredMode = REQUIRED) String insightId) {
	}

	@Schema(description = "The insight's recipe, parsed into a pipeline")
	public record EngineGetPipelineResult(
			@Schema(description = "The recipe's steps", requiredMode = REQUIRED) List<Map<String, Object>> idMapping,
			@Schema(description = "The parsed routines, one list of operations per routine", requiredMode = REQUIRED) List<List<Object>> pixelParsing) {
	}

	/*
	 * Asynchronous jobs
	 */

	@Schema(description = "The job that was started")
	public record EngineRunPixelAsyncResult(
			@Schema(description = "The job id; pass it to result, status, console, pixelJobStreaming and terminate", requiredMode = REQUIRED) String jobId) {
	}

	@Schema(description = "The job to read")
	public record EngineJobRequest(
			@Schema(description = "The job id runPixelAsync returned", requiredMode = REQUIRED) String jobId) {
	}

	@Schema(description = "A job's status and the lines it printed since the last call")
	public record EngineJobLines(
			@Schema(description = "The job status", allowableValues = { "Created", "Submitted", "Canceled",
					"InProgress", "ProgressComplete", "Streaming", "Complete", "Paused", "Error",
					"UnknownJob" }, requiredMode = REQUIRED) String status,
			@Schema(description = "The new lines", requiredMode = REQUIRED) List<String> message) {
	}

	@Schema(description = "A job's status and its text output")
	public record EnginePartialResult(
			@Schema(description = "The job status", allowableValues = { "Created", "Submitted", "Canceled",
					"InProgress", "ProgressComplete", "Streaming", "Complete", "Paused", "Error",
					"UnknownJob" }, requiredMode = REQUIRED) String status,
			@Schema(description = "The text output; empty when the job has none", requiredMode = REQUIRED) EnginePartialText message) {
	}

	@Schema(description = "A job's text output")
	public record EnginePartialText(
			@Schema(description = "All the text so far") String total,
			@JsonProperty("new") @Schema(description = "The text added since the last call") String newText) {
	}

	@Schema(description = "A job's status and the stream messages it sent since the last call")
	public record EnginePixelJobStreamingResult(
			@Schema(description = "The job status", allowableValues = { "Created", "Submitted", "Canceled",
					"InProgress", "ProgressComplete", "Streaming", "Complete", "Paused", "Error",
					"UnknownJob" }, requiredMode = REQUIRED) String status,
			@Schema(description = "The new stream messages", requiredMode = REQUIRED) List<EnginePixelJobStreamMessage> message) {
	}

	@Schema(description = "One stream message from a job, such as a chunk of a model's answer")
	public record EnginePixelJobStreamMessage(
			@Schema(description = "The kind of message, such as content, tool, thinking or usage", requiredMode = REQUIRED) String stream_type,
			@Schema(description = "The message, shaped by stream_type: content and finish_reason for content, the tool call delta for tool, thinking for thinking, input_tokens and output_tokens for usage", types = "object") Map<String, Object> data) {
	}

	/*
	 * Agent runs
	 */

	@Schema(description = "The agent run to read")
	public record EngineAgentRunStreamingRequest(
			@Schema(description = "The run id", requiredMode = REQUIRED) String runId) {
	}

	@Schema(description = "An agent run's current state and the events it sent since the last call")
	public record EngineAgentRunStreamingResult(
			@Schema(description = "The run's current state", requiredMode = REQUIRED) EngineAgentRunSnapshot run,
			@Schema(description = "The new events, in order", requiredMode = REQUIRED) List<EngineAgentRunStreamEvent> events,
			@Schema(description = "How many events were dropped because the buffer overflowed", types = "integer", format = "int64", requiredMode = REQUIRED) long droppedEvents) {
	}

	@Schema(description = "An agent run's current state")
	public record EngineAgentRunSnapshot(
			@Schema(description = "The run id") String runId,
			@Schema(description = "The room the run writes its messages to") String roomId,
			@Schema(description = "The run status: SUBMITTED, RUNNING, INPUT_REQUIRED, COMPLETED, FAILED or CANCELLED") String status,
			@Schema(description = "The user message the run started from, once written") String inputMessageId,
			@Schema(description = "The final assistant message, once the run completes") String finalOutputMessageId,
			@Schema(description = "The final assistant text, once the run completes") String finalText,
			@Schema(description = "Why the run failed") String errorMessage,
			@Schema(description = "The run's progress, as its harness reports it", types = "object") Map<String, Object> progress,
			@Schema(description = "The artifacts the run produced") List<Map<String, Object>> artifacts,
			@Schema(description = "The outcome of the run's review, as its workflow reports it", types = "object") Map<String, Object> reviewOutcome,
			@Schema(description = "Warnings from the run's workflow") List<String> warnings,
			@Schema(description = "The tool calls waiting on a decision; empty unless the status is INPUT_REQUIRED", requiredMode = REQUIRED) List<EngineAgentRunPendingAction> pendingActions) {
	}

	@Schema(description = "A paused tool call waiting on the user's decision")
	public record EngineAgentRunPendingAction(
			@Schema(description = "The action id, to pass back with the decision") String actionId,
			@Schema(description = "The run the call belongs to") String runId,
			@Schema(description = "The room of the run") String roomId,
			@Schema(description = "The response message the call is part of") String parentMessageId,
			@Schema(description = "The tool call id") String toolCallId,
			@Schema(description = "The tool name") String toolName,
			@Schema(description = "The arguments the model called the tool with", types = "object") Map<String, Object> toolArgs,
			@Schema(description = "The arguments as the user edited them", types = "object") Map<String, Object> editedArgs,
			@Schema(description = "The tool definition's _meta", types = "object") Map<String, Object> toolMeta,
			@JsonProperty("hasUi") @Schema(description = "Whether the tool has its own UI", requiredMode = REQUIRED) boolean hasUi,
			@Schema(description = "The URL of the tool's UI") String uiUrl,
			@Schema(description = "Always PENDING") String status,
			@Schema(description = "The result of the call") String result,
			@Schema(description = "The status of the tool call") String toolStatus,
			@Schema(description = "When the action was created") String dateCreated,
			@Schema(description = "When the action was decided") String decidedAt,
			@Schema(description = "The user who owns the run") String userId) {
	}

	@Schema(description = "One event of an agent run's stream")
	public record EngineAgentRunStreamEvent(
			@Schema(description = "The event schema version, 1", types = "integer", requiredMode = REQUIRED) int version,
			@Schema(description = "The event id, unique within the run", requiredMode = REQUIRED) String eventId,
			@Schema(description = "The order of the event within the run", types = "integer", format = "int64", requiredMode = REQUIRED) long sequence,
			@Schema(description = "The run id", requiredMode = REQUIRED) String runId,
			@Schema(description = "When the event was recorded, as an ISO-8601 instant", requiredMode = REQUIRED) String timestamp,
			@Schema(description = "The event type", allowableValues = { "item.started", "item.updated",
					"item.completed" }, requiredMode = REQUIRED) String type,
			@Schema(description = "The item, on item.started and item.completed: a message, reasoning, tool or subagent item, told apart by its kind", types = "object") Map<String, Object> item,
			@Schema(description = "The id of the item an item.updated applies to") String itemId,
			@Schema(description = "The kind of item an item.updated applies to: message, reasoning, tool or subagent") String kind,
			@Schema(description = "Text to append, on item.updated for a message or reasoning item") String delta,
			@Schema(description = "Fields to merge onto the item, on item.updated for a tool or subagent item", types = "object") Map<String, Object> patch) {
	}

	/*
	 * Engines and insights
	 */

	@Schema(description = "An engine the user can access")
	public record EngineAllRow(
			@Schema(description = "The engine id", requiredMode = REQUIRED) String engine_id,
			@Schema(description = "The engine name") String engine_name,
			@Schema(description = "The engine display name") String engine_display_name,
			@Schema(description = "The engine type, such as DATABASE, MODEL, STORAGE or VECTOR") String engine_type,
			@Schema(description = "The engine subtype") String engine_subtype,
			@Schema(description = "The engine cost") String engine_cost,
			@Schema(description = "Whether the engine is discoverable") Boolean engine_discoverable,
			@Schema(description = "Whether the engine is public") Boolean engine_global,
			@Schema(description = "The tool app of the engine") String engine_tool_app,
			@Schema(description = "The id of the user who created the engine") String engine_created_by,
			@Schema(description = "The login type of the user who created the engine") String engine_created_by_type,
			@Schema(description = "When the engine was created") String engine_date_created,
			@Schema(description = "The display name, or the name when there is none, in lower case") String low_engine_name,
			@Schema(description = "The user's own permission level (1 owner, 2 edit, 3 read only)", types = "integer") Integer engine_user_permission,
			@Schema(description = "The best permission level the user gets through a group (1 owner, 2 edit, 3 read only)", types = "integer") Integer engine_group_permission,
			@Schema(description = "1 when the user favorited the engine, otherwise 0", types = "integer", requiredMode = REQUIRED) Integer engine_favorite,
			@Schema(description = "Legacy alias of engine_id", requiredMode = REQUIRED) String app_id,
			@Schema(description = "Legacy alias of engine_name") String app_name,
			@Schema(description = "Legacy alias of engine_type") String app_type,
			@Schema(description = "Legacy alias of engine_subtype") String app_subtype,
			@Schema(description = "Legacy alias of engine_cost") String app_cost,
			@Schema(description = "Legacy alias of engine_id", requiredMode = REQUIRED) String database_id,
			@Schema(description = "Legacy alias of engine_name") String database_name,
			@Schema(description = "Legacy alias of engine_type") String database_type,
			@Schema(description = "Legacy alias of engine_discoverable") Boolean database_discoverable,
			@Schema(description = "The engine's description metadata") String engine_description,
			@Schema(description = "The best of the user's own and group permission levels (1 owner, 2 edit, 3 read only); absent for a public engine the user has no grant on", types = "integer") Integer permission) {
	}

	@Schema(description = "What to search the user's insights for")
	public record EngineGetSearchInsightsResultsRequest(
			@Schema(description = "Text to find in the insight names") String searchString,
			@Schema(description = "The most insights to return", types = "integer") String limit,
			@Schema(description = "The insights to skip", types = "integer") String offset,
			@Schema(description = "JSON-encoded filters: {\"app_id\": [project ids], \"tags\": [tags]}; only app_id is applied") String filterData) {
	}

	@Schema(description = "An insight the user can access")
	public record EngineSearchInsightRow(
			@Schema(description = "Legacy alias of project_id", requiredMode = REQUIRED) String app_id,
			@Schema(description = "Legacy alias of project_name") String app_name,
			@Schema(description = "Legacy alias of project_insight_id", requiredMode = REQUIRED) String app_insight_id,
			@Schema(description = "The id of the insight's project", requiredMode = REQUIRED) String project_id,
			@Schema(description = "The name of the insight's project") String project_name,
			@Schema(description = "Whether the project is public") Boolean project_global,
			@Schema(description = "The project's catalog name") String project_catalog_name,
			@Schema(description = "The insight id", requiredMode = REQUIRED) String project_insight_id,
			@Schema(description = "The insight name") String name,
			@Schema(description = "How many times the insight was run", types = "integer") Long view_count,
			@Schema(description = "The insight layout") String layout,
			@Schema(description = "When the insight was created") String created_on,
			@Schema(description = "When the insight was last changed") String last_modified_on,
			@Schema(description = "Whether the insight is cached") Boolean cacheable,
			@Schema(description = "How many minutes the cache lasts", types = "integer") Integer cacheMinutes,
			@Schema(description = "The cron expression that refreshes the cache") String cacheCron,
			@Schema(description = "When the insight was cached") String cachedOn,
			@Schema(description = "Whether the cache is encrypted") Boolean cacheEncrypt,
			@Schema(description = "Whether the insight is public") Boolean insight_global,
			@Schema(description = "The insight's schema name") String insight_schema_name,
			@Schema(description = "The insight name in lower case") String low_name,
			@Schema(description = "The user's own permission level on the insight (1 owner, 2 edit, 3 read only)", types = "integer") Integer insight_permission,
			@Schema(description = "The user's own permission level on the project (1 owner, 2 edit, 3 read only)", types = "integer") Integer project_permission,
			@Schema(description = "The best permission level the user gets on the insight through a group", types = "integer") Integer insight_group_permission,
			@Schema(description = "The best permission level the user gets on the project through a group", types = "integer") Integer project_group_permission,
			@Schema(description = "1 when the user favorited the insight, otherwise 0", types = "integer", requiredMode = REQUIRED) Integer insight_favorite,
			@Schema(description = "The user's effective permission level on the insight (1 owner, 2 edit, 3 read only)", types = "integer") Integer permission) {
	}
}
