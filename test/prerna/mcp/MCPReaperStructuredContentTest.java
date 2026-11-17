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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import prerna.reactor.ReactorDefinition;

class MCPReaperStructuredContentTest {

	private static JSONObject wrapped(JSONObject valueSchema) {
		return new JSONObject().put("type", "object")
				.put("properties", new JSONObject().put(ReactorDefinition.WRAPPED_RESULT, valueSchema))
				.put(ReactorDefinition.WRAP_RESULT_MARKER, true);
	}

	private static Map<String, Object> textResult() {
		Map<String, Object> result = new HashMap<>();
		result.put("content", List.of(Map.of("type", "text", "text", "ignored")));
		result.put("isError", false);
		return result;
	}

	@Test
	void wrapsAnArrayOutputUnderResult() {
		Map<String, Object> result = textResult();
		MCPReaper.addStructuredContent(result, "[{\"id\":\"a\"}]", wrapped(new JSONObject().put("type", "array")));

		JSONObject structured = (JSONObject) result.get("structuredContent");
		JSONArray rows = structured.getJSONArray(ReactorDefinition.WRAPPED_RESULT);
		assertEquals("a", rows.getJSONObject(0).getString("id"));
	}

	@Test
	void wrapsAStringOutputAsIs() {
		Map<String, Object> result = textResult();
		MCPReaper.addStructuredContent(result, "123", wrapped(new JSONObject().put("type", "string")));

		assertEquals("123", ((JSONObject) result.get("structuredContent")).get(ReactorDefinition.WRAPPED_RESULT),
				"a string output stays a string even when it looks like a number");
	}

	@Test
	void passesAnObjectOutputThroughForAnObjectSchema() {
		Map<String, Object> result = textResult();
		MCPReaper.addStructuredContent(result, "{\"count\":2}", new JSONObject().put("type", "object"));

		assertEquals(2, ((JSONObject) result.get("structuredContent")).getInt("count"));
	}

	@Test
	void leavesOutputThatDoesNotFitAsTextOnly() {
		Map<String, Object> result = textResult();
		MCPReaper.addStructuredContent(result, "[1, 2]", new JSONObject().put("type", "object"));
		assertFalse(result.containsKey("structuredContent"), "an array is not an object result");

		MCPReaper.addStructuredContent(result, "{\"count\":2}", null);
		assertFalse(result.containsKey("structuredContent"), "a tool without an output schema gets none");
		assertTrue(result.containsKey("content"));
	}
}
