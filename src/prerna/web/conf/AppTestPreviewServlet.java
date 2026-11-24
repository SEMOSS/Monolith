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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.Gson;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import prerna.reactor.apptest.AppTestEnvironment;
import prerna.reactor.apptest.RegressionPixelAccess;

/** Capability-scoped manual preview. Ordinary API/session requests are never delegated from here. */
public final class AppTestPreviewServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern PATH = Pattern.compile("/(" + UUID + ")/(" + UUID + "-" + UUID + ")/(.*)");
    private static final Gson JSON = new Gson();

    @Override
    public void destroy() {
        AppTestEnvironment.shutdown();
        super.destroy();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        var match = PATH.matcher(request.getPathInfo() == null ? "" : request.getPathInfo());
        if (!match.matches()) { response.sendError(404); return; }
        try {
            AppTestEnvironment environment = AppTestEnvironment.authenticate(match.group(1), match.group(2));
            String path = match.group(3);
            if ("POST".equals(request.getMethod()) && "api/engine/runPixel".equals(path)) {
                if (request.getContentType() == null || !request.getContentType().split(";", 2)[0].trim()
                        .equalsIgnoreCase("application/x-www-form-urlencoded")) {
                    environment.blockedRequest(request.getMethod(), path, "fetch", "TEST_PREVIEW_REQUEST_INVALID");
                    response.sendError(415, "Send an SDK URL-encoded expression; inspect api/test-diagnostics"); return;
                }
                byte[] body = request.getInputStream().readNBytes(RegressionPixelAccess.MAX_REQUEST_BYTES + 1);
                if (body.length > RegressionPixelAccess.MAX_REQUEST_BYTES) {
                    environment.blockedRequest(request.getMethod(), path, "fetch", "TEST_PREVIEW_REQUEST_TOO_LARGE");
                    response.sendError(413); return;
                }
                response.setContentType("application/json;charset=UTF-8");
                try { JSON.toJson(environment.execute(new String(body, StandardCharsets.UTF_8)), response.getWriter()); }
                catch (IllegalArgumentException | SecurityException | IllegalStateException failure) {
                    JSON.toJson(environment.pixelError(), response.getWriter());
                }
            } else if ("GET".equals(request.getMethod()) && "api/config".equals(path)) {
                response.setContentType("application/json;charset=UTF-8");
                JSON.toJson(environment.sdkConfig(), response.getWriter());
            } else if ("GET".equals(request.getMethod()) && "api/test-diagnostics".equals(path)) {
                response.setContentType("application/json;charset=UTF-8");
                JSON.toJson(Map.of("diagnostics", environment.diagnostics()), response.getWriter());
            } else if ("GET".equals(request.getMethod()) && path.startsWith("public_home/")) {
                AppTestEnvironment.Asset asset = environment.asset(path, request.getContextPath());
                if (asset == null) {
                    environment.blockedRequest(request.getMethod(), "/" + path, "ASSET", "TEST_PREVIEW_ASSET_MISSING");
                    response.sendError(404, "Asset missing from the preview snapshot; inspect api/test-diagnostics"); return;
                }
                URI url = URI.create(request.getRequestURL().toString());
                response.setHeader("Content-Security-Policy", environment.contentSecurityPolicy(
                        url.getScheme() + "://" + url.getRawAuthority() + request.getContextPath()));
                response.setContentType(asset.contentType());
                response.getOutputStream().write(asset.bytes());
            } else {
                environment.blockedRequest(request.getMethod(), "/" + path, "request", "TEST_PREVIEW_ENDPOINT_UNSUPPORTED");
                response.sendError(404, "This endpoint is unavailable in the test preview; inspect api/test-diagnostics");
            }
        } catch (SecurityException failure) {
            response.sendError(403, "Test preview expired or was closed. Open a fresh preview from the Testing tab");
        } catch (IllegalArgumentException failure) {
            response.sendError(400, "Invalid test preview request");
        }
    }
}
