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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The inputs and responses of {@link SchedulerResource}, for its OpenAPI
 * annotations.
 */
public final class SchedulerResourceSchemas {

	private SchedulerResourceSchemas() {
	}

	@Schema(description = "The form a scheduled job's pixel is run with")
	public record ScheduleExecutePixelRequest(
			@Schema(description = "The pixel to run; a missing trailing semicolon is added", requiredMode = REQUIRED) String pixel) {
	}

	@Schema(description = "The results of a pixel run")
	public record ScheduleExecutePixelResult(
			@Schema(description = "The id of the insight the pixel ran in", requiredMode = REQUIRED) String insightID,
			@Schema(description = "One result for each step of the pixel", requiredMode = REQUIRED) List<ScheduleExecutePixelStep> pixelReturn) {
	}

	@Schema(description = "The result of one step of a pixel")
	public record ScheduleExecutePixelStep(
			@Schema(description = "The id of the step") String pixelId,
			@Schema(description = "The step's pixel expression") String pixelExpression,
			@Schema(description = "Whether the step is a meta step, not shown in the recipe") Boolean isMeta,
			@Schema(description = "How long the step took to run, in milliseconds") Long timeToRun,
			@Schema(description = "What the step returned; its shape depends on the reactor and on operationType") Object output,
			@Schema(description = "What kind of result the step returned, such as CODE_EXECUTION or ERROR") List<String> operationType,
			@Schema(description = "Further results the step sent, such as messages, each in the same shape as a step") List<Map<String, Object>> additionalOutput) {
	}

	@Schema(description = "A failure to collect the pixel's results")
	public record ScheduleExecutePixelError(
			@Schema(description = "Always \"unknown\"", requiredMode = REQUIRED) String errorType,
			@Schema(description = "Why the results could not be collected") String message) {
	}
}
