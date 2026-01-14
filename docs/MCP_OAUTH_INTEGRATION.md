# MCP OAuth Integration for ChatGPT

This document explains how to integrate your MCP (Model Context Protocol) tools with ChatGPT using OAuth authentication.

## Overview

The implementation follows the [OpenAI Apps SDK OAuth pattern](https://developers.openai.com/apps-sdk/plan/components) to allow ChatGPT to authenticate users via standard OAuth providers (Google, GitHub, Microsoft, etc.) and access your MCP tools.

## Architecture

### Endpoints Added to `MCPResource.java`

1. **`GET /ext/mcp/oauth/authorize`** - OAuth initiation endpoint
   - Called by OpenAI/ChatGPT to start the OAuth flow
   - Redirects the user to the OAuth provider (Google, GitHub, etc.)
   - Stores state in session for CSRF protection

2. **`GET /ext/mcp/oauth/callback/{provider}`** - OAuth callback endpoint
   - Receives the redirect from the OAuth provider after user authenticates
   - Exchanges authorization code for access token
   - Fetches user info from the provider
   - Creates/updates user session
   - Returns user info to OpenAI

3. **`GET /ext/mcp/oauth/userinfo`** - User info endpoint
   - Returns authenticated user information
   - Called by OpenAI to verify user is still authenticated

## Configuration

### 1. Configure OAuth Provider in `social.properties`

Add or update your OAuth provider configuration. Example for Google:

```properties
# Google OAuth Configuration
google_client_id=YOUR_GOOGLE_CLIENT_ID
google_secret_key=YOUR_GOOGLE_CLIENT_SECRET
google_redirect_uri=https://yourdomain.com/Monolith/api/ext/mcp/oauth/callback/google
google_auth_url=https://accounts.google.com/o/oauth2/v2/auth
google_token_url=https://oauth2.googleapis.com/token
google_userinfo_url=https://www.googleapis.com/oauth2/v3/userinfo
google_scope=openid email profile
google_beanProps=name,sub,email
google_jsonPattern=
google_auto_add=true
google_sanitizeUserResponse=false
```

### 2. Configure OpenAI App

In your OpenAI app configuration (when you create the MCP integration):

**OAuth Configuration:**
- **Authorization URL**: `https://yourdomain.com/Monolith/api/ext/mcp/oauth/authorize?provider=google&toolbox_id={toolbox_id}`
- **Token URL**: Not needed (we handle token exchange server-side)
- **Callback URL**: `https://yourdomain.com/Monolith/api/ext/mcp/oauth/callback/google`
- **User Info URL**: `https://yourdomain.com/Monolith/api/ext/mcp/oauth/userinfo`
- **Scopes**: `openid email profile`

### 3. OAuth Provider Setup

#### Google OAuth
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing
3. Enable Google+ API
4. Create OAuth 2.0 credentials
5. Add authorized redirect URI: `https://yourdomain.com/Monolith/api/ext/mcp/oauth/callback/google`
6. Copy Client ID and Client Secret to `social.properties`

#### GitHub OAuth
1. Go to [GitHub Developer Settings](https://github.com/settings/developers)
2. Create a new OAuth App
3. Set Authorization callback URL: `https://yourdomain.com/Monolith/api/ext/mcp/oauth/callback/github`
4. Copy Client ID and Client Secret to `social.properties`

```properties
github_client_id=YOUR_GITHUB_CLIENT_ID
github_secret_key=YOUR_GITHUB_CLIENT_SECRET
github_redirect_uri=https://yourdomain.com/Monolith/api/ext/mcp/oauth/callback/github
github_auth_url=https://github.com/login/oauth/authorize
github_token_url=https://github.com/login/oauth/access_token
github_userinfo_url=https://api.github.com/user
github_scope=read:user user:email
github_beanProps=name,id,email
github_jsonPattern=
github_auto_add=true
```

## OAuth Flow

```
User (ChatGPT)                    Your Server              OAuth Provider
      |                                 |                         |
      |-- 1. Click "Connect" --------->|                         |
      |<-- Redirect to provider --------|                         |
      |                                 |                         |
      |-- 2. Authenticate ------------->|                         |
      |                                 |                         |
      |<-- 3. Redirect with code -------|                         |
      |                                 |                         |
      |-- 4. Code to your callback ---->|                         |
      |                                 |-- 5. Exchange code ---->|
      |                                 |<-- Access token ---------|
      |                                 |-- 6. Get user info ----->|
      |                                 |<-- User details ---------|
      |<-- 7. User authenticated -------|                         |
      |                                 |                         |
      |-- 8. Use MCP tools ------------>|                         |
```

## MCP Tools Configuration

MCP tools are defined by engine/app:

```java
// Tools are resolved from engine configuration
GetMCPTools(engine=<engineId>)
```

When connecting ChatGPT to your MCP server, include the project/engine ID in the URL:

```
https://yourdomain.com/Monolith/api/ext/mcp/comms?projectId=<YOUR_PROJECT_ID>
```

This ensures ChatGPT uses the correct tool set for that engine/project.

## Security Considerations

1. **CSRF Protection**: State parameter is validated on OAuth callback
2. **Authorization Code Validation**: Code format is validated (ASCII characters)
3. **Session Management**: User session is created/updated securely
4. **Input Sanitization**: All provider parameters are sanitized
5. **HTTPS Required**: OAuth should only be used over HTTPS in production

## Testing

1. **Local Testing**: Use ngrok or similar to expose your local server over HTTPS
   ```bash
   ngrok http 8080
   ```

2. **Update Configuration**: Use the ngrok URL in your OAuth provider and `social.properties`

3. **Test Flow**:
   - Access the authorization URL in browser
   - Complete OAuth login
   - Verify callback receives user info
   - Check session is created

## Troubleshooting

### "OAuth error: Invalid state parameter"
- State mismatch indicates possible CSRF attack or session issues
- Ensure cookies are enabled
- Check session timeout settings

### "Failed to exchange code for access token"
- Verify OAuth provider configuration in `social.properties`
- Check client_id and secret_key are correct
- Ensure redirect_uri matches exactly what's configured in OAuth provider

### "Provider login is not allowed"
- Add provider to allowed logins in `social.properties`:
  ```properties
  logins_allowed=google,github,microsoft
  ```

## Related Resources

- [OpenAI Apps SDK Documentation](https://developers.openai.com/apps-sdk/plan/components)
- [MCP Protocol Specification](https://www.npmjs.com/package/mcp-remote)
- [OAuth 2.0 RFC](https://www.rfc-editor.org/rfc/rfc6749)
- Existing `UserResource.java` implementation for reference patterns

