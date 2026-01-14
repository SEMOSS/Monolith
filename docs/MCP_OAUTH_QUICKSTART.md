# MCP OAuth Quick Start Guide

This guide will help you quickly set up OAuth authentication for your MCP tools to work with ChatGPT.

## Prerequisites

1. Your SEMOSS instance running and accessible via HTTPS (required for OAuth)
2. An OAuth provider account (Google, GitHub, Microsoft, etc.)
3. OpenAI account with access to create custom apps

## Step 1: Configure OAuth Provider

### Option A: Google OAuth (Recommended for Testing)

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing
3. Navigate to **APIs & Services** > **Credentials**
4. Click **Create Credentials** > **OAuth 2.0 Client ID**
5. Configure OAuth consent screen if prompted
6. Select **Web application** as application type
7. Add these redirect URIs:
   ```
   https://yourdomain.com/Monolith/api/ext/mcp/{toolbox_id}/oauth/callback/google
   ```
8. Copy the **Client ID** and **Client Secret**

### Option B: GitHub OAuth

1. Go to [GitHub Developer Settings](https://github.com/settings/developers)
2. Click **New OAuth App**
3. Fill in:
   - **Application name**: SEMOSS MCP Integration
   - **Homepage URL**: `https://yourdomain.com`
   - **Authorization callback URL**: `https://yourdomain.com/Monolith/api/ext/mcp/{toolbox_id}/oauth/callback/github`
4. Click **Register application**
5. Copy the **Client ID** and **Client Secret**

## Step 2: Update social.properties

Add your OAuth provider configuration to your `social.properties` file:

### For Google:

```properties
# Enable Google login
logins_allowed=google

# Google OAuth Configuration
google_client_id=YOUR_GOOGLE_CLIENT_ID_HERE
google_secret_key=YOUR_GOOGLE_CLIENT_SECRET_HERE
google_redirect_uri=https://yourdomain.com/Monolith/api/ext/mcp/{toolbox_id}/oauth/callback/google
google_auth_url=https://accounts.google.com/o/oauth2/v2/auth
google_token_url=https://oauth2.googleapis.com/token
google_userinfo_url=https://www.googleapis.com/oauth2/v3/userinfo
google_scope=openid email profile
google_beanProps=name,sub,email
google_jsonPattern=
google_auto_add=true
google_sanitizeUserResponse=false
```

### For GitHub:

```properties
# Enable GitHub login
logins_allowed=github

# GitHub OAuth Configuration
github_client_id=YOUR_GITHUB_CLIENT_ID_HERE
github_secret_key=YOUR_GITHUB_CLIENT_SECRET_HERE
github_redirect_uri=https://yourdomain.com/Monolith/api/ext/mcp/{toolbox_id}/oauth/callback/github
github_auth_url=https://github.com/login/oauth/authorize
github_token_url=https://github.com/login/oauth/access_token
github_userinfo_url=https://api.github.com/user
github_scope=read:user user:email
github_beanProps=name,id,email
github_jsonPattern=
github_auto_add=true
github_sanitizeUserResponse=false
```

**Important:** Replace `{toolbox_id}` with your actual toolbox/engine ID, or use a wildcard pattern if your OAuth provider supports it.

## Step 3: Restart SEMOSS

Restart your SEMOSS instance to load the new OAuth configuration:

```bash
# Stop SEMOSS
./stop.sh

# Start SEMOSS
./start.sh
```

## Step 4: Configure OpenAI Custom App

1. Go to [OpenAI Platform](https://platform.openai.com/)
2. Navigate to **Apps** section
3. Click **Create App**
4. Fill in app details:
   - **Name**: Your MCP Tool Name
   - **Description**: Description of your tools
5. In the **Authentication** section, select **OAuth 2.0**
6. Configure OAuth settings:

   ```yaml
   Authorization URL: https://yourdomain.com/Monolith/api/ext/mcp/{toolbox_id}/oauth/authorize?provider=google
   
   Callback URL: https://yourdomain.com/Monolith/api/ext/mcp/{toolbox_id}/oauth/callback/google
   
   User Info URL: https://yourdomain.com/Monolith/api/ext/mcp/{toolbox_id}/oauth/userinfo
   
   Scopes: openid email profile
   ```

7. In the **MCP Server** section:
   ```yaml
   Server URL: https://yourdomain.com/Monolith/api/ext/mcp/{toolbox_id}/comms
   
   Transport: SSE (Server-Sent Events)
   ```

8. Save and publish your app

## Step 5: Test the Integration

1. In ChatGPT, find your custom app in the app store
2. Click **Connect** or **Authorize**
3. You should be redirected to your OAuth provider (Google/GitHub)
4. Log in and authorize the application
5. You should be redirected back to ChatGPT
6. Your MCP tools should now be available in ChatGPT

## Troubleshooting

### "Provider login is not allowed"
- Make sure `logins_allowed` in `social.properties` includes your provider
- Restart SEMOSS after updating configuration

### "Redirect URI mismatch"
- Ensure the redirect URI in `social.properties` exactly matches what's configured in your OAuth provider
- Check that `{toolbox_id}` is replaced with actual value

### "Failed to exchange code for access token"
- Verify `client_id` and `secret_key` are correct
- Check that `token_url` is accessible from your SEMOSS server
- Review SEMOSS logs for detailed error messages

### "No active session" or "User not authenticated"
- Ensure cookies are enabled in your browser
- Check that your domain supports HTTPS (required for secure cookies)
- Try clearing browser cache and cookies

## Local Development Testing

For local testing with ngrok:

1. Start ngrok:
   ```bash
   ngrok http 8080
   ```

2. Use the ngrok URL in your configuration:
   ```properties
   google_redirect_uri=https://your-ngrok-url.ngrok.io/Monolith/api/ext/mcp/{toolbox_id}/oauth/callback/google
   ```

3. Update your OAuth provider's redirect URI with the ngrok URL

4. Restart SEMOSS and test

## Next Steps

- Review the full documentation: [MCP_OAUTH_INTEGRATION.md](./MCP_OAUTH_INTEGRATION.md)
- Configure additional OAuth providers
- Set up user permission management
- Implement custom MCP tools for your use case

## Support

For issues or questions:
- Check SEMOSS logs: `logs/semoss.log`
- Review OAuth provider logs/dashboards
- Consult OpenAI documentation for app configuration

