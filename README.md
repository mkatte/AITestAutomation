# MCP-Powered Browser Automation

AI-driven browser automation using Ollama (local LLM) and MCP (Model Context Protocol) Chrome DevTools server. The AI directly controls Chrome through MCP tools - pure AI-driven automation with no intermediate executors.

## Overview

This project uses Ollama (local LLM) to execute browser automation tasks by:
1. Connecting to the `chrome-devtools-mcp` server
2. Querying available MCP tools (navigate, click, fill, snapshot, wait, etc.)
3. Converting MCP tools to function calling format
4. Letting the AI decide which tools to call and in what order
5. Executing tool calls through the MCP server
6. Browser automation happens directly via Chrome DevTools Protocol

**Pure AI-driven automation** - the AI controls the browser directly through MCP tools!

## Prerequisites

1. **Java 17+** and **Maven 3.6+**
2. **Node.js** and **npm** (for chrome-devtools-mcp)
3. **Ollama** installed and running locally

### Install chrome-devtools-mcp

```powershell
npm install -g chrome-devtools-mcp
```

Verify installation:
```powershell
chrome-devtools-mcp --help
```

### Install and Setup Ollama

1. **Install Ollama**: Download from [https://ollama.ai](https://ollama.ai) and install
2. **Start Ollama**: The Ollama service should be running (usually starts automatically)
3. **Pull a model**: Download a model that supports function calling:
   ```bash
   ollama pull llama3.2
   # or
   ollama pull llama3.1
   # or
   ollama pull qwen2.5
   # or
   ollama pull mistral
   # or
   ollama pull phi3
   ```

Verify Ollama is running:
```bash
curl http://localhost:11434/api/tags
```

**⚠️ IMPORTANT - Function Calling Support**: 
- **This code REQUIRES a model that supports function calling (tools)**
- **Models that DO support function calling**: `llama3.2`, `llama3.1`, `qwen2.5`, `mistral`, `phi3`
- **Models that DO NOT support function calling**: `tinyllama`, `llama2`, `codellama` (without function calling support)
- If you use a model without function calling support, you'll get an error: "does not support tools"

## Configuration

Create `secrets.properties` in the project root:

```properties
ollama.model=llama3.2
ollama.baseUrl=http://localhost:11434
app.url=https://login.microsoftonline.com/
crm.username=your-user@example.com
crm.password=your-password

# Timeout configuration (optional - defaults shown)
# mcp.defaultTimeoutMs=10000  # Default timeout: 10 seconds
# mcp.minTimeoutMs=2000        # Minimum timeout: 2 seconds
```

**Environment variables** (alternative to secrets.properties):
- `OLLAMA_MODEL` - Ollama model name (default: llama3.2)
- `OLLAMA_BASE_URL` - Ollama API base URL (default: http://localhost:11434)
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
mvn test -Dtest=PromptTests "-Dollama.model=llama3.2" "-Denvfile=secrets.properties" "-Dlatency.budget.ms=180000"
```

### With Visible Browser

```powershell
mvn test -Dtest=PromptTests "-Dbrowser.headless=false"
```

### Full Example

```powershell
mvn test "-Dtest=PromptTests" "-Dollama.model=llama3.2" "-Denvfile=secrets.properties" "-Dbrowser.headless=false" "-Dlatency.budget.ms=180000"
```

## Configuration Options

### System Properties

| Property | Description | Default |
|----------|-------------|---------|
| `ollama.model` | Ollama model name | `llama3.2` |
| `ollama.baseUrl` | Ollama API base URL | `http://localhost:11434` |
| `app.url` | Initial application URL | `about:blank` |
| `crm.username` | Username for login | `CRM_USERNAME` env var |
| `crm.password` | Password for login | `CRM_PASSWORD` env var |
| `envfile` | Path to properties file | `secrets.properties` |
| `latency.budget.ms` | Maximum execution time per prompt | `300000` (5 minutes) |
| `mcp.chrome.command` | MCP server command | `chrome-devtools-mcp` (or `chrome-devtools-mcp.cmd` on Windows) |
| `mcp.chrome.args` | MCP server arguments | `--isolated` |
| `mcp.defaultTimeoutMs` | Default timeout for tool calls when AI doesn't specify one | `10000` (10 seconds) |
| `mcp.minTimeoutMs` | Minimum allowed timeout for tool calls | `2000` (2 seconds) |

## How It Works

### MCP Workflow

1. **Test Setup**: `PromptTests` loads configuration from `secrets.properties` and creates a `PromptExecutorMcp` instance
2. **MCP Connection**: `McpClient` starts the `chrome-devtools-mcp` server process via stdio
3. **Tool Discovery**: Queries available MCP tools (navigate_page, take_snapshot, click, fill, wait_for, etc.)
4. **Tool Conversion**: Converts MCP tool definitions to function calling format (OpenAI-compatible)
5. **AI Execution**: 
   - Sends user prompt + available tools to Ollama
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

## Writing Prompts

The `prompts.csv` file contains your test scenarios. Each line represents a step in a single test, and all lines are executed sequentially as one complete workflow.

### Prompt Format

- **One step per line**: Each line is a single action or instruction
- **Sequential execution**: Steps execute in order, one must complete before the next begins
- **Be specific**: Use exact text values, button labels, and element descriptions
- **Use "Wait for"**: When pages need time to load or elements appear dynamically

### Example Prompts

See `src/test/resources/prompts.example.csv` for detailed examples. Here are some common patterns:

#### Simple Login Flow
```
Navigate to https://example.com/login
Enter username testuser@example.com
Enter password MyPassword123
Click Sign in button
Wait for dashboard page to load
```

#### Microsoft Dynamics 365 Login
```
Navigate to https://test.dynamics.com/
Enter Email AutomationUser
Wait for Next button and click Next
Enter Password password 
Wait for Sign in button and click Sign in
Click Yes on stay signed in page
On the SANDBOX page, click on div containing Zones Sales & Service
Click on list item with text exactly matching Orders
```

#### Form Filling
```
Navigate to https://example.com/contact
Enter name John Doe
Enter email john.doe@example.com
Enter message This is a test message
Select dropdown option Support
Click Submit button
Wait for success message
```

### Best Practices

1. **Be explicit**: "Click Sign in button" is better than "Click button"
2. **Wait for elements**: "Wait for Next button and click Next" ensures the button exists
3. **Use exact text**: Match button labels and field labels exactly as they appear
4. **One action per line**: Keeps steps clear and debuggable
5. **Include navigation**: Always start with "Navigate to [URL]"
6. **Wait for page loads**: After navigation or clicks that change pages, wait for expected content

### Tips

- The AI will automatically take snapshots to find element UIDs
- Use descriptive step names that explain what should happen
- For dynamic pages, include "Wait for" steps before interactions
- Test one scenario per CSV file, or use multiple CSV files for different scenarios

## Troubleshooting

### MCP Server Not Found

Ensure `chrome-devtools-mcp` is installed globally:
```powershell
npm install -g chrome-devtools-mcp
chrome-devtools-mcp --help
```

### Ollama Not Running

Ensure Ollama is installed and running:
```bash
# Check if Ollama is running
curl http://localhost:11434/api/tags

# If not running, start Ollama service
# On macOS/Linux: ollama serve
# On Windows: Start Ollama from Start Menu
```

### Model Not Found

Pull the required model:
```bash
ollama pull llama3.2
# or
ollama pull qwen2.5
```

Verify model is available:
```bash
ollama list
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
- **RestAssured** - HTTP client for Ollama API calls
- **Jackson** - JSON processing
- **Selenium** - Browser automation (used by MCP server, not directly by tests)

## License

This project is provided as-is for automation testing purposes.

