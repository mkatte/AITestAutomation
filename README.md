# MCP-Powered Browser Automation

AI-driven browser automation using OpenAI and MCP (Model Context Protocol) Chrome DevTools server. The AI directly controls Chrome through MCP tools - pure AI-driven automation with no intermediate executors.

## Overview

This project uses OpenAI to execute browser automation tasks by:
1. Connecting to the `chrome-devtools-mcp` server
2. Querying available MCP tools (navigate, click, fill, snapshot, wait, etc.)
3. Converting MCP tools to OpenAI function calling format
4. Letting the AI decide which tools to call and in what order
5. Executing tool calls through the MCP server
6. Browser automation happens directly via Chrome DevTools Protocol

**Pure AI-driven automation** - the AI controls the browser directly through MCP tools!

## Prerequisites

1. **Java 17+** and **Maven 3.6+**
2. **Node.js** and **npm** (for chrome-devtools-mcp)
3. **OpenAI API Key**

### Install chrome-devtools-mcp

```powershell
npm install -g chrome-devtools-mcp
```

Verify installation:
```powershell
chrome-devtools-mcp --help
```

## Configuration

Create `secrets.properties` in the project root:

```properties
openai.apiKey=sk-...
openai.model=gpt-4o-mini
app.url=https://login.microsoftonline.com/
crm.username=your-user@example.com
crm.password=your-password
```

**Environment variables** (alternative to secrets.properties):
- `OPENAI_API_KEY` - OpenAI API key
- `OPENAI_MODEL` - OpenAI model (default: gpt-4o-mini)
- `APP_URL` - Initial application URL
- `CRM_USERNAME` - Username for login
- `CRM_PASSWORD` - Password for login

## Running Tests

### Basic Usage

```powershell
mvn test -Dtest=PromptTests
```

### With Custom Configuration

```powershell
mvn test -Dtest=PromptTests "-Dopenai.model=gpt-4o-mini" "-Denvfile=secrets.properties" "-Dlatency.budget.ms=180000"
```

### With Visible Browser

```powershell
mvn test -Dtest=PromptTests "-Dbrowser.headless=false"
```

### Full Example

```powershell
mvn test "-Dtest=PromptTests" "-Dopenai.model=gpt-4o-mini" "-Denvfile=secrets.properties" "-Dbrowser.headless=false" "-Dlatency.budget.ms=180000"
```

## Configuration Options

### System Properties

| Property | Description | Default |
|----------|-------------|---------|
| `openai.apiKey` | OpenAI API key | `OPENAI_API_KEY` env var |
| `openai.model` | OpenAI model to use | `gpt-4o-mini` |
| `app.url` | Initial application URL | `about:blank` |
| `crm.username` | Username for login | `CRM_USERNAME` env var |
| `crm.password` | Password for login | `CRM_PASSWORD` env var |
| `envfile` | Path to properties file | `secrets.properties` |
| `latency.budget.ms` | Maximum execution time per prompt | `300000` (5 minutes) |
| `mcp.chrome.command` | MCP server command | `chrome-devtools-mcp` (or `chrome-devtools-mcp.cmd` on Windows) |
| `mcp.chrome.args` | MCP server arguments | `--isolated` |

## How It Works

### MCP Workflow

1. **Test Setup**: `PromptTests` loads configuration from `secrets.properties` and creates a `PromptExecutorMcp` instance
2. **MCP Connection**: `McpClient` starts the `chrome-devtools-mcp` server process via stdio
3. **Tool Discovery**: Queries available MCP tools (navigate_page, take_snapshot, click, fill, wait_for, etc.)
4. **Tool Conversion**: Converts MCP tool definitions to OpenAI function calling format
5. **AI Execution**: 
   - Sends user prompt + available tools to OpenAI
   - AI decides which tools to call and in what order
   - Executes tool calls through MCP server
   - Receives results and continues conversation until task is complete
6. **Cleanup**: MCP client and server process are closed after execution

### Test Execution

- Tests read prompts from `src/test/resources/prompts.csv` (one prompt per line)
- Each prompt gets a **fresh, isolated browser instance** (creates → executes → closes)
- Tests run in **parallel** (max 4 concurrent browsers) for faster execution
- Test reports are generated in `target/surefire-reports/`

## Project Structure

```
src/test/java/com/acme/e2e/mcp/
├── PromptTests.java          # JUnit test class
├── PromptExecutor.java       # Executor interface
├── PromptExecutorMcp.java    # MCP executor implementation
├── McpClient.java            # MCP server communication client
└── PromptResult.java         # Result data class

src/test/resources/
└── prompts.csv               # Test prompts (one per line)
```

## Example Prompts

Add your test prompts to `src/test/resources/prompts.csv`:

```
Navigate to Dynamics 365, login, and create a new sales order
Login to the CRM and search for account "Acme Corp"
Navigate to ${app.url}, login with ${crm.username}, and list all contacts
```

Variables in prompts are automatically resolved:
- `${app.url}` → replaced with `app.url` property
- `${crm.username}` → replaced with `crm.username` property
- `${crm.password}` → replaced with `crm.password` property

## Troubleshooting

### MCP Server Not Found

Ensure `chrome-devtools-mcp` is installed globally:
```powershell
npm install -g chrome-devtools-mcp
chrome-devtools-mcp --help
```

### OpenAI API Key Not Configured

Set the API key in `secrets.properties` or as environment variable:
```properties
openai.apiKey=sk-...
```

Or:
```powershell
$env:OPENAI_API_KEY="sk-..."
```

### Tests Timeout

Increase the latency budget for complex workflows:
```powershell
mvn test -Dtest=PromptTests "-Dlatency.budget.ms=600000"
```

### Browser Not Visible

Ensure headless mode is disabled:
```powershell
mvn test -Dtest=PromptTests "-Dbrowser.headless=false"
```

## Dependencies

- **JUnit 5** - Testing framework
- **RestAssured** - HTTP client for OpenAI API calls
- **Jackson** - JSON processing
- **Selenium** - Browser automation (used by MCP server, not directly by tests)

## License

This project is provided as-is for automation testing purposes.

