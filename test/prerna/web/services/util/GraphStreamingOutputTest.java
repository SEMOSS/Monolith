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
package prerna.web.services.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openrdf.model.Statement;
import org.openrdf.model.impl.LiteralImpl;
import org.openrdf.model.impl.StatementImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.query.GraphQueryResult;
import org.openrdf.query.QueryEvaluationException;

class GraphStreamingOutputTest {

	private static Statement statement(String object) {
		return new StatementImpl(new URIImpl("http://semoss.org/s"), new URIImpl("http://semoss.org/p"),
				new LiteralImpl(object));
	}

	private static List<Object> readAll(byte[] bytes) throws Exception {
		List<Object> objects = new ArrayList<>();
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
			while (true) {
				objects.add(in.readObject());
			}
		} catch (EOFException e) {
			return objects;
		}
	}

	@Test
	void writesEveryStatementThenTheNullSentinel() throws Exception {
		GraphQueryResult result = mock(GraphQueryResult.class);
		when(result.hasNext()).thenReturn(true, true, false);
		when(result.next()).thenReturn(statement("a"), statement("b"));
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		new GraphStreamingOutput(result).write(out);

		List<Object> objects = readAll(out.toByteArray());
		assertEquals(3, objects.size());
		assertEquals(statement("a"), objects.get(0));
		assertEquals(statement("b"), objects.get(1));
		assertEquals("null", objects.get(2));
	}

	@Test
	void stopsWithoutTheSentinelWhenTheQueryFails() throws Exception {
		GraphQueryResult result = mock(GraphQueryResult.class);
		when(result.hasNext()).thenReturn(true, true);
		when(result.next()).thenReturn(statement("a")).thenThrow(new QueryEvaluationException("boom"));
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		new GraphStreamingOutput(result).write(out);

		List<Object> objects = readAll(out.toByteArray());
		assertEquals(List.of(statement("a")), objects);
		assertFalse(objects.contains("null"));
	}
}
