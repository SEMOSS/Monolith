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
package prerna.web.conf;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.reactor.apptest.AppTestEnvironment;

class AppTestPreviewServletUnitTests {
    private static final String ID = "10000000-0000-4000-8000-000000000001";
    private static final String SECRET = "20000000-0000-4000-8000-000000000001-30000000-0000-4000-8000-000000000001";

    @Test void rejectsUnknownCapabilitiesWithoutAnOrdinarySessionFallback() throws Exception {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        when(request.getPathInfo()).thenReturn("/" + ID + "/" + SECRET + "/api/config");
        new AppTestPreviewServlet().service(request, response);
        verify(response).sendError(eq(403), anyString());
        verify(request, never()).getSession();
        verify(request, never()).getSession(anyBoolean());
    }

    @Test void servesOnlyTheScopedSdkConfig() throws Exception {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var environment = mock(AppTestEnvironment.class);
        var output = new StringWriter();
        when(request.getPathInfo()).thenReturn("/" + ID + "/" + SECRET + "/api/config");
        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenReturn(new PrintWriter(output));
        when(environment.sdkConfig()).thenReturn(Map.of("security", false));
        try (var contexts = mockStatic(AppTestEnvironment.class)) {
            contexts.when(() -> AppTestEnvironment.authenticate(ID, SECRET)).thenReturn(environment);
            new AppTestPreviewServlet().service(request, response);
            assertTrue(output.toString().contains("\"security\":false"));
            verify(response).setHeader("Cache-Control", "no-store");
            verify(response).setHeader("Referrer-Policy", "no-referrer");
        }
    }

    @Test void keepsUnsupportedOperationErrorsInTheSdkEnvelopeAndAcceptsCharset() throws Exception {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var environment = mock(AppTestEnvironment.class);
        var output = new StringWriter();
        when(request.getPathInfo()).thenReturn("/" + ID + "/" + SECRET + "/api/engine/runPixel");
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded;charset=UTF-8");
        when(request.getInputStream()).thenReturn(body("expression=unsupported"));
        when(response.getWriter()).thenReturn(new PrintWriter(output));
        when(environment.execute("expression=unsupported")).thenThrow(new SecurityException("UNMAPPED_TEST_DATABASE"));
        try (var contexts = mockStatic(AppTestEnvironment.class)) {
            contexts.when(() -> AppTestEnvironment.authenticate(ID, SECRET)).thenReturn(environment);
            new AppTestPreviewServlet().service(request, response);
            assertTrue(output.toString().contains("UNMAPPED_TEST_DATABASE"));
            assertTrue(output.toString().contains("\"operationType\":[\"ERROR\"]"));
        }
    }

    @Test void rejectsOversizedSdkRequestsBeforeExecutingThem() throws Exception {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var environment = mock(AppTestEnvironment.class);
        when(request.getPathInfo()).thenReturn("/" + ID + "/" + SECRET + "/api/engine/runPixel");
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(request.getInputStream()).thenReturn(body("x".repeat(32769)));
        try (var contexts = mockStatic(AppTestEnvironment.class)) {
            contexts.when(() -> AppTestEnvironment.authenticate(ID, SECRET)).thenReturn(environment);
            new AppTestPreviewServlet().service(request, response);
            verify(response).sendError(413);
            verify(environment, never()).execute(anyString());
        }
    }

    private static ServletInputStream body(String value) {
        var stream = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override public int read() { return stream.read(); }
            @Override public boolean isFinished() { return stream.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { }
        };
    }
}
