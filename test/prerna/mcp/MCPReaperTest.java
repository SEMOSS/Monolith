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
package prerna.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.imageio.ImageIO;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import prerna.auth.User;
import prerna.om.Insight;
import prerna.om.ThreadStore;
import prerna.reactor.agent.mcp.MCPErrorCode;
import prerna.reactor.agent.mcp.MCPUtility;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.comm.PixelJobManager;
import prerna.sablecc2.comm.PixelJobRunner;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.execptions.SemossMCPException;
import prerna.sablecc2.om.nounmeta.NounMetadata;

class MCPReaperTest {

	private static final String TOOLBOX = "toolbox-1";

	@TempDir
	Path insightFolder;

	private Insight insight;
	private String insightId;
	private MockedStatic<PixelJobManager> managerStatic;
	private PixelJobRunner job;
	private final List<String> pixels = new ArrayList<>();
	private Function<String, Object> answers = pixel -> null;

	@BeforeEach
	void prepare() {
		insightId = "mcp-" + UUID.randomUUID();
		insight = mock(Insight.class);
		when(insight.getInsightId()).thenReturn(insightId);
		when(insight.getUser()).thenReturn(mock(User.class));
		when(insight.getInsightFolder()).thenReturn(insightFolder.toString());

		PixelJobManager manager = mock(PixelJobManager.class);
		job = mock(PixelJobRunner.class);
		PixelRunner runner = mock(PixelRunner.class);
		when(manager.makeJob(eq(insightId), eq(insight), anyString(), isNull())).thenReturn(job);
		when(job.getJobId()).thenReturn("job-1");
		when(job.getRunner()).thenReturn(runner);
		doAnswer(inv -> pixels.add(inv.getArgument(0))).when(job).addPixel(anyString());
		when(runner.getResults()).thenAnswer(inv -> {
			Object value = answers.apply(pixels.get(pixels.size() - 1));
			if (value instanceof RuntimeException failure) {
				throw failure;
			}
			return value == null ? List.of() : List.of(new NounMetadata(value, PixelDataType.CONST_STRING));
		});
		managerStatic = mockStatic(PixelJobManager.class);
		managerStatic.when(PixelJobManager::getManager).thenReturn(manager);
	}

	@AfterEach
	void release() {
		managerStatic.close();
		MCPReaper.clearInsightLock(insightId);
		ThreadStore.remove();
	}

	private static final class Exchange {
		private final ByteArrayOutputStream body = new ByteArrayOutputStream();
		private final HttpServletResponse response = mock(HttpServletResponse.class);
		private boolean committed;

		private Exchange() throws IOException {
			ServletOutputStream out = new ServletOutputStream() {
				@Override
				public void write(int b) {
					committed = true;
					body.write(b);
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setWriteListener(WriteListener listener) {
				}
			};
			when(response.getOutputStream()).thenReturn(out);
			when(response.isCommitted()).thenAnswer(inv -> committed);
		}

		private List<JSONObject> messages() {
			List<JSONObject> messages = new ArrayList<>();
			for (String line : body.toString(StandardCharsets.UTF_8).split("\n")) {
				if (!line.isBlank()) {
					messages.add(new JSONObject(line));
				}
			}
			return messages;
		}
	}

	private Exchange http(String... lines) throws IOException {
		Exchange exchange = new Exchange();
		byte[] input = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
		new MCPReaper(insight, "session-1", new ByteArrayInputStream(input), exchange.response, TOOLBOX,
				"http://localhost/api/ext/mcp/" + TOOLBOX + "/comms", Map.of(), 1).run();
		return exchange;
	}

	private JSONObject single(String line) throws IOException {
		List<JSONObject> messages = http(line).messages();
		assertEquals(1, messages.size());
		return messages.get(0);
	}

	private static String call(Object id, String method, JSONObject params) {
		JSONObject request = new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method);
		if (params != null) {
			request.put("params", params);
		}
		return request.toString();
	}

	private static String toolCall(int id, String name, JSONObject arguments) {
		return call(id, "tools/call", new JSONObject().put("name", name).put("arguments", arguments));
	}

	private void toolReturns(Object output, JSONObject outputSchema) {
		JSONObject tool = new JSONObject().put("name", "add");
		if (outputSchema != null) {
			tool.put("outputSchema", outputSchema);
		}
		JSONObject tools = new JSONObject().put("tools", new JSONArray().put(tool));
		answers = pixel -> pixel.startsWith("RunMCPTool") ? output : pixel.startsWith("GetMCPTools") ? tools : null;
	}

	private static JSONObject result(JSONObject message) {
		assertFalse(message.has("error"), message::toString);
		return message.getJSONObject("result");
	}

	private static JSONObject error(JSONObject message) {
		assertFalse(message.has("result"), message::toString);
		return message.getJSONObject("error");
	}

	@Test
	void initializeRunsInitMcpForTheToolboxAndEchoesTheId() throws Exception {
		JSONObject init = new JSONObject().put("protocolVersion", "2025-06-18");
		answers = pixel -> new JSONObject().put("protocolVersion", "2025-06-18");

		JSONObject message = single(call(0, "initialize", new JSONObject().put("protocolVersion", "2025-06-18")));

		assertEquals("2.0", message.getString("jsonrpc"));
		assertEquals(0, message.getInt("id"));
		assertEquals(init.toString(), result(message).toString());
		assertEquals(List.of("InitMCP(project='" + TOOLBOX + "', protocolVersion='2025-06-18');"), pixels);
	}

	@Test
	void initializeWithoutParamsOrProtocolVersionIsAnInvalidRequest() throws Exception {
		JSONObject noParams = error(single(call(1, "initialize", null)));
		JSONObject noVersion = error(single(call(2, "initialize", new JSONObject())));

		assertEquals(MCPErrorCode.INVALID_REQUEST.getCode(), noParams.getInt("code"));
		assertEquals("Invalid Request - Missing required 'params.protocolVersion' field", noParams.getString("message"));
		assertEquals(MCPErrorCode.INVALID_REQUEST.getCode(), noVersion.getInt("code"));
		assertEquals("Invalid Request - Missing required 'protocolVersion' field", noVersion.getString("message"));
		assertTrue(pixels.isEmpty());
	}

	@Test
	void listMethodsRunTheMatchingPixelForTheToolbox() throws Exception {
		answers = pixel -> new JSONObject().put("ran", pixel);

		List<JSONObject> messages = http(call(1, "tools/list", new JSONObject()), call(2, "resources/list", null),
				call(3, "resources/templates/list", null), call(4, "prompts/list", null)).messages();

		assertEquals(4, messages.size());
		assertEquals("GetMCPTools(project='" + TOOLBOX + "');", result(messages.get(0)).getString("ran"));
		assertEquals("GetMCPResources(project='" + TOOLBOX + "');", result(messages.get(1)).getString("ran"));
		assertEquals("GetMCPResourcesTemplates(project='" + TOOLBOX + "');",
				result(messages.get(2)).getString("ran"));
		assertEquals("GetMCPPrompts(project='" + TOOLBOX + "');", result(messages.get(3)).getString("ran"));
		assertEquals(List.of(1, 2, 3, 4), List.of(messages.get(0).getInt("id"), messages.get(1).getInt("id"),
				messages.get(2).getInt("id"), messages.get(3).getInt("id")));
	}

	@Test
	void keepsAStringOrNullIdAsSent() throws Exception {
		answers = pixel -> new JSONObject();

		List<JSONObject> messages = http(call("req-7", "tools/list", null),
				"{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}").messages();

		assertEquals("req-7", messages.get(0).getString("id"));
		assertTrue(messages.get(1).isNull("id"));
	}

	@Test
	void aRequestWithoutAnIdIsAnInvalidRequest() throws Exception {
		JSONObject failure = error(single("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}"));

		assertEquals(MCPErrorCode.INVALID_REQUEST.getCode(), failure.getInt("code"));
		assertEquals("Invalid Request - Missing required 'id' field", failure.getString("message"));
		assertTrue(pixels.isEmpty());
	}

	@Test
	void malformedJsonIsAParseError() throws Exception {
		JSONObject failure = error(single("{\"jsonrpc\":"));

		assertEquals(MCPErrorCode.PARSE_ERROR.getCode(), failure.getInt("code"));
		assertEquals(MCPErrorCode.PARSE_ERROR.getDescription(), failure.getString("message"));
	}

	@Test
	void aParseErrorOrAMissingIdIsAnsweredWithANullId() throws Exception {
		List<JSONObject> messages = http("{\"jsonrpc\":", "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}").messages();

		assertEquals(2, messages.size());
		for (JSONObject message : messages) {
			assertTrue(message.has("id"), message::toString);
			assertTrue(message.isNull("id"), message::toString);
		}
	}

	@Test
	void anUnknownMethodIsMethodNotFound() throws Exception {
		JSONObject message = single(call(3, "completion/complete", new JSONObject()));

		assertEquals(3, message.getInt("id"));
		assertEquals(MCPErrorCode.METHOD_NOT_FOUND.getCode(), error(message).getInt("code"));
		assertTrue(pixels.isEmpty());
	}

	@Test
	void pingIsAnsweredWithAnEmptyResult() throws Exception {
		JSONObject message = single(call(4, "ping", null));

		assertEquals(4, message.getInt("id"));
		assertEquals(0, result(message).length());
		assertTrue(pixels.isEmpty());
	}

	@Test
	void aRequestWithoutAStringMethodIsAnInvalidRequest() throws Exception {
		List<JSONObject> messages = http("{\"jsonrpc\":\"2.0\",\"id\":5}",
				"{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":7}").messages();

		assertEquals(2, messages.size());
		assertEquals(5, messages.get(0).getInt("id"));
		assertEquals(MCPErrorCode.INVALID_REQUEST.getCode(), error(messages.get(0)).getInt("code"));
		assertEquals(6, messages.get(1).getInt("id"));
		assertEquals(MCPErrorCode.INVALID_REQUEST.getCode(), error(messages.get(1)).getInt("code"));
	}

	@Test
	void aToolsCallWithBadParamsIsInvalidParams() throws Exception {
		List<JSONObject> messages = http(call(1, "tools/call", null),
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":\"add\"}",
				call(3, "tools/call", new JSONObject().put("arguments", new JSONObject())),
				call(4, "tools/call", new JSONObject().put("name", 5)),
				call(5, "tools/call", new JSONObject().put("name", "add").put("arguments", "a=1"))).messages();

		assertEquals(5, messages.size());
		for (int i = 0; i < messages.size(); i++) {
			assertEquals(i + 1, messages.get(i).getInt("id"));
			assertEquals(MCPErrorCode.INVALID_PARAMS.getCode(), error(messages.get(i)).getInt("code"));
		}
		assertTrue(pixels.isEmpty());
	}

	@Test
	void aProtocolVersionThatIsNotADateIsInvalidParams() throws Exception {
		List<JSONObject> messages = http(call(1, "initialize", new JSONObject().put("protocolVersion", "latest")),
				call(2, "initialize", new JSONObject().put("protocolVersion", "2025-06-18 beta"))).messages();

		assertEquals(2, messages.size());
		for (JSONObject message : messages) {
			assertEquals(MCPErrorCode.INVALID_PARAMS.getCode(), error(message).getInt("code"));
		}
		assertTrue(pixels.isEmpty());
	}

	@Test
	void aToolNameOutsideTheToolNameCharactersIsInvalidParams() throws Exception {
		List<JSONObject> messages = http(call(1, "tools/call", new JSONObject().put("name", "get weather")),
				call(2, "tools/call", new JSONObject().put("name", "a".repeat(129)))).messages();

		assertEquals(2, messages.size());
		for (JSONObject message : messages) {
			assertEquals(MCPErrorCode.INVALID_PARAMS.getCode(), error(message).getInt("code"));
		}
		assertTrue(pixels.isEmpty());
	}

	@Test
	void aToolNameWithDotsDashesAndUnderscoresRuns() throws Exception {
		answers = pixel -> pixel.startsWith("RunMCPTool") ? "ok" : null;

		result(single(call(1, "tools/call", new JSONObject().put("name", "Get_Oldest-Movies.v2"))));

		assertEquals("RunMCPTool(project='" + TOOLBOX + "', function='Get_Oldest-Movies.v2', paramValues=[{}]);",
				pixels.get(0));
	}

	@Test
	void aToolsCallWithoutArgumentsPassesAnEmptyObject() throws Exception {
		toolReturns("ok", null);

		result(single(call(6, "tools/call", new JSONObject().put("name", "add"))));

		assertEquals("RunMCPTool(project='" + TOOLBOX + "', function='add', paramValues=[{}]);", pixels.get(0));
	}

	@Test
	void aListMethodReadsAJsonStringResultAndReportsAnyOtherAsAnInternalError() throws Exception {
		answers = pixel -> switch (pixel) {
		case "GetMCPTools(project='" + TOOLBOX + "');" -> "{\"tools\":[]}";
		case "GetMCPPrompts(project='" + TOOLBOX + "');" -> "Reactor GetMCPPrompts failed";
		default -> new IllegalStateException("broken resources");
		};

		List<JSONObject> messages = http(call(1, "tools/list", null), call(2, "prompts/list", null),
				call(3, "resources/list", null)).messages();

		assertEquals(3, messages.size());
		assertEquals(0, result(messages.get(0)).getJSONArray("tools").length());
		JSONObject failedReactor = error(messages.get(1));
		assertEquals(MCPErrorCode.INTERNAL_ERROR.getCode(), failedReactor.getInt("code"));
		assertEquals("Reactor GetMCPPrompts failed", failedReactor.getString("message"));
		JSONObject failedPixel = error(messages.get(2));
		assertEquals(MCPErrorCode.INTERNAL_ERROR.getCode(), failedPixel.getInt("code"));
		assertEquals("broken resources", failedPixel.getString("message"));
	}

	@Test
	void aNotificationWithParamsThatAreNotAnObjectIsStillAccepted() throws Exception {
		Exchange exchange = http("{\"method\":\"notifications/cancelled\",\"params\":\"3\"}",
				"{\"method\":\"notifications/progress\",\"params\":[1]}");

		assertTrue(exchange.messages().isEmpty());
	}

	@Test
	void aNotificationIsAcknowledgedWith202AndNoBody() throws Exception {
		Exchange exchange = http("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

		verify(exchange.response).setStatus(HttpServletResponse.SC_ACCEPTED);
		verify(exchange.response).setContentLength(0);
		verify(exchange.response, never()).getOutputStream();
		assertTrue(pixels.isEmpty());
	}

	@Test
	void everyNotificationKindIsAcceptedWithoutAResponse() throws Exception {
		Exchange exchange = http("{\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3}}",
				"{\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}",
				"{\"method\":\"notifications/message\",\"params\":{\"level\":\"info\"}}",
				"{\"method\":\"notifications/resources/updated\"}", "{\"method\":\"notifications/tools/list_changed\"}",
				"{\"method\":\"notifications/prompts/list_changed\"}", "{\"method\":\"notifications/unknown\"}");

		assertTrue(exchange.messages().isEmpty());
		assertTrue(pixels.isEmpty());
	}

	@Test
	void aJsonResponseIsSentAsAnUncachedOkBody() throws Exception {
		answers = pixel -> new JSONObject();

		Exchange exchange = http(call(1, "tools/list", null));

		verify(exchange.response).setStatus(HttpServletResponse.SC_OK);
		verify(exchange.response).setContentType("application/json");
		verify(exchange.response).setHeader("Cache-Control", "no-cache");
		verify(exchange.response, never()).setStatus(HttpServletResponse.SC_ACCEPTED);
	}

	@Test
	void toolsCallPassesTheArgumentsAsOneParamValuesEntry() throws Exception {
		toolReturns("3", null);

		single(toolCall(5, "add", new JSONObject().put("a", 1)));

		assertEquals("RunMCPTool(project='" + TOOLBOX + "', function='add', paramValues=[{\"a\":1}]);", pixels.get(0));
	}

	@Test
	void toolsCallReturnsTheOutputAsOneTextBlock() throws Exception {
		toolReturns("334.07", null);

		JSONObject result = result(single(toolCall(5, "add", new JSONObject())));

		assertFalse(result.getBoolean("isError"));
		JSONArray content = result.getJSONArray("content");
		assertEquals(1, content.length());
		assertEquals("text", content.getJSONObject(0).getString("type"));
		assertEquals("334.07", content.getJSONObject(0).getString("text"));
		assertFalse(result.has("structuredContent"));
	}

	@Test
	void toolsCallAddsStructuredContentWhenTheToolDeclaresAnOutputSchema() throws Exception {
		toolReturns("{\"count\":2}", new JSONObject().put("type", "object"));

		JSONObject result = result(single(toolCall(5, "add", new JSONObject())));

		assertEquals(2, result.getJSONObject("structuredContent").getInt("count"));
		assertEquals("{\"count\":2}", result.getJSONArray("content").getJSONObject(0).getString("text"));
	}

	@Test
	void toolsCallReportsAnMcpErrorWithItsCode() throws Exception {
		answers = pixel -> new SemossMCPException("Missing argument 'a'", MCPErrorCode.INVALID_PARAMS);

		JSONObject failure = error(single(toolCall(5, "add", new JSONObject())));

		assertEquals(MCPErrorCode.INVALID_PARAMS.getCode(), failure.getInt("code"));
		assertEquals("Missing argument 'a'", failure.getString("message"));
	}

	@Test
	void toolsCallReportsAnyOtherFailureAsToolExecutionFailed() throws Exception {
		answers = pixel -> new IllegalStateException("engine offline");

		JSONObject failure = error(single(toolCall(5, "add", new JSONObject())));

		assertEquals(MCPErrorCode.TOOL_EXECUTION_FAILED.getCode(), failure.getInt("code"));
		assertEquals("engine offline", failure.getString("message"));
	}

	/** The real job runner leaves the insight's user on the thread it runs on. */
	private void jobLeavesItsUserOnTheThread() {
		doAnswer(inv -> {
			ThreadStore.setUser(insight.getUser());
			return null;
		}).when(job).run();
	}

	@Test
	void clearsTheRequestThreadOnceTheHttpStreamEnds() throws Exception {
		jobLeavesItsUserOnTheThread();
		toolReturns("3", null);

		single(toolCall(5, "add", new JSONObject()));

		verify(job, atLeastOnce()).run();
		assertNull(ThreadStore.getUser());
	}

	private static String multimodal(JSONArray blocks) {
		return new JSONObject().put(MCPUtility.SEMOSS_MULTIMODAL_TOOL_RESPONSE_KEY, blocks).toString();
	}

	@Test
	void toolsCallTurnsAMultimodalResponseIntoTextAndImageBlocks() throws Exception {
		Path image = Files.createDirectories(insightFolder.resolve("out")).resolve("chart.png");
		ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
		toolReturns(multimodal(new JSONArray().put(new JSONObject().put("type", "text").put("text", "Here it is"))
				.put(new JSONObject().put("type", "image").put("image", new JSONArray().put("out/chart.png")))), null);

		JSONObject result = result(single(toolCall(5, "add", new JSONObject())));

		assertFalse(result.getBoolean("isError"));
		JSONArray content = result.getJSONArray("content");
		assertEquals("Here it is", content.getJSONObject(0).getString("text"));
		JSONObject picture = content.getJSONObject(1);
		assertEquals("image", picture.getString("type"));
		assertEquals("image/png", picture.getString("mimeType"));
		assertEquals(Base64.getEncoder().encodeToString(Files.readAllBytes(image)), picture.getString("data"));
		assertFalse(result.has("structuredContent"));
	}

	private String multimodalError(JSONArray blocks) throws IOException {
		toolReturns(multimodal(blocks), null);
		JSONObject result = result(single(toolCall(5, "add", new JSONObject())));
		assertTrue(result.getBoolean("isError"));
		return result.getJSONArray("content").getJSONObject(0).getString("text");
	}

	@Test
	void toolsCallRefusesAnImageOutsideTheInsightFolder() throws Exception {
		Files.writeString(insightFolder.getParent().resolve("elsewhere.png"), "x");

		assertEquals("The tool returned an image that is unavailable or outside its execution folder.",
				multimodalError(new JSONArray()
						.put(new JSONObject().put("type", "image").put("image", "../elsewhere.png"))));
		assertEquals("The tool returned an image that is unavailable or outside its execution folder.",
				multimodalError(new JSONArray().put(new JSONObject().put("type", "image").put("image", "missing.png"))));
	}

	@Test
	void toolsCallRefusesAFileThatIsNotAnImage() throws Exception {
		Files.writeString(insightFolder.resolve("notes.txt"), "plain text");

		assertEquals(
				"External MCP currently supports image attachments only; the referenced file is not an image.",
				multimodalError(new JSONArray().put(new JSONObject().put("type", "image").put("image", "notes.txt"))));
	}

	@Test
	void toolsCallReportsMalformedMultimodalResponses() throws Exception {
		assertEquals("Tool returned an empty multimodal response.", multimodalError(new JSONArray()));
		assertEquals("Tool returned an invalid multimodal response.", multimodalError(new JSONArray().put("text")));
		assertEquals("Tool returned an invalid multimodal text block.",
				multimodalError(new JSONArray().put(new JSONObject().put("type", "text").put("text", 5))));
		assertEquals("Tool returned an invalid multimodal image block.",
				multimodalError(new JSONArray().put(new JSONObject().put("type", "image").put("image", new JSONArray()))));
		assertEquals("External MCP currently supports text and image result blocks only.",
				multimodalError(new JSONArray().put(new JSONObject().put("type", "audio"))));
	}

	@Test
	void aMultimodalKeyThatIsNotAListIsAnError() throws Exception {
		toolReturns(new JSONObject().put(MCPUtility.SEMOSS_MULTIMODAL_TOOL_RESPONSE_KEY, "nope").toString(), null);

		JSONObject result = result(single(toolCall(5, "add", new JSONObject())));

		assertTrue(result.getBoolean("isError"));
		assertEquals("Tool returned an invalid multimodal response.",
				result.getJSONArray("content").getJSONObject(0).getString("text"));
	}

	private static final class SseCapture {
		private final Sse sse = mock(Sse.class);
		private final SseEventSink sink = mock(SseEventSink.class);
		private final OutboundSseEvent.Builder builder = mock(OutboundSseEvent.Builder.class, RETURNS_SELF);
		private final ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);

		private SseCapture() {
			when(sse.newEventBuilder()).thenReturn(builder);
			when(builder.build()).thenReturn(mock(OutboundSseEvent.class));
		}
	}

	@Test
	void sseModeSendsOneEventAndClosesTheSink() throws Exception {
		answers = pixel -> new JSONObject().put("tools", new JSONArray());
		SseCapture capture = new SseCapture();

		new MCPReaper(insight, "session-1", new BufferedReader(new StringReader(call(9, "tools/list", null) + "\n")),
				capture.sink, capture.sse, TOOLBOX, "http://localhost", null).run();

		verify(capture.builder).data(eq(String.class), capture.data.capture());
		JSONObject message = new JSONObject((String) capture.data.getValue());
		assertEquals(9, message.getInt("id"));
		assertEquals(0, result(message).getJSONArray("tools").length());
		verify(capture.sink).send(any(OutboundSseEvent.class));
		verify(capture.sink).close();
	}

	@Test
	void clearsTheSseWorkerOnceTheStreamEnds() throws Exception {
		jobLeavesItsUserOnTheThread();
		answers = pixel -> new JSONObject().put("tools", new JSONArray());
		SseCapture capture = new SseCapture();

		new MCPReaper(insight, "session-1", new BufferedReader(new StringReader(call(9, "tools/list", null) + "\n")),
				capture.sink, capture.sse, TOOLBOX, "http://localhost", null).run();

		verify(job, atLeastOnce()).run();
		assertNull(ThreadStore.getUser());
	}

	@Test
	void sseModeSendsNothingForANotificationButStillClosesTheSink() throws Exception {
		SseCapture capture = new SseCapture();

		new MCPReaper(insight, "session-1",
				new BufferedReader(new StringReader("{\"method\":\"notifications/initialized\"}\n")), capture.sink,
				capture.sse, TOOLBOX, "http://localhost", null).run();

		verify(capture.sink, never()).send(any());
		verify(capture.sink).close();
	}

	@Test
	void sseModeReportsAReadFailureAsAnErrorResult() throws Exception {
		SseCapture capture = new SseCapture();
		BufferedReader broken = new BufferedReader(new StringReader("")) {
			@Override
			public String readLine() throws IOException {
				throw new IOException("stream reset");
			}
		};

		new MCPReaper(insight, "session-1", broken, capture.sink, capture.sse, TOOLBOX, "http://localhost", null).run();

		verify(capture.builder).data(eq(String.class), capture.data.capture());
		JSONObject result = result(new JSONObject((String) capture.data.getValue()));
		assertTrue(result.getBoolean("isError"));
		assertEquals("stream reset", result.getJSONObject("content").getString("text"));
		verify(capture.sink).close();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, ?> locks() throws Exception {
		Field field = MCPReaper.class.getDeclaredField("INSIGHT_LOCKS");
		field.setAccessible(true);
		return (Map<String, ?>) field.get(null);
	}

	@Test
	void clearInsightLockDropsTheLockCreatedForAnInsight() throws Exception {
		answers = pixel -> new JSONObject();
		http(call(1, "tools/list", null));
		assertTrue(locks().containsKey(insightId));

		MCPReaper.clearInsightLock(insight);
		assertFalse(locks().containsKey(insightId));

		http(call(2, "tools/list", null));
		MCPReaper.clearInsightLock(insightId);
		assertFalse(locks().containsKey(insightId));

		MCPReaper.clearInsightLock((Insight) null);
		MCPReaper.clearInsightLock((String) null);
	}
}
