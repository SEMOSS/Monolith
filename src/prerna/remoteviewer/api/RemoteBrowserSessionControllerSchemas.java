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
package prerna.remoteviewer.api;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The requests and responses of {@link RemoteBrowserSessionController}, for its
 * OpenAPI annotations.
 */
public final class RemoteBrowserSessionControllerSchemas {

	private RemoteBrowserSessionControllerSchemas() {
	}

	@Schema(description = "The browser session to create")
	public record BrowserSessionsCreateRequest(
			@Schema(description = "The URL to open; required unless preserveExisting is true") String url,
			@Schema(description = "The viewport width in pixels; the server default when absent or 0") Integer viewportWidth,
			@Schema(description = "The viewport height in pixels; the server default when absent or 0") Integer viewportHeight,
			@Schema(description = "True to allow leaving out url, keeping whatever page the user's reusable browser session shows") Boolean preserveExisting) {
	}

	@Schema(description = "A created browser session")
	public record BrowserSessionsCreateResponse(
			@Schema(description = "The session id", requiredMode = REQUIRED) String sessionId,
			@Schema(description = "The context-relative WebSocket path that streams the session, /browserSocket/{sessionId}", requiredMode = REQUIRED) String webSocketUrl,
			@Schema(description = "The session's viewport", requiredMode = REQUIRED) BrowserSessionsViewport viewport,
			@Schema(description = "The URL the browser is on; empty when it cannot be read") String currentUrl) {
	}

	@Schema(description = "A browser viewport")
	public record BrowserSessionsViewport(@Schema(description = "The width in pixels", requiredMode = REQUIRED) int width,
			@Schema(description = "The height in pixels", requiredMode = REQUIRED) int height,
			@Schema(description = "The device scale factor", requiredMode = REQUIRED) double deviceScaleFactor) {
	}

	@Schema(description = "An interaction recorded in a browser session")
	public record BrowserSessionsRecordedStep(@Schema(description = "The kind of interaction") String type,
			@Schema(description = "The page URL") String url,
			@Schema(description = "The CSS selector of the target element") String selector,
			@Schema(description = "The text typed or the element's text") String text,
			@Schema(description = "The target element's ARIA role") String role,
			@Schema(description = "Where the interaction happened") BrowserSessionsRecordedStepCoordinates coordinates,
			@Schema(description = "The viewport the coordinates are relative to") BrowserSessionsViewport viewport,
			@Schema(description = "When the step was recorded, in epoch milliseconds", requiredMode = REQUIRED) long timestamp) {
	}

	@Schema(description = "A point in the viewport")
	public record BrowserSessionsRecordedStepCoordinates(@Schema(description = "The x coordinate in pixels", requiredMode = REQUIRED) double x,
			@Schema(description = "The y coordinate in pixels", requiredMode = REQUIRED) double y) {
	}

	@Schema(description = "A replayable Playwright recording")
	public record BrowserSessionsRecording(@Schema(description = "The recording format version, \"1.0\"", requiredMode = REQUIRED) String version,
			@Schema(description = "The recording's metadata", requiredMode = REQUIRED) BrowserSessionsRecordingMeta meta,
			@Schema(description = "The steps by tab id (such as \"tab-1\"), each a list of pages, each a list of steps", requiredMode = REQUIRED) Map<String, List<List<BrowserSessionsPlaywrightStep>>> steps) {
	}

	@Schema(description = "A recording's metadata")
	public record BrowserSessionsRecordingMeta(@Schema(description = "The recording's stable id") String id,
			@Schema(description = "A short title") String title,
			@Schema(description = "A longer description") String description,
			@Schema(description = "When the recording was created, in epoch milliseconds") Long createdAt,
			@Schema(description = "When the recording was last updated, in epoch milliseconds") Long updatedAt,
			@Schema(description = "What the recording is for") String intent) {
	}

	@Schema(description = "One step of a Playwright recording")
	public record BrowserSessionsPlaywrightStep(@Schema(description = "The step id", requiredMode = REQUIRED) int id,
			@Schema(description = "The kind of action", allowableValues = { "NAVIGATE", "CLICK", "TYPE", "SCROLL", "WAIT",
					"CONTEXT", "HOVER" }) String type,
			@Schema(description = "The URL to open, for NAVIGATE steps") String url,
			@Schema(description = "Where to act, for CLICK, TYPE, SCROLL and HOVER steps") BrowserSessionsPlaywrightCoords coords,
			@Schema(description = "The points that outline an area, for CONTEXT steps") List<BrowserSessionsPlaywrightCoords> multiCoords,
			@Schema(description = "The prompt, for CONTEXT steps") String prompt,
			@Schema(description = "The text to type, for TYPE steps") String text,
			@Schema(description = "Whether to press Enter after typing, for TYPE steps") Boolean pressEnter,
			@Schema(description = "The vertical scroll amount, for SCROLL steps") Integer deltaY,
			@Schema(description = "The load state to wait for, for NAVIGATE steps") String waitUntil,
			@Schema(description = "How long to wait after the action, in milliseconds") Integer waitAfterMs,
			@Schema(description = "The viewport the coordinates are relative to") BrowserSessionsViewport viewport,
			@Schema(description = "When the step was recorded, in epoch milliseconds") Long timestamp,
			@Schema(description = "A human-readable label") String label,
			@Schema(description = "A longer description") String description,
			@JsonProperty("isPassword") @Schema(description = "True when the typed text is a password", requiredMode = REQUIRED) boolean isPassword,
			@Schema(description = "True when the typed value should be stored as a variable", requiredMode = REQUIRED) boolean storeValue,
			@Schema(description = "How to find the target element") BrowserSessionsPlaywrightSelector selector,
			@JsonProperty("isTriggerNewTab") @Schema(description = "Whether the action opens a new tab") BrowserSessionsPlaywrightTriggerNewTab isTriggerNewTab,
			@Schema(description = "Whether the step runs on replay") Boolean shouldRun,
			@Schema(description = "Whether the step is mandatory") Boolean required,
			@Schema(description = "Whether the context is sent to the playground, for CONTEXT steps") Boolean sendToPlayground,
			@Schema(description = "The target element's tag") String tag) {
	}

	@Schema(description = "A point in the viewport, in whole pixels")
	public record BrowserSessionsPlaywrightCoords(@Schema(description = "The x coordinate", requiredMode = REQUIRED) int x,
			@Schema(description = "The y coordinate", requiredMode = REQUIRED) int y) {
	}

	@Schema(description = "How a step finds its target element")
	public record BrowserSessionsPlaywrightSelector(
			@Schema(description = "The selector strategy, such as id, css, xpath, text, role, testId, placeholder or label") String strategy,
			@Schema(description = "The selector value") String value,
			@Schema(description = "The CSS selector of the iframe holding the element; absent for the main page") String frameSelector) {
	}

	@Schema(description = "Whether a step opens a new tab")
	public record BrowserSessionsPlaywrightTriggerNewTab(
			@JsonProperty("isTrue") @Schema(description = "True when the step opens a new tab", requiredMode = REQUIRED) boolean isTrue,
			@Schema(description = "The new tab's id") String tabId) {
	}

	@Schema(description = "Where to save a session's recording")
	public record BrowserSessionsSaveRecordingRequest(
			@Schema(description = "The project whose recordings folder gets the file", requiredMode = REQUIRED) String project,
			@Schema(description = "The file name; .json is added when missing", requiredMode = REQUIRED) String name,
			@Schema(description = "The recording's title") String title,
			@Schema(description = "The recording's description") String description,
			@Schema(description = "What the recording is for") String intent) {
	}

	@Schema(description = "A saved recording")
	public record BrowserSessionsSaveRecordingResult(
			@Schema(description = "The saved file's absolute path on the server", requiredMode = REQUIRED) String filePath,
			@Schema(description = "The saved file's name", requiredMode = REQUIRED) String fileName,
			@Schema(description = "The project it was saved in", requiredMode = REQUIRED) String project,
			@Schema(description = "Always true", requiredMode = REQUIRED) boolean saved) {
	}

	@Schema(description = "The outcome of closing a browser session")
	public record BrowserSessionsCloseResult(
			@Schema(description = "\"Session closed\", or \"Session not found or already closed\"", requiredMode = REQUIRED) String message) {
	}
}
