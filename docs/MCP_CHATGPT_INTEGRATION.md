# MCP Server Integration with ChatGPT using Keycloak Authentication

This document describes how to expose the SEMOSS MCP (Model Context Protocol) server to ChatGPT as an external tool provider, secured with Keycloak OAuth 2.0 authentication.

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [What Was Added/Changed](#what-was-addedchanged)
4. [Prerequisites](#prerequisites)
5. [Keycloak Configuration](#keycloak-configuration)
6. [SEMOSS Configuration](#semoss-configuration)
7. [ChatGPT Actions Configuration](#chatgpt-actions-configuration)
8. [API Reference](#api-reference)
9. [User Authentication Flow](#user-authentication-flow)
10. [Verification Guide](#verification-guide)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)

---

## Overview

The SEMOSS Monolith module provides an MCP server that exposes tools via a REST API. This integration allows ChatGPT to:

- Discover available tools via OpenAPI specification
- Authenticate users via Keycloak OAuth 2.0 (login page flow)
- Execute tools with proper user context
- Receive structured responses

**Authentication Flow**: When a user first interacts with the ChatGPT Action, they will be redirected to Keycloak's login page. After successful authentication, ChatGPT can call MCP tools on behalf of the user.

---

## Architecture

```
┌─────────────────┐                    ┌──────────────────┐                    ┌─────────────────────┐
│                 │  1. User asks to   │                  │                    │                     │
│    ChatGPT      │     use a tool     │                  │                    │  SEMOSS MCP Server  │
│   (Custom GPT)  │───────────────────▶│                  │                    │    (Monolith)       │
│                 │                    │                  │                    │                     │
└────────┬────────┘                    │                  │                    └──────────┬──────────┘
         │                             │                  │                               │
         │ 2. Redirect to login        │    Keycloak      │                               │
         │◀────────────────────────────│   (sso.semoss.org)                               │
         │                             │                  │                               │
         │ 3. User logs in             │                  │                               │
         │────────────────────────────▶│                  │                               │
         │                             │                  │                               │
         │ 4. Auth code returned       │                  │                               │
         │◀────────────────────────────│                  │                               │
         │                             │                  │                               │
         │ 5. Exchange code for token  │                  │                               │
         │────────────────────────────▶│                  │                               │
         │                             │                  │                               │
         │ 6. JWT Access Token         │                  │                               │
         │◀────────────────────────────│                  │                               │
         │                             │                  │                               │
         │ 7. Call MCP API with Bearer Token             │                               │
         │───────────────────────────────────────────────────────────────────────────────▶│
         │                             │                  │                               │
         │                             │                  │  8. Validate JWT via JWKS    │
         │                             │◀─────────────────────────────────────────────────│
         │                             │                  │                               │
         │ 9. Tool execution result    │                  │                               │
         │◀──────────────────────────────────────────────────────────────────────────────│
         │                             │                  │                               │
```

---

## What Was Added/Changed

### New Files Created

| File | Description |
|------|-------------|
| `src/prerna/web/conf/KeycloakJWTFilter.java` | Servlet filter that validates Keycloak JWT bearer tokens, extracts user info from claims, and creates user sessions. Supports both `keycloak_` and `generic_` provider configurations. |
| `docs/MCP_CHATGPT_INTEGRATION.md` | This documentation file |

### Modified Files

| File | Changes |
|------|---------|
| `src/prerna/semoss/web/services/local/MCPResource.java` | Added ChatGPT-compatible REST endpoints: `/openapi.json`, `/tools`, `/tools/call`, `/tools/{tool_name}`, `/health` |
| `WebContent/WEB-INF/web.xml` | Added KeycloakJWTFilter configuration for `/api/ext/mcp/*` paths |

---

## Prerequisites

Before starting, ensure you have:

- [x] SEMOSS Monolith deployed and accessible
- [x] Keycloak server configured (e.g., `sso.semoss.org`)
- [x] Existing OAuth client in Keycloak (e.g., `semoss-oauth`)
- [x] `generic_login true` in `social.properties`
- [ ] ChatGPT Plus subscription (required for Custom GPTs with Actions)
- [ ] MCP tools defined in your SEMOSS project (`assets/mcp/py_mcp.json` or `assets/mcp/pixel_mcp.json`)

---

## Keycloak Configuration

### Using Existing Configuration

If you already have Keycloak working with SEMOSS using the `generic_` provider in `social.properties`, **you only need to add ChatGPT redirect URIs** to your existing client.

### Step 1: Add ChatGPT Redirect URIs

1. Log into **Keycloak Admin Console**: `https://sso.semoss.org/admin`
2. Select your realm (e.g., `dev`)
3. Go to **Clients** → **semoss-oauth** (or your existing client)
4. In the **Settings** tab, find **Valid redirect URIs**
5. Add these URIs:
   ```
   https://chat.openai.com/aip/g/*/oauth/callback
   https://chatgpt.com/aip/g/*/oauth/callback
   ```
6. Find **Web origins** and add:
   ```
   https://chat.openai.com
   https://chatgpt.com
   ```
7. Click **Save**

### Step 2: Verify Client Settings

Ensure these settings are configured:

| Setting | Value |
|---------|-------|
| Client authentication | ON |
| Standard flow | ON (required for OAuth login page) |
| Direct access grants | ON (optional, for testing) |

### Step 3: Note Your Client Credentials

You'll need these for ChatGPT configuration:

| Field | Where to Find |
|-------|---------------|
| Client ID | Clients → semoss-oauth → Settings tab |
| Client Secret | Clients → semoss-oauth → Credentials tab |

### Required Token Claims

Your access token should include these claims (verify with [jwt.io](https://jwt.io)):

| Claim | Description | Example |
|-------|-------------|---------|
| `sub` | User ID | `43ee2ed3-acd3-4192-9176-182784ac318c` |
| `iss` | Issuer (realm URL) | `https://sso.semoss.org/realms/dev` |
| `azp` | Authorized party (client ID) | `semoss-oauth` |
| `name` | User's full name | `Mohamed ahmed` |
| `preferred_username` | Username | `mahmed8` |
| `email` | Email address | `mahmed8@deloitte.com` |

---

## SEMOSS Configuration

### Existing Configuration (No Changes Needed)

The JWT filter automatically works with your existing `generic_` configuration in `social.properties`:

```properties
# These settings are already in your social.properties
generic_login true
generic_auth_url https://sso.semoss.org/realms/dev/protocol/openid-connect/auth
generic_token_url https://sso.semoss.org/realms/dev/protocol/openid-connect/token
generic_userinfo_url https://sso.semoss.org/realms/dev/protocol/openid-connect/userinfo
generic_client_id semoss-oauth
generic_secret_key YOUR_CLIENT_SECRET
generic_scope profile email openid
```

The filter detects that `generic_auth_url` contains `/realms/` (Keycloak pattern) and automatically:
1. Extracts the realm URL: `https://sso.semoss.org/realms/dev`
2. Fetches JWKS from: `https://sso.semoss.org/realms/dev/protocol/openid-connect/certs`
3. Validates JWT tokens against Keycloak's public keys

### Alternative: Dedicated Keycloak Configuration

If you prefer a separate configuration for MCP/ChatGPT, add these to `social.properties`:

```properties
keycloak_login true
keycloak_realm_url https://sso.semoss.org/realms/dev
keycloak_auth_url https://sso.semoss.org/realms/dev/protocol/openid-connect/auth
keycloak_token_url https://sso.semoss.org/realms/dev/protocol/openid-connect/token
keycloak_client_id semoss-oauth
keycloak_secret_key YOUR_CLIENT_SECRET
keycloak_scope openid profile email
keycloak_auto_add true
```

**Note**: If both `keycloak_login` and `generic_login` are enabled, `keycloak_` takes priority.

---

## ChatGPT Actions Configuration

### Step 1: Create a Custom GPT

1. Go to [ChatGPT](https://chat.openai.com)
2. Click your profile → **My GPTs** → **Create a GPT**
3. Configure your GPT with a name and description

### Step 2: Add an Action

1. Click **Configure** tab
2. Scroll to **Actions** → **Create new action**

### Step 3: Import or Enter OpenAPI Schema

**Option A: Import from URL** (if server is publicly accessible and you have a valid token)

```
https://your-semoss-server.com/Monolith/api/ext/mcp/{toolbox_id}/openapi.json
```

**Option B: Enter Schema Manually**

Paste this minimal schema and customize:

```json
{
  "openapi": "3.0.0",
  "info": {
    "title": "SEMOSS MCP Tools",
    "version": "1.0.0"
  },
  "servers": [
    {
      "url": "https://your-semoss-server.com/Monolith/api/ext/mcp/{toolbox_id}"
    }
  ],
  "paths": {
    "/tools": {
      "get": {
        "operationId": "listTools",
        "summary": "List available tools",
        "responses": {
          "200": { "description": "List of tools" }
        }
      }
    },
    "/tools/call": {
      "post": {
        "operationId": "callTool",
        "summary": "Execute a tool",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "type": "object",
                "properties": {
                  "name": { "type": "string" },
                  "arguments": { "type": "object" }
                },
                "required": ["name"]
              }
            }
          }
        },
        "responses": {
          "200": { "description": "Tool result" }
        }
      }
    }
  }
}
```

### Step 4: Configure OAuth Authentication

Click **Authentication** → Select **OAuth**

Enter the following values:

| Field | Value |
|-------|-------|
| **Client ID** | `semoss-oauth` |
| **Client Secret** | (from Keycloak Credentials tab) |
| **Authorization URL** | `https://sso.semoss.org/realms/dev/protocol/openid-connect/auth` |
| **Token URL** | `https://sso.semoss.org/realms/dev/protocol/openid-connect/token` |
| **Scope** | `openid profile email` |
| **Token Exchange Method** | `POST request` |

### Step 5: Save and Test

1. Click **Save** on the Action
2. Click **Save** on the GPT (top right)
3. Click **View GPT** to test it

### Step 6: Test the Integration

In the chat, try:

> "List the available tools"

or

> "Call the get_stock_price tool with symbol AAPL"

You should see a **"Sign in to [Your Server]"** button. Click it to authenticate via Keycloak.

---

## API Reference

### Base URL

```
https://your-semoss-server.com/Monolith/api/ext/mcp/{toolbox_id}
```

Where `{toolbox_id}` is the SEMOSS engine or project ID containing your MCP tools.

### Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/health` | No | Health check - verify server is running |
| GET | `/openapi.json` | Yes | OpenAPI 3.0 specification for ChatGPT |
| GET | `/tools` | Yes | List all available MCP tools |
| POST | `/tools/call` | Yes | Execute a tool (generic endpoint) |
| POST | `/tools/{tool_name}` | Yes | Execute a specific tool by name |
| POST | `/comms` | Yes | SSE-based MCP protocol communication |

### Authentication Header

All authenticated endpoints require:

```
Authorization: Bearer <access_token>
```

### Request/Response Formats

#### List Tools Response

```json
{
  "tools": [
    {
      "name": "get_stock_price",
      "description": "Retrieve the current stock price for a ticker symbol",
      "inputSchema": {
        "type": "object",
        "properties": {
          "symbol": {
            "type": "string",
            "title": "Symbol"
          }
        },
        "required": ["symbol"]
      }
    }
  ],
  "_meta": {
    "projectId": "abc123",
    "projectName": "My MCP Project"
  }
}
```

#### Call Tool Request

```json
{
  "name": "get_stock_price",
  "arguments": {
    "symbol": "AAPL"
  }
}
```

#### Call Tool Response (Success)

```json
{
  "content": [
    {
      "type": "text",
      "text": "150.25"
    }
  ],
  "isError": false
}
```

#### Call Tool Response (Error)

```json
{
  "content": [
    {
      "type": "text",
      "text": "Unknown tool: invalid_tool_name"
    }
  ],
  "isError": true,
  "errorCode": -32602
}
```

---

## User Authentication Flow

### What Users Experience

1. **First Interaction**: User asks ChatGPT to use a tool from your MCP server

2. **Sign-in Prompt**: ChatGPT displays a button:
   ```
   ┌─────────────────────────────────────┐
   │  To use this action, you need to   │
   │  sign in to SEMOSS MCP Server      │
   │                                     │
   │  [Sign in with SEMOSS MCP Server]  │
   └─────────────────────────────────────┘
   ```

3. **Keycloak Login**: User clicks the button and sees the Keycloak login page

4. **Authentication**: User enters credentials or uses SSO

5. **Consent Screen** (first time only): User approves access

6. **Redirect Back**: User is redirected back to ChatGPT

7. **Tool Execution**: ChatGPT can now execute tools

### Session Persistence

- Login is **one-time per ChatGPT session**
- ChatGPT automatically handles token refresh
- If token expires and refresh fails, user will be prompted to sign in again

---

## Verification Guide

### 1. Verify Health Endpoint (No Auth Required)

```bash
curl "http://localhost:9090/Monolith/api/ext/mcp/{toolbox_id}/health"
```

**Expected Response:**
```json
{
  "status": "healthy",
  "toolbox_id": "your-toolbox-id",
  "timestamp": 1704067200000
}
```

### 2. Get a Token (for testing)

```bash
# Get access token
TOKEN=$(curl -s -X POST "https://sso.semoss.org/realms/dev/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=semoss-oauth" \
  -d "client_secret=YOUR_CLIENT_SECRET" \
  -d "username=YOUR_USERNAME" \
  -d "password=YOUR_PASSWORD" \
  -d "scope=openid profile email" | jq -r '.access_token')

echo $TOKEN
```

### 3. List Available Tools

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9090/Monolith/api/ext/mcp/{toolbox_id}/tools"
```

**Expected Response:**
```json
{
  "tools": [...],
  "_meta": { ... }
}
```

### 4. Execute a Tool

```bash
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "your_tool_name", "arguments": {"param": "value"}}' \
  "http://localhost:9090/Monolith/api/ext/mcp/{toolbox_id}/tools/call"
```

### 5. Test Authentication Failure

```bash
# Without token - should return 401
curl "http://localhost:9090/Monolith/api/ext/mcp/{toolbox_id}/tools"

# With invalid token - should return 401
curl -H "Authorization: Bearer invalid_token" \
  "http://localhost:9090/Monolith/api/ext/mcp/{toolbox_id}/tools"
```

---

## Troubleshooting

### Common Issues

#### 1. "Authentication required" (401)

**Causes:**
- No Authorization header provided
- `generic_login` is not set to `true` in social.properties

**Solutions:**
- Ensure `Authorization: Bearer <token>` header is included
- Verify `generic_login true` in social.properties
- Restart SEMOSS after configuration changes

#### 2. "Invalid or expired token" (401)

**Causes:**
- Token has expired (default 5 minutes)
- Token was issued by a different Keycloak realm
- JWKS endpoint is unreachable

**Solutions:**
- Get a fresh token
- Verify `generic_auth_url` matches the token's issuer
- Check network connectivity to Keycloak

#### 3. "Toolbox not found" (404)

**Causes:**
- Invalid toolbox_id
- Engine/project doesn't exist

**Solutions:**
- Verify the toolbox_id is a valid SEMOSS engine or project ID
- Check that MCP tools are defined in `assets/mcp/` folder

#### 4. ChatGPT Shows "Failed to fetch" Error

**Causes:**
- CORS not configured
- Server not accessible from internet
- HTTPS certificate issues

**Solutions:**
- Enable CORS filter in web.xml for ChatGPT domains
- Ensure server is publicly accessible
- Use valid SSL certificate

#### 5. CORS Errors

Add to `web.xml`:

```xml
<filter>
   <filter-name>CorsFilter</filter-name>
   <filter-class>org.apache.catalina.filters.CorsFilter</filter-class>
   <init-param>
      <param-name>cors.allowed.origins</param-name>
      <param-value>https://chat.openai.com,https://chatgpt.com</param-value>
   </init-param>
   <init-param>
      <param-name>cors.allowed.methods</param-name>
      <param-value>GET,POST,OPTIONS</param-value>
   </init-param>
   <init-param>
      <param-name>cors.allowed.headers</param-name>
      <param-value>Authorization,Content-Type</param-value>
   </init-param>
</filter>
<filter-mapping>
   <filter-name>CorsFilter</filter-name>
   <url-pattern>/api/ext/mcp/*</url-pattern>
</filter-mapping>
```

### Enable Debug Logging

Add to `log4j2.xml`:

```xml
<Logger name="prerna.web.conf.KeycloakJWTFilter" level="DEBUG"/>
<Logger name="prerna.semoss.web.services.local.MCPResource" level="DEBUG"/>
```

---

## Security Considerations

1. **HTTPS Required**: Always use HTTPS in production
2. **Token Expiration**: Keycloak tokens expire quickly (default 5 minutes)
3. **CORS**: Restrict allowed origins to ChatGPT domains only
4. **Client Secret**: Keep the client secret secure
5. **Audit Logging**: Monitor authentication failures

---

## Quick Reference

### Configuration Checklist

- [ ] ChatGPT redirect URIs added to Keycloak client
- [ ] `generic_login true` in social.properties
- [ ] SEMOSS server restarted after code deployment
- [ ] Health endpoint accessible
- [ ] Token can be obtained from Keycloak
- [ ] Tools endpoint returns data with valid token
- [ ] ChatGPT Action created with OAuth settings

### Important URLs

| Purpose | URL |
|---------|-----|
| Keycloak Admin | `https://sso.semoss.org/admin` |
| Authorization URL | `https://sso.semoss.org/realms/dev/protocol/openid-connect/auth` |
| Token URL | `https://sso.semoss.org/realms/dev/protocol/openid-connect/token` |
| JWKS URL | `https://sso.semoss.org/realms/dev/protocol/openid-connect/certs` |
| MCP Health | `https://your-server/Monolith/api/ext/mcp/{id}/health` |
| MCP Tools | `https://your-server/Monolith/api/ext/mcp/{id}/tools` |

### ChatGPT OAuth Settings

| Field | Value |
|-------|-------|
| Client ID | `semoss-oauth` |
| Client Secret | (from Keycloak) |
| Authorization URL | `https://sso.semoss.org/realms/dev/protocol/openid-connect/auth` |
| Token URL | `https://sso.semoss.org/realms/dev/protocol/openid-connect/token` |
| Scope | `openid profile email` |

---

## Support

For issues or questions:

- **SEMOSS Documentation**: https://semoss.org
- **GitHub Issues**: https://github.com/SEMOSS/Monolith/issues
