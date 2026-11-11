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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import prerna.om.Insight;
import prerna.om.OldInsight;
import prerna.ui.components.api.IPlaySheet;
import prerna.util.Constants;

class DataframeResourceTest {

	private static String body(Response response) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		((StreamingOutput) response.getEntity()).write(out);
		return out.toString(StandardCharsets.UTF_8);
	}

	private static MultivaluedMap<String, String> form(String data) {
		MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
		if (data != null) {
			form.add("data", data);
		}
		return form;
	}

	@Test
	void runsThePlaysheetMethodOfALegacyInsight() throws IOException {
		IPlaySheet playSheet = mock(IPlaySheet.class);
		when(playSheet.doMethod("highlight", Map.of("node", "a"))).thenReturn(Map.of("ok", true));
		OldInsight insight = mock(OldInsight.class);
		when(insight.getPlaySheet()).thenReturn(playSheet);
		DataframeResource resource = new DataframeResource();
		resource.insight = insight;

		Response response = resource.doMethod("highlight", form("{\"node\":\"a\"}"), mock(HttpServletRequest.class));

		assertEquals(200, response.getStatus());
		assertEquals("{\"ok\":true}", body(response));
	}

	@Test
	void passesNoArgumentsWhenTheFormHasNoData() throws IOException {
		IPlaySheet playSheet = mock(IPlaySheet.class);
		when(playSheet.doMethod("refresh", null)).thenReturn("done");
		OldInsight insight = mock(OldInsight.class);
		when(insight.getPlaySheet()).thenReturn(playSheet);
		DataframeResource resource = new DataframeResource();
		resource.insight = insight;

		assertEquals("\"done\"", body(resource.doMethod("refresh", form(null), mock(HttpServletRequest.class))));
	}

	@Test
	void answersThatTheCallDoesNotApplyToAPixelInsight() throws IOException {
		Insight insight = mock(Insight.class);
		DataframeResource resource = new DataframeResource();
		resource.insight = insight;

		Response response = resource.doMethod("highlight", form("{}"), mock(HttpServletRequest.class));

		assertEquals("{\"" + Constants.ERROR_MESSAGE + "\":\"Rest call is not applicable for this insight\"}",
				body(response));
		verifyNoInteractions(insight);
	}

	@Test
	void answersThatTheCallDoesNotApplyWithoutAnInsight() throws IOException {
		Response response = new DataframeResource().doMethod("any", form("{\"a\":1}"), mock(HttpServletRequest.class));

		assertEquals("{\"" + Constants.ERROR_MESSAGE + "\":\"Rest call is not applicable for this insight\"}",
				body(response));
	}
}
