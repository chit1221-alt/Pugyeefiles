# PugyeeFiles: MCP and text conversion

This build adds **AI connection & conversion** to the navigation drawer.
It updates the previously signed Amaze-based app and retains its Android
application ID (`com.amaze.filemanager`) and existing data. Its launcher
name is PugyeeFiles. This is a personal testing release.

## Phone and tablet

The phone containing the files is the host. Install the APK on that phone.
ChatGPT can be used on either the phone or tablet, on any Internet connection.
The tablet does not need Termux or a second file server.

## Connect the phone

1. Open PugyeeFiles → navigation menu → **AI connection & conversion**.
2. Tap **Choose folder**, select The Scale or another folder, and grant access.
3. On the same phone, open a **new** Termux session. If cloudflared is already
   installed, run the command provided by **Copy tunnel command**:

   ```sh
   cloudflared tunnel --url http://127.0.0.1:8787
   ```

4. Copy the `https://…trycloudflare.com` address from Termux into the app's
   **HTTPS tunnel address** field. Do not append `/mcp` here.
5. Choose whether the AI may create converted copies, then tap **Start AI
   connection**. Keep the app service and Termux tunnel running.
6. **Copy MCP address** copies the full URL ending in `/mcp`.
7. In ChatGPT, enable developer mode if your account/workspace allows it and
   add a custom MCP connection with this URL and OAuth authentication. Use
   dynamic client registration (DCR); client credentials are not needed.
8. When the connection page displays a code, open PugyeeFiles on the phone.
   Check the code, client, callback address, and requested access. Tap
   **Approve matching code** only for the connection you started.
9. Return to the connection page and tap **Continue**. Add the connection to
   a chat, then ask it to list the shared folder or read a text file.

The callback approval can be open on the tablet while you approve on the
phone. Requests expire after five minutes.

Quick Tunnels are for testing and their URL changes on restart. A named
Cloudflare tunnel with a separate hostname such as `mcp.pugyee.uk` provides
a stable address. Route **that entire hostname**, including OAuth discovery
and `/authorize`, `/consent`, `/register`, `/token`, and `/mcp`, to
`http://127.0.0.1:8787`. Keep an existing WebDAV hostname on its existing
service. Configure the app with the exact new HTTPS origin.

If cloudflared refuses a Quick Tunnel because it finds an existing config,
use a separate named MCP hostname; do not overwrite your existing GoLive
configuration. Tunnel provisioning and in-app tunnel control are not
included in this build. The app provides a loopback server for your tunnel.

Opening `https://YOUR-HOST/health` should return `status: running`. An
unauthenticated `/mcp` request must return HTTP 401. This is expected.

## Available operations

| Tool | Behavior |
| --- | --- |
| `list_files` | Lists direct children of the selected folder or a relative subfolder. |
| `read_text_file` | Reads UTF-8 `.txt`, `.md`, or `.json`, with character pagination. |
| `convert_text_file` | Creates a new converted file when both the phone setting and OAuth grant allow it. |

Only the selected Android document tree is exposed. Relative paths cannot
contain `..`, absolute URIs, control characters, `%`, `:` or backslashes.
Paths are at most 20 levels; directories may contain up to 500 direct
entries. Text input and converted output are limited to 1 MiB each. Binary
and invalid UTF-8 files are rejected.

Conversion works directly on the phone even with MCP stopped. Choose a text
file, select a format, enter a new filename without its extension, then tap
**Save converted copy**. The copy is placed beside the source.

| Conversion | Result |
| --- | --- |
| Markdown → text | CommonMark text rendering removes Markdown formatting. |
| Text → Markdown | A fenced literal text block preserves the text. |
| Text/Markdown → JSON | A `format`/`text` envelope preserves the source content. |
| That JSON envelope → text/Markdown | Unwraps the source and converts it. |
| Other JSON → text | Pretty-printed JSON; all fields remain. |
| Other JSON → Markdown | Pretty-printed JSON inside a fenced code block. |

Existing destination files are never overwritten. There are no delete,
shell execution, arbitrary write, or external URL fetching tools.

## Connection lifecycle

The server starts only from the phone UI and stops via the screen or its
ongoing notification. It keeps the phone awake while hosting, which uses
battery. Android or the phone manufacturer's power management may still
stop it. It does not auto-start after a reboot.

Stopping or process death revokes every in-memory access/refresh token.
Restart and reconnect in ChatGPT when that happens. Registered public client
IDs are retained privately to support reconnecting without re-registering.
Folder and conversion permissions can only change while hosting is stopped.

OAuth uses PKCE S256, exact registered HTTPS callbacks, resource binding,
one-minute single-use authorization codes, one-hour access tokens, rotating
24-hour refresh tokens, issuer identification, and native phone approval.
No owner password or API key is collected. Loopback-only HTTP requires the
HTTPS tunnel; Origin and Host are checked. This focused single-owner OAuth
implementation is not a general identity provider.

## Development checks

```sh
./gradlew :mcp_server:test spotlessCheck
./gradlew :app:testFdroidDebugUnitTest --tests '*McpActivityTest'
./gradlew assembleFdroidRelease
```

The build verifies APK signatures and compares the embedded certificate
with the configured signing key before uploading the artifact. Phone
installation, Android document-provider behavior, and an actual ChatGPT
connection must also be exercised on the owner's devices.

Protocol references:

- [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [MCP authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [OpenAI authentication](https://developers.openai.com/plugins/build/auth)
- [Connecting to ChatGPT](https://developers.openai.com/plugins/deploy/connect-chatgpt)
- [Cloudflare Quick Tunnels](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)

If registration reaches the 50-client limit, stop hosting and use **Reset
saved connections**, then recreate the ChatGPT connection. This clears only
client registrations; it does not change files.
