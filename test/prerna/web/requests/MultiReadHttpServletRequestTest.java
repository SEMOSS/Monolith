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
package prerna.web.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

class MultiReadHttpServletRequestTest {

	private static ServletInputStream servletStream(String body) {
		InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
		return new ServletInputStream() {
			@Override
			public int read() throws IOException {
				return in.read();
			}

			@Override
			public boolean isFinished() {
				return false;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
			}
		};
	}

	@Test
	void letsTheBodyBeReadMoreThanOnce() throws IOException {
		HttpServletRequest original = mock(HttpServletRequest.class);
		ServletInputStream body = servletStream("{\"expression\":\"1+1;\"}");
		when(original.getInputStream()).thenReturn(body);
		MultiReadHttpServletRequest wrapper = new MultiReadHttpServletRequest(original);

		String first = IOUtils.toString(wrapper.getInputStream(), StandardCharsets.UTF_8);
		String second = IOUtils.toString(wrapper.getInputStream(), StandardCharsets.UTF_8);
		String third = IOUtils.toString(wrapper.getReader());

		assertEquals("{\"expression\":\"1+1;\"}", first);
		assertEquals(first, second);
		assertEquals(first, third);
		verify(original, times(1)).getInputStream();
	}

	@Test
	void readsAnEmptyBodyAsEmpty() throws IOException {
		HttpServletRequest original = mock(HttpServletRequest.class);
		ServletInputStream body = servletStream("");
		when(original.getInputStream()).thenReturn(body);
		MultiReadHttpServletRequest wrapper = new MultiReadHttpServletRequest(original);

		assertEquals(-1, wrapper.getInputStream().read());
		assertEquals("", IOUtils.toString(wrapper.getReader()));
	}
}
