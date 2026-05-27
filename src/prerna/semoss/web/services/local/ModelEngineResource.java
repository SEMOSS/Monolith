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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import prerna.auth.User;
import prerna.om.Insight;
import prerna.om.InsightStore;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.comm.PixelJobStatus;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

@Path("/model-{modelId}")
@PermitAll
public class ModelEngineResource {

	private static final Logger classLogger = LogManager.getLogger(ModelEngineResource.class);

	/**
	 * LLM(engine=[modelId], command=[...], context=[...], useHistory=[...],
	 * paramValues=[{...}], roomId=[...], image=[...], url=[...])
	 */
	@POST
	@Path("/llm")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response llm(@Context HttpServletRequest request, @PathParam("modelId") String modelId) {
		modelId = WebUtility.inputSanitizer(modelId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /llm on model engine '{}'", modelId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		Object command = body.get("command");
		if (command == null || command.toString().trim().isEmpty()) {
			return EngineRouteResource.error("Must pass in 'command' containing the prompt for the model", 400);
		}

		StringBuilder pixel = new StringBuilder("LLM(engine=");
		pixel.append(EngineRouteResource.GSON.toJson(modelId));
		pixel.append(", command=").append(EngineRouteResource.GSON.toJson(command));
		EngineRouteResource.appendIfPresent(pixel, body, "context");
		EngineRouteResource.appendIfPresent(pixel, body, "useHistory");
		EngineRouteResource.appendIfPresent(pixel, body, "paramValues");
		EngineRouteResource.appendIfPresent(pixel, body, "roomId");
		EngineRouteResource.appendIfPresent(pixel, body, "image");
		EngineRouteResource.appendIfPresent(pixel, body, "url");
		pixel.append(")");

		return ResourceUtility.runPixel(request, pixel.toString());
	}

	/**
	 * Streaming variant of {@link #llm(HttpServletRequest, String)}: dispatches the
	 * same {@code LLM(...)} pixel asynchronously and writes each chunk produced by
	 * the model — pulled from {@link PixelJobManager#getStreamOut(String)} —
	 * back to the client as a Server-Sent Events stream.
	 * <p>
	 * Each SSE event payload is the raw {@code Map<String, Object>} from the job's
	 * stream map serialized as JSON (no OpenAI-format translation). The stream
	 * terminates with a {@code data: [DONE]\n\n} event when the underlying job
	 * reaches {@link PixelJobStatus#PROGRESS_COMPLETE}.
	 * <p>
	 * Forces {@code stream=true} into {@code paramValues} so the underlying model
	 * engine is asked to stream rather than return a single response.
	 */
	@POST
	@Path("/llmStreaming")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("text/event-stream")
	public Response llmStreaming(@Context HttpServletRequest request, @PathParam("modelId") String modelId) {
		modelId = WebUtility.inputSanitizer(modelId);

		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return EngineRouteResource.error("User session is invalid", 401);
		}

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /llmStreaming on model engine '{}'", modelId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		Object command = body.get("command");
		if (command == null || command.toString().trim().isEmpty()) {
			return EngineRouteResource.error("Must pass in 'command' containing the prompt for the model", 400);
		}

		// Force stream=true into paramValues so the underlying engine streams
		Map<String, Object> paramValues = new LinkedHashMap<>();
		Object existingParams = body.get("paramValues");
		if (existingParams instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> existingMap = (Map<String, Object>) existingParams;
			paramValues.putAll(existingMap);
		}
		paramValues.put("stream", true);
		body.put("paramValues", paramValues);

		StringBuilder pixel = new StringBuilder("LLM(engine=");
		pixel.append(EngineRouteResource.GSON.toJson(modelId));
		pixel.append(", command=").append(EngineRouteResource.GSON.toJson(command));
		EngineRouteResource.appendIfPresent(pixel, body, "context");
		EngineRouteResource.appendIfPresent(pixel, body, "useHistory");
		EngineRouteResource.appendIfPresent(pixel, body, "paramValues");
		EngineRouteResource.appendIfPresent(pixel, body, "roomId");
		EngineRouteResource.appendIfPresent(pixel, body, "image");
		EngineRouteResource.appendIfPresent(pixel, body, "url");
		pixel.append(");");

		HttpSession session = request.getSession(false);
		final String sessionId = session != null ? session.getId() : null;

		Insight insight = new Insight();
		InsightStore.getInstance().put(insight);
		final String insightId = insight.getInsightId();
		if (sessionId != null) {
			InsightStore.getInstance().addToSessionHash(sessionId, insightId);
		}
		insight.setUser(user);
		user.setZoneId(Utility.getApplicationZoneIdObj());

		final PixelJobManager manager = PixelJobManager.getManager();
		final PixelJobRunner jobRunner = manager.makeJob(insight, sessionId, null);
		final String jobId = jobRunner.getJobId();
		jobRunner.addPixel(pixel.toString());
		Thread.ofVirtual().start(jobRunner);

		final Insight finalInsight = insight;
		final String finalModelId = modelId;
		return Response.ok().header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
				.header("Connection", "keep-alive").entity(new StreamingOutput() {
					@Override
					public void write(OutputStream output) throws IOException, WebApplicationException {
						try (Writer writer = new BufferedWriter(
								new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
							STREAM_LOOP: while (true) {
								PixelJobRunner jt = manager.getJob(jobId);
								List<Map<String, Object>> partial = manager.getStreamOut(jobId);
								PixelJobStatus jobStatus = jt == null ? PixelJobStatus.UNKNOWN_JOB
										: jt.getPixelJobStatus();

								if (partial != null && !partial.isEmpty()) {
									for (Map<String, Object> chunk : partial) {
										writeChunk(writer, chunk);
									}
									writer.flush();
								}

								if (jobStatus == PixelJobStatus.PROGRESS_COMPLETE) {
									// drain any chunks written between our last poll and completion
									List<Map<String, Object>> tail = manager.getStreamOut(jobId);
									if (tail != null && !tail.isEmpty()) {
										for (Map<String, Object> chunk : tail) {
											writeChunk(writer, chunk);
										}
									}
									writer.write("data: [DONE]\n\n");
									writer.flush();
									break STREAM_LOOP;
								}

								try {
									Thread.sleep(100);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
									break STREAM_LOOP;
								}
							}
						} catch (Exception e) {
							classLogger.error("Streaming /llmStreaming response failed for engine '{}' and job '{}'",
									finalModelId, jobId, e);
							throw new WebApplicationException(e, 500);
						} finally {
							// Cleanup runs only after the entire SSE body has been
							// written, so the Insight stays alive for the full
							// duration of the streaming model call.
							ResourceUtility.cleanupAfterPixel(finalInsight, manager, jobId, jobRunner);
						}
					}

					private void writeChunk(Writer writer, Map<String, Object> chunk) throws IOException {
						writer.write("data: ");
						writer.write(EngineRouteResource.GSON.toJson(chunk));
						writer.write("\n\n");
					}
				}).build();
	}

	/**
	 * Embeddings(engine=[modelId], values=[...], paramValues=[{...}])
	 */
	@POST
	@Path("/embeddings")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response embeddings(@Context HttpServletRequest request, @PathParam("modelId") String modelId) {
		modelId = WebUtility.inputSanitizer(modelId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /embeddings on model engine '{}'", modelId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		Object values = body.get("values");
		if (values == null) {
			return EngineRouteResource.error("Must pass in 'values' containing the input(s) to embed", 400);
		}

		StringBuilder pixel = new StringBuilder("Embeddings(engine=");
		pixel.append(EngineRouteResource.GSON.toJson(modelId));
		pixel.append(", values=").append(EngineRouteResource.GSON.toJson(values));
		EngineRouteResource.appendIfPresent(pixel, body, "paramValues");
		pixel.append(")");

		return ResourceUtility.runPixel(request, pixel.toString());
	}

	/**
	 * Vision(engine=[modelId], command=[...], image=[...], paramValues=[{...}])
	 */
	@POST
	@Path("/vision")
	@Consumes({ "application/x-www-form-urlencoded", "application/json" })
	@Produces("application/json;charset=utf-8")
	public Response vision(@Context HttpServletRequest request, @PathParam("modelId") String modelId) {
		modelId = WebUtility.inputSanitizer(modelId);

		Map<String, Object> body;
		try {
			body = ResourceUtility.parseRequestBody(request);
		} catch (IOException e) {
			classLogger.error("Failed to read request body for /vision on model engine '{}'", modelId, e);
			return EngineRouteResource.error("Invalid request body: " + e.getMessage(), 400);
		}

		Object command = body.get("command");
		Object image = body.get("image");
		if (command == null || command.toString().trim().isEmpty()) {
			return EngineRouteResource.error("Must pass in 'command' containing the prompt for the model", 400);
		}
		if (image == null || image.toString().trim().isEmpty()) {
			return EngineRouteResource.error("Must pass in 'image' containing the image location or encoding", 400);
		}

		StringBuilder pixel = new StringBuilder("Vision(engine=");
		pixel.append(EngineRouteResource.GSON.toJson(modelId));
		pixel.append(", command=").append(EngineRouteResource.GSON.toJson(command));
		pixel.append(", image=").append(EngineRouteResource.GSON.toJson(image));
		EngineRouteResource.appendIfPresent(pixel, body, "paramValues");
		pixel.append(")");

		return ResourceUtility.runPixel(request, pixel.toString());
	}

}
