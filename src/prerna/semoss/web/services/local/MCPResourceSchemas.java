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

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The request and responses of {@link MCPResource}, for its OpenAPI
 * annotations. They follow the MCP JSON-RPC 2.0 wire format, but model only the
 * fields Monolith reads and writes.
 */
public final class MCPResourceSchemas {

	private MCPResourceSchemas() {
	}

	@Schema(description = "An MCP JSON-RPC 2.0 request or notification (only the fields Monolith reads)")
	public record ExtMcpCommsRequest(
			@Schema(description = "The JSON-RPC version, \"2.0\"") String jsonrpc,
			@Schema(description = "The request id, echoed in the response; leave it out for a notification (a method starting with \"notifications/\"), which gets no body", types = {
					"integer", "string" }) Object id,
			@Schema(description = "The MCP method, such as initialize, tools/list, tools/call, resources/list, prompts/list or notifications/initialized", requiredMode = REQUIRED) String method,
			@Schema(description = "The method's parameters, whose keys depend on the method: initialize reads protocolVersion; tools/call reads name and arguments") Map<String, Object> params) {
	}

	@Schema(description = "An MCP JSON-RPC 2.0 response (only the fields Monolith writes)")
	public record ExtMcpCommsResponse(
			@Schema(description = "The JSON-RPC version, always \"2.0\"", requiredMode = REQUIRED) String jsonrpc,
			@Schema(description = "The request's id; the string \"null\" when the request could not be parsed or had no id", types = {
					"integer", "string" }, requiredMode = REQUIRED) Object id,
			@Schema(description = "The method's result when it succeeded; its keys depend on the method, as the MCP specification defines them (initialize: protocolVersion, capabilities, serverInfo; tools/list: tools; tools/call: content, isError and, for tools with an output schema, structuredContent)") Map<String, Object> result,
			@Schema(description = "Why the request failed, instead of result") ExtMcpCommsError error) {
	}

	@Schema(description = "An MCP JSON-RPC 2.0 error")
	public record ExtMcpCommsError(
			@Schema(description = "The JSON-RPC error code, such as -32700 (parse error) or -32600 (invalid request)", requiredMode = REQUIRED) int code,
			@Schema(description = "What went wrong", requiredMode = REQUIRED) String message) {
	}
}
