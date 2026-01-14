# MCP OAuth Integration - Architecture Diagram

## Complete OAuth Flow with ChatGPT

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          CHATGPT USER INTERACTION                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ 1. User clicks "Connect" to MCP App
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              OPENAI PLATFORM                                 │
│  - Reads MCP App Configuration                                              │
│  - Gets Authorization URL from config                                        │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ 2. HTTP GET Request
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│              YOUR SEMOSS SERVER - MCPResource.java                           │
│                                                                              │
│  📍 Endpoint: /ext/mcp/{toolbox_id}/oauth/authorize                         │
│              ?provider=google&state=xyz123                                   │
│                                                                              │
│  ✓ Validate provider is allowed (social.properties)                         │
│  ✓ Store state in session (CSRF protection)                                 │
│  ✓ Store toolbox_id in session                                              │
│  ✓ Build OAuth redirect URL                                                 │
│                                                                              │
│  Response: 302 Redirect to OAuth Provider                                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ 3. HTTP 302 Redirect
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      OAUTH PROVIDER (Google/GitHub/etc)                      │
│                                                                              │
│  📍 https://accounts.google.com/o/oauth2/v2/auth                            │
│              ?client_id=...&redirect_uri=...&scope=...&state=xyz123         │
│                                                                              │
│  🔐 User logs in with credentials                                           │
│  ✓ User authorizes application                                              │
│  ✓ Provider generates authorization code                                    │
│                                                                              │
│  Response: 302 Redirect back to your server                                 │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ 4. HTTP 302 Redirect with code
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│              YOUR SEMOSS SERVER - MCPResource.java                           │
│                                                                              │
│  📍 Endpoint: /ext/mcp/{toolbox_id}/oauth/callback/google                   │
│              ?code=abc123&state=xyz123                                       │
│                                                                              │
│  ✓ Validate state matches (CSRF protection)                                 │
│  ✓ Validate authorization code format                                       │
│  ✓ Exchange code for access token (POST to token_url)                       │
│  ✓ Fetch user info from provider (GET userinfo_url)                         │
│  ✓ Create/update User object with AccessToken                               │
│  ✓ Store User in HttpSession                                                │
│                                                                              │
│  Response: JSON with user info                                              │
│  { "success": true, "user_id": "...", "user_name": "...", ... }            │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ 5. Returns to OpenAI
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              OPENAI PLATFORM                                 │
│  ✓ Stores user authentication                                               │
│  ✓ Verifies authentication by calling userinfo endpoint                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ 6. HTTP GET Request (verification)
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│              YOUR SEMOSS SERVER - MCPResource.java                           │
│                                                                              │
│  📍 Endpoint: /ext/mcp/{toolbox_id}/oauth/userinfo                          │
│              Authorization: Bearer <session_token>                           │
│                                                                              │
│  ✓ Check session exists                                                     │
│  ✓ Get User from session                                                    │
│  ✓ Get primary AccessToken                                                  │
│                                                                              │
│  Response: JSON with user info                                              │
│  { "user_id": "...", "user_name": "...", "user_email": "..." }             │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ 7. User authenticated
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          CHATGPT USER INTERACTION                            │
│  ✅ Connected to MCP App                                                    │
│  ✅ Can use MCP tools                                                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ 8. User invokes MCP tool
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│              YOUR SEMOSS SERVER - MCPResource.java                           │
│                                                                              │
│  📍 Endpoint: /ext/mcp/{toolbox_id}/comms                                   │
│              Authorization: Bearer <session_token>                           │
│              (SSE - Server-Sent Events)                                      │
│                                                                              │
│  ✓ Validate user session                                                    │
│  ✓ Load Insight for toolbox_id                                              │
│  ✓ Execute MCP tool via MCPReaper                                           │
│  ✓ Stream results back via SSE                                              │
│                                                                              │
│  Response: SSE stream with tool execution results                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Component Interactions

```
┌────────────────────────┐         ┌────────────────────────┐
│                        │         │                        │
│   social.properties    │◄────────│   MCPResource.java     │
│                        │  reads  │                        │
│  - OAuth config        │         │  - OAuth endpoints     │
│  - Client IDs          │         │  - Session mgmt        │
│  - Secrets             │         │  - MCP tools           │
│  - URLs                │         │                        │
│                        │         │                        │
└────────────────────────┘         └────────────┬───────────┘
                                                │
                                                │ uses
                                                ▼
┌────────────────────────┐         ┌────────────────────────┐
│                        │         │                        │
│  HttpHelperUtility     │◄────────│   User & AccessToken   │
│                        │         │                        │
│  - Token exchange      │         │  - Session storage     │
│  - HTTP calls          │         │  - User info           │
│                        │         │                        │
└────────────────────────┘         └────────────────────────┘
          ▲                                   │
          │                                   │ stored in
          │                                   ▼
          │                        ┌────────────────────────┐
          │                        │                        │
          └────────────────────────│   HttpSession          │
                    calls          │                        │
                                   │  - User object         │
                                   │  - OAuth state         │
                                   │  - Toolbox ID          │
                                   │                        │
                                   └────────────────────────┘
```

## Endpoint Mapping

```
Base Path: /ext/mcp/{toolbox_id}

├── POST /comms                        [Existing - MCP communication]
│   └── SSE streaming for tool execution
│
├── GET /oauth/authorize               [New - OAuth initiation]
│   └── ?provider=google&state=xyz
│
├── GET /oauth/callback/{provider}     [New - OAuth callback]
│   └── ?code=abc&state=xyz
│
└── GET /oauth/userinfo                [New - User verification]
    └── Returns authenticated user info
```

## Session State Management

```
┌─────────────────────────────────────┐
│         HttpSession                 │
│                                     │
│  Key: SESSION_USER                  │
│  Value: User object                 │
│    └── AccessToken (Google)         │
│    └── AccessToken (GitHub)         │
│    └── Primary Login                │
│                                     │
│  Key: mcp_oauth_state               │
│  Value: "xyz123" (CSRF token)       │
│                                     │
│  Key: mcp_toolbox_id                │
│  Value: "engine123"                 │
│                                     │
│  Key: INSIGHT                       │
│  Value: "insight_id_456"            │
│                                     │
└─────────────────────────────────────┘
```

## Security Layers

```
🛡️ Layer 1: HTTPS Requirement
    └── All OAuth traffic must be encrypted

🛡️ Layer 2: CSRF Protection
    └── State parameter validated on callback

🛡️ Layer 3: Authorization Code Validation
    └── Code format verified (ASCII chars)

🛡️ Layer 4: Input Sanitization
    └── All parameters cleaned via WebUtility.inputSanitizer()

🛡️ Layer 5: Session Management
    └── Secure session cookies with proper timeout

🛡️ Layer 6: Provider Validation
    └── Only allowed providers (social.properties) can be used
```

## Data Flow for Tool Execution

```
User types in ChatGPT: "Use MCP tool to analyze data"
                │
                ▼
        OpenAI processes request
                │
                ▼
        Determines MCP tool needed
                │
                ▼
        POST /ext/mcp/{toolbox_id}/comms
        (with Authorization header + session cookie)
                │
                ▼
        MCPResource.comms() endpoint
                │
                ├── Check authorization header
                │
                ├── Get User from session
                │
                ├── Initialize or get Insight
                │
                ├── Create MCPReaper thread
                │
                └── Stream results via SSE
                        │
                        ▼
                OpenAI receives stream
                        │
                        ▼
                ChatGPT displays results to user
```

This architecture ensures secure, standards-compliant OAuth authentication while maintaining compatibility with the existing SEMOSS authentication system and MCP protocol requirements.

