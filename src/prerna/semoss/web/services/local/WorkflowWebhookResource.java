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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.security.PermitAll;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import prerna.auth.AccessToken;
import prerna.auth.AuthProvider;
import prerna.auth.User;
import prerna.om.ThreadStore;
import prerna.util.AssetUtility;
import prerna.util.Constants;
import prerna.web.requests.OverrideParametersServletRequest;

/**
 * REST endpoint for triggering a workflow via webhook.
 *
 * <p>POST /api/workflow/{projectId}/trigger
 *   Header: X-Webhook-Secret: &lt;secret&gt;
 *
 * <p>The secret is stored in the project's workflow-config.json under the key
 * {@code WEBHOOK_SECRET}. Generate it via the {@code GenerateWorkflowWebhookSecret}
 * pixel reactor and store it there. The caller must present the same secret in
 * the {@code X-Webhook-Secret} request header; requests with a missing or wrong
 * secret are rejected with 401.
 *
 * <p>On success the pixel {@code TriggerWorkflow(project=["projectId"])} is
 * executed in scheduler mode and the resulting run summary JSON is returned.
 */
@Path("/workflow")
@PermitAll
public class WorkflowWebhookResource {

    private static final Logger classLogger = LogManager.getLogger(WorkflowWebhookResource.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String WEBHOOK_SECRET_KEY = "WEBHOOK_SECRET";
    private static final String WEBHOOK_USER_KEY = "WEBHOOK_USER";
    private static final String WORKFLOW_CONFIG_FILE = "workflow-config.json";
    private static final String SECRET_HEADER = "X-Webhook-Secret";

    @POST
    @Path("/{projectId}/trigger")
    @Produces("application/json")
    public Response trigger(
            @PathParam("projectId") String projectId,
            @Context HttpServletRequest request) {

        if (projectId == null || projectId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"projectId is required\"}")
                    .build();
        }

        // Validate webhook secret
        String incomingSecret = request.getHeader(SECRET_HEADER);
        if (incomingSecret == null || incomingSecret.isBlank()) {
            classLogger.warn("Webhook request for project {} missing {} header", projectId, SECRET_HEADER);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Missing " + SECRET_HEADER + " header\"}")
                    .build();
        }

        String storedSecret = readWebhookSecret(projectId);
        if (storedSecret == null) {
            classLogger.warn("Webhook request for project {} but no secret is configured", projectId);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Webhook not configured for this workflow\"}")
                    .build();
        }

        if (!constantTimeEquals(incomingSecret, storedSecret)) {
            classLogger.warn("Webhook request for project {} presented invalid secret", projectId);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Invalid webhook secret\"}")
                    .build();
        }

        // Reconstruct the session user from stored webhook user info
        String userAccess = readConfigValue(projectId, WEBHOOK_USER_KEY);
        if (userAccess == null || userAccess.isBlank()) {
            classLogger.warn("Webhook request for project {} has no stored user — secret must be regenerated", projectId);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Webhook user not configured. Regenerate the webhook secret to fix this.\"}")
                    .build();
        }

        HttpSession session = request.getSession(true);
        User webhookUser = new User();
        for (String pair : userAccess.split(",")) {
            String[] parts = pair.split(":", 2);
            if (parts.length == 2) {
                try {
                    AuthProvider provider = AuthProvider.valueOf(parts[0]);
                    AccessToken token = new AccessToken();
                    token.setProvider(provider);
                    token.setId(parts[1]);
                    token.setName("webhook_" + UUID.randomUUID());
                    webhookUser.setAccessToken(token);
                } catch (IllegalArgumentException e) {
                    classLogger.warn("Unknown auth provider in webhook user config: {}", parts[0]);
                }
            }
        }
        session.setAttribute(Constants.SESSION_USER, webhookUser);

        // Execute TriggerWorkflow in scheduler mode
        String pixel = "TriggerWorkflow(project=[\"" + projectId + "\"], triggerType=[\"WEBHOOK\"]);";

        ThreadStore.setSchedulerMode(true);
        NameServer ns = new NameServer();
        OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("expression", pixel);
        requestWrapper.setParameters(paramMap);

        try {
            classLogger.info("Webhook triggered workflow for project {}", projectId);
            return ns.runPixelSync(requestWrapper);
        } catch (Exception e) {
            classLogger.error("Webhook execution failed for project {}: {}", projectId, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } finally {
            try { session.invalidate(); } catch (Exception ignored) {}
        }
    }

    private String readWebhookSecret(String projectId) {
        return readConfigValue(projectId, WEBHOOK_SECRET_KEY);
    }

    private String readConfigValue(String projectId, String key) {
        try {
            String portalsFolder = AssetUtility.getProjectPortalsFolder(projectId);
            File configFile = new File(portalsFolder + "/" + WORKFLOW_CONFIG_FILE);
            if (!configFile.exists()) return null;

            String json = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            List<Map<String, Object>> entries = GSON.fromJson(json,
                    new TypeToken<List<Map<String, Object>>>(){}.getType());
            if (entries == null) return null;

            for (Map<String, Object> entry : entries) {
                if (key.equals(entry.get("key"))) {
                    Object val = entry.get("value");
                    return val != null ? val.toString() : null;
                }
            }
        } catch (Exception e) {
            classLogger.warn("Could not read workflow config for project {}: {}", projectId, e.getMessage());
        }
        return null;
    }

    /** Timing-safe string comparison to prevent timing attacks on the secret. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) return false;
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }
}
