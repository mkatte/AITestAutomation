package com.acme.e2e.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * Uses Ollama (local LLM) with MCP (Model Context Protocol) to execute browser automation.
 * The AI directly controls Chrome through the MCP Chrome DevTools server.
 * No intermediate executor - pure AI-driven automation.
 */
public final class PromptExecutorMcp implements PromptExecutor {
    private final ObjectMapper mapper;
    private final String ollamaBaseUrl;
    private final String model;
    private ApiCallMetrics metrics;
    private final Map<String, Map<String, Object>> toolSchemas;
    private String currentAppUrl;
    private AtomicBoolean navigationState;
    private static final int DEFAULT_TIMEOUT_MS = getDefaultTimeout();
    
    private static int getDefaultTimeout() {
        // Check system property first (from secrets.properties or command line)
        String prop = System.getProperty("mcp.defaultTimeoutMs");
        if (prop != null && !prop.isBlank()) {
            try {
                int value = Integer.parseInt(prop.trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException e) {
                // Invalid value, use default
            }
        }
        // Default to 10 seconds (10000ms) for faster execution
        return 10000;
    }

    public PromptExecutorMcp() {
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Ollama default URL is http://localhost:11434
        this.ollamaBaseUrl = System.getProperty("ollama.baseUrl", 
                System.getenv("OLLAMA_BASE_URL") != null ? System.getenv("OLLAMA_BASE_URL") : "http://localhost:11434");
        // Default to a model that supports function calling (like llama3.2 or qwen2.5)
        this.model = System.getProperty("ollama.model", 
                System.getenv("OLLAMA_MODEL") != null ? System.getenv("OLLAMA_MODEL") : "llama3.2");
        this.toolSchemas = new HashMap<>();
        System.out.println("=== Ollama Configuration ===");
        System.out.println("Ollama Base URL: " + this.ollamaBaseUrl);
        System.out.println("Model: " + this.model);
        System.out.println("============================");
    }

    @Override
    public PromptResult runPrompt(String userPrompt, Duration timeout) {
        Instant start = Instant.now();
        McpClient client = null;
        try {
            System.out.println("=== AI MCP Execution Mode ===");
            System.out.println("User prompt: " + userPrompt);
            System.out.println("Note: MCP server manages its own browser");
            System.out.println("==============================");
            
            // Connect to MCP Chrome DevTools server (manages its own browser)
            client = new McpClient();
            client.connect();
            
            // Have OpenAI execute the test using MCP Chrome DevTools tools
            String result = executeThroughMcp(userPrompt, client, timeout);
            
            long elapsed = Duration.between(start, Instant.now()).toMillis();
            
            // Determine success: Check for terminal failure indicators, not just any mention of "error"
            boolean success = result != null 
                && !result.contains("FAILED: Same error repeated")
                && !result.contains("FAILED: Too many consecutive errors")
                && !result.contains("FAILED: Reached maximum turns")
                && !result.contains("MCP execution failed:")
                && !result.contains("Timeout reached after");
            
            System.out.println("=== Execution Complete ===");
            System.out.println("Success: " + success + ", Elapsed: " + elapsed + "ms");
            System.out.println("Result: " + result);
            if (metrics != null) {
                System.out.println(metrics.getSummary());
            }
            System.out.println("==========================");
            
            return new PromptResult(result, success, elapsed);
            
        } catch (Exception e) {
            long elapsed = Duration.between(start, Instant.now()).toMillis();
            e.printStackTrace();
            return new PromptResult("MCP execution failed: " + e.getMessage(), false, elapsed);
        } finally {
            // CRITICAL: Always close MCP client to terminate the server process
            if (client != null) {
                try { 
                    System.out.println("Cleaning up MCP client...");
                    client.close(); 
                } catch (Exception e) {
                    System.err.println("Error during MCP cleanup: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    private String executeThroughMcp(String userPrompt, McpClient mcpClient, Duration timeout) {
        try {
            // Initialize metrics tracking
            metrics = new ApiCallMetrics();
            StringBuilder allResults = new StringBuilder();
            AtomicBoolean navigationCompleted = new AtomicBoolean(false);
            this.navigationState = navigationCompleted;

            String username = System.getProperty("crm.username", System.getenv("CRM_USERNAME"));
            String password = System.getProperty("crm.password", System.getenv("CRM_PASSWORD"));

            // Resolve variables in the user prompt
            String resolvedPrompt = resolveVariables(userPrompt, username, password);

            String appUrl = System.getProperty("app.url", System.getenv("APP_URL") != null ? System.getenv("APP_URL") : "about:blank");
            this.currentAppUrl = appUrl;
            if (appUrl == null || appUrl.isBlank() || "about:blank".equalsIgnoreCase(appUrl)) {
                String extracted = extractFirstUrl(resolvedPrompt);
                if (extracted == null) {
                    extracted = extractFirstUrl(userPrompt);
                }
                if (extracted != null) {
                    this.currentAppUrl = extracted;
                    appUrl = extracted;
                }
            }

            String initialNavigationStatus;
            if (this.currentAppUrl != null && !this.currentAppUrl.isBlank() && !"about:blank".equalsIgnoreCase(this.currentAppUrl)) {
                initialNavigationStatus = "Initial navigation not attempted.";
                try {
                    Map<String, Object> initialNavArgs = new HashMap<>();
                    ensureNavigationDefaults(initialNavArgs);
                    System.out.println("Attempting initial navigate_page to " + currentAppUrl);
                    Map<String, Object> navResult = mcpClient.callTool("navigate_page", initialNavArgs);
                    String navResultStr = mapper.writeValueAsString(navResult);
                    boolean navSuccess = navResultStr.contains("Successfully navigated")
                            || navResultStr.contains("Navigation successful")
                            || (navResultStr.contains("# navigate_page response") && !navResultStr.contains("\"isError\":true"));
                    if (navSuccess) {
                        navigationCompleted.set(true);
                        initialNavigationStatus = "Initial navigation succeeded. Result: " + navResultStr;
                        System.out.println("Initial navigation succeeded.");
                    } else {
                        initialNavigationStatus = "Initial navigation attempt returned: " + navResultStr + ". You must retry navigation before proceeding.";
                        System.out.println("Initial navigation did not confirm success: " + navResultStr);
                    }
                    allResults.append("Initial navigate_page result: ").append(navResultStr).append("\n\n");
                } catch (Exception navEx) {
                    initialNavigationStatus = "Initial navigation threw exception: " + navEx.getMessage() + ". You must perform navigate_page as the first step.";
                    System.err.println("Initial navigate_page failed: " + navEx.getMessage());
                }
            } else {
                initialNavigationStatus = "No initial navigation attempted because no target URL was configured. You must call navigate_page with the desired URL.";
            }

            // Get available tools from MCP server
            List<Map<String, Object>> mcpTools = mcpClient.listTools();
            System.out.println("=== MCP Tools Available ===");
            mcpTools.forEach(tool -> System.out.println("- " + tool.get("name")));
            System.out.println("===========================");

            // Build tools in function calling format (Ollama supports OpenAI-compatible format)
            List<Map<String, Object>> tools = convertMcpToolsToOpenAiFormat(mcpTools);

            List<String> steps = extractSteps(resolvedPrompt);
            String formattedSteps = steps.isEmpty()
                    ? resolvedPrompt
                    : IntStream.range(0, steps.size())
                        .mapToObj(i -> (i + 1) + ". " + steps.get(i))
                        .collect(Collectors.joining("\n"));

            String systemMsg = String.join("\n",
                    "You are an AI automation agent with access to Chrome DevTools through MCP (Model Context Protocol).",
                    "You can control the browser directly using the available tools.",
                    "",
                    "Context:",
                    "- App URL: " + appUrl,
                    "- Username: " + (username != null ? username : "[not available]"),
                    "- Password: " + (password != null ? password : "[not available]"),
                    "",
                    "EXECUTION RULES:",
                    "1. CRITICAL: Your FIRST tool call must be navigate_page to '" + appUrl + "' (ignoreCache=false, timeout>0, type='url', url='" + appUrl + "')",
                    "2. CRITICAL: You MUST complete ALL steps in the user's request - do NOT stop after navigation!",
                    "3. Execute the user's request directly using MCP tools - do NOT generate instructions or plans",
                    "4. After navigation, IMMEDIATELY use wait_for with text 'Sign in' (not 'Login') until the login screen is ready",
                    "5. CRITICAL: You MUST wait for each step to succeed before proceeding to the next step!",
                    "6. If a tool call returns an error or fails, you MUST retry that step immediately - do NOT proceed to next step!",
                    "7. After navigation, ALWAYS take_snapshot FIRST to get actual element UIDs",
                    "8. CRITICAL: You MUST use actual UIDs from snapshots (like '1_5', '2_3', etc.) - placeholder UIDs like 'email', 'password', 'button' will FAIL",
                    "9. If snapshot shows only RootWebArea with no interactive elements, wait 2 seconds and retry snapshot",
                    "10. After each successful action, IMMEDIATELY move to the next step - do NOT stop!",
                    "11. DO NOT retry the same action if it was successful",
                    "12. If you successfully click a link and navigate to a new page, move to the next step",
                    "13. If an action fails, you MUST retry it until it succeeds before moving to the next step!",
                    "14. For Microsoft login pages (complete ALL steps):",
                    "    a. Navigate to the URL",
                    "    b. take_snapshot to see the page structure",
                    "    c. Find the textbox UID (look for 'textbox' in snapshot, UID format is like '1_5', '2_7', etc.)",
                    "    d. Use 'fill' tool (NOT fill_form) with the actual textbox UID and the username value",
                    "    e. Find the 'Next' button UID from the snapshot and click it",
                    "    f. Wait 3-5 seconds using evaluate_script with function: () => new Promise(r => setTimeout(r, 3000))",
                    "    g. take_snapshot again to see password field (it will have a NEW UID!)",
                    "    h. Use 'fill' tool (NOT fill_form) to fill password using the new UID",
                    "    i. Find and click 'Sign in' button",
                    "    j. Continue with remaining steps (Stay signed in, navigate to SANDBOX, etc.)",
                    "",
                    "SNAPSHOT RULES:",
                    "1. ALWAYS take_snapshot before fill, click, or any interaction to get current element UIDs",
                    "2. NEVER try to fill/click the RootWebArea element (uid like '1_0') - it's not interactive",
                    "3. UIDs are strings like '1_5', '2_3', '3_7' - extract them from snapshot output",
                    "4. Look for specific elements in snapshot: 'textbox', 'button', 'link' - these have UIDs you can use",
                    "5. If snapshot is incomplete (only shows RootWebArea), use evaluate_script with function parameter: () => new Promise(r => setTimeout(r, 2000))",
                    "6. Then take snapshot again",
                    "7. After clicking buttons that cause page transitions:",
                    "   - Wait 3-5 seconds using evaluate_script with function parameter: () => new Promise(r => setTimeout(r, 3000))",
                    "   - IMPORTANT: evaluate_script requires 'function' parameter (string), not 'expression' or 'type'",
                    "   - Then take_snapshot to see the new page elements with NEW UIDs",
                    "   - NEVER reuse old UIDs after a page transition - they become stale",
                    "8. If you see a progressbar or disabled buttons, the page is loading - wait and take new snapshot",
                    "9. If you get error 'This uid is coming from a stale snapshot', take_snapshot immediately to get fresh UIDs",
                    "",
                    "EFFICIENCY RULES:",
                    "1. Take snapshots when you need element UIDs - this is REQUIRED before fill/click operations",
                    "2. Use wait_for to wait for specific text/elements, but still take_snapshot to get UIDs",
                    "3. CRITICAL: ALWAYS use 'fill' tool for filling fields - NEVER use 'fill_form'!",
                    "4. Use 'fill' tool one field at a time: fill with uid='1_5' and value='username@example.com'",
                    "5. Example workflow: take_snapshot → find textbox UID → fill with that UID → click Next → wait → take_snapshot → find password UID → fill with that UID",
                    "6. Use wait_for or evaluate_script (with empty args list) to wait for new content before taking snapshots",
                    "7. After successful actions that change the page, take new snapshot before next interaction",
                    "8. Only take screenshots for critical debugging (they consume many tokens)",
                    "",
                    "CRITICAL REMINDERS:",
                    "1. First tool call must be navigate_page to '" + appUrl + "' (ignoreCache=false, timeout>0, type='url', url='" + appUrl + "')",
                    "2. You MUST complete ALL steps in the user's prompt - navigation is just the first step!",
                    "3. After navigation, take_snapshot and continue with login, clicking, etc.",
                    "4. Do NOT stop after navigation - keep going until ALL steps are done!",
                    "5. If you encounter an error, fix it and continue - do NOT give up!",
                    "6. Always wait for 'Sign in' text (if Microsoft login) before interacting with the page",
                    "7. Use evaluate_script with 'function' parameter (string) and an array 'args' (e.g. [])",
                    "8. Execute the user's request directly. Do not generate instructions or plans first."
            );

            String userContent = String.join("\n",
                    "Execute these steps sequentially using Chrome DevTools MCP tools:",
                    "",
                    formattedSteps,
                    "",
                    "IMPORTANT: " + initialNavigationStatus,
                    "You MUST complete ALL steps listed above. Do not stop after navigation.",
                    "Continue with login, clicking buttons, navigating, and all other steps until the workflow is complete.",
                    "Execute the workflow directly using MCP tools. Do not generate instructions or plans first."
            );
            
            // Conversation history for multi-turn interaction
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemMsg));
            messages.add(Map.of("role", "user", "content", userContent));
            int maxTurns = 30; // Increased to allow complex workflows to complete
            int turn = 0;
            Instant startTime = Instant.now();
            
            // Loop detection: fail fast if same error repeats
            String lastError = null;
            int sameErrorCount = 0;
            int maxSameErrors = 3; // Allow more retries before aborting
            boolean pendingFailure = !navigationCompleted.get(); // Track if previous turn had failure requiring retry
            boolean hasValidSnapshot = false; // Track if we have a valid snapshot for current page
            
            while (turn < maxTurns) {
                turn++;
                
                // Check timeout
                long elapsed = Duration.between(startTime, Instant.now()).toMillis();
                if (elapsed > timeout.toMillis()) {
                    allResults.append("\n[Timeout reached after ").append(elapsed).append("ms]");
                    break;
                }
                
                // Additional timeout check for individual turns
                if (elapsed > timeout.toMillis() * 0.8) { // Stop at 80% of timeout
                    allResults.append("\n[Approaching timeout, stopping at ").append(elapsed).append("ms]");
                    break;
                }
                
                System.out.println("\n=== AI Turn " + turn + " ===");
                
                Map<String, Object> body = new HashMap<>();
                body.put("model", model);
                body.put("temperature", 0.1);
                body.put("messages", messages);
                // Ollama supports OpenAI-compatible function calling format
                if (tools != null && !tools.isEmpty()) {
                    body.put("tools", tools);
                    body.put("tool_choice", "auto");
                }

                // Track API call start time
                long apiCallStart = System.currentTimeMillis();
                
                // Use OpenAI-compatible endpoint for better function calling support
                Response resp = RestAssured.given()
                        .baseUri(ollamaBaseUrl)
                        .basePath("/v1/chat/completions")
                        .contentType(ContentType.JSON)
                        .header("Authorization", "Bearer ollama") // Required by Ollama but not used
                        .body(body)
                        .post();

                long apiCallDuration = System.currentTimeMillis() - apiCallStart;

                if (resp.statusCode() / 100 != 2) {
                    String errorBody = resp.asString();
                    String errorMessage = "Ollama MCP call failed: status=" + resp.statusCode() + ", body=" + errorBody;
                    
                    // Check if the error is about tools not being supported
                    if (errorBody != null && errorBody.contains("does not support tools")) {
                        errorMessage = String.join("\n",
                            "ERROR: The model '" + model + "' does not support function calling (tools).",
                            "This code requires a model that supports function calling to control the browser.",
                            "",
                            "Please use a model that supports function calling, such as:",
                            "  - llama3.2 (recommended)",
                            "  - llama3.1",
                            "  - qwen2.5",
                            "  - mistral",
                            "  - phi3",
                            "",
                            "To change the model, update secrets.properties:",
                            "  ollama.model=llama3.2",
                            "",
                            "Or pull a supported model:",
                            "  ollama pull llama3.2",
                            "",
                            "Original error: " + errorBody
                        );
                    }
                    
                    throw new IllegalStateException(errorMessage);
                }

                Map<?, ?> root = mapper.readValue(resp.asString(), Map.class);
                
                // Extract token usage from response (Ollama format might be different)
                int promptTokens = 0;
                int completionTokens = 0;
                int totalTokens = 0;
                Object usageObj = root.get("usage");
                if (usageObj instanceof Map<?, ?> usage) {
                    // Ollama might use different field names
                    Object pt = usage.get("prompt_tokens");
                    Object ct = usage.get("completion_tokens");
                    Object tt = usage.get("total_tokens");
                    if (pt == null) pt = usage.get("prompt_eval_count"); // Ollama alternative
                    if (ct == null) ct = usage.get("eval_count"); // Ollama alternative
                    if (pt instanceof Number) promptTokens = ((Number) pt).intValue();
                    if (ct instanceof Number) completionTokens = ((Number) ct).intValue();
                    if (tt instanceof Number) {
                        totalTokens = ((Number) tt).intValue();
                    } else if (promptTokens > 0 || completionTokens > 0) {
                        totalTokens = promptTokens + completionTokens;
                    }
                }
                
                Map<String, Object> assistantMessage = extractAssistantMessage(root);
                
                if (assistantMessage == null) {
                    allResults.append("\n[No valid response from AI]");
                    break;
                }
                
                // Add assistant message to history
                messages.add(assistantMessage);
                
                // Check for tool calls
                Object toolCalls = assistantMessage.get("tool_calls");
                int toolCallsCount = 0;
                if (toolCalls instanceof List<?> tcList) {
                    toolCallsCount = tcList.size();
                }
                
                // Record API call metrics
                metrics.addCall(turn, promptTokens, completionTokens, totalTokens, toolCallsCount, apiCallDuration);
                
                // Print API call metrics
                System.out.println(String.format("API Call #%d: %d prompt + %d completion = %d total tokens, %d tool calls, %dms",
                        turn, promptTokens, completionTokens, totalTokens, toolCallsCount, apiCallDuration));
                
                if (toolCalls instanceof List<?> tcList && !tcList.isEmpty()) {
                    System.out.println("AI requested " + tcList.size() + " tool call(s)");
                    
                    // Execute each tool call and collect results
                    List<Map<String, Object>> toolMessages = new ArrayList<>();
                    boolean hasFailedTool = false; // Track if any tool failed in this turn
                    int successfulToolCalls = 0; // Track successful tool calls in this turn
                    int failedToolCalls = 0; // Track failed tool calls in this turn
                    
                    for (Object tc : tcList) {
                        if (tc instanceof Map<?, ?> toolCall) {
                            String toolCallId = String.valueOf(toolCall.get("id"));
                            Object functionObj = toolCall.get("function");
                            
                            if (functionObj instanceof Map<?, ?> function) {
                                String toolName = String.valueOf(function.get("name"));
                                String argsJson = String.valueOf(function.get("arguments"));
                                
                                Map<String, Object> args = mapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {});

                                // Enforce navigation as the very first successful step
                                if (!navigationCompleted.get() && !"navigate_page".equals(toolName)) {
                                    hasFailedTool = true;
                                    String errorContent = "ERROR: You must call navigate_page to '" + appUrl + "' as the first step before using '" + toolName + "'. " +
                                            "Call navigate_page with ignoreCache=false, timeout>0, type='url', url='" + appUrl + "' and wait for it to succeed. " +
                                            "Do NOT proceed until navigation succeeds.";
                                    System.err.println("  [DETECTED ERROR: navigation step missing before " + toolName + "]");
                                    toolMessages.add(Map.of(
                                            "role", "tool",
                                            "tool_call_id", toolCallId,
                                            "content", errorContent
                                    ));
                                    allResults.append("Tool: ").append(toolName).append(" SKIPPED\n");
                                    allResults.append("Reason: Navigation step not completed yet.\n\n");
                                    continue;
                                }

                                args = normalizeArguments(toolName, args);
                                
                                System.out.println("  → " + toolName + "(" + args + ")");
                                
                                try {
                                    // Execute tool via MCP with timeout
                                    Map<String, Object> toolResult;
                                    try {
                                        // Add timeout wrapper for MCP calls
                                        toolResult = mcpClient.callTool(toolName, args);
                                    } catch (Exception mcpException) {
                                        // If MCP call fails, create error result
                                        toolResult = Map.of(
                                            "content", List.of(Map.of("type", "text", "text", "MCP tool call failed: " + mcpException.getMessage())),
                                            "isError", true
                                        );
                                    }
                                    String resultStr = mapper.writeValueAsString(toolResult);
                                    
                                    allResults.append("Tool: ").append(toolName).append("\n");
                                    allResults.append("Args: ").append(args).append("\n");
                                    allResults.append("Result: ").append(resultStr).append("\n\n");
                                    
                                    // Strip base64 image data from screenshots to prevent token overflow
                                    if ("take_screenshot".equals(toolName)) {
                                        resultStr = stripBase64FromScreenshot(resultStr);
                                    }
                                    
                                    // Check for explicit error indicators
                                    boolean hasExplicitError = resultStr.contains("\"isError\":true");
                                    
                                    // Check for error patterns (but be more conservative)
                                    boolean hasErrorPattern = resultStr.contains("Timed out after waiting") || 
                                                      resultStr.contains("Cannot read properties of null") ||
                                                      resultStr.contains("Element not found") ||
                                                      resultStr.contains("Failed to execute") ||
                                                      resultStr.contains("Connection refused") ||
                                                      resultStr.contains("No such element") ||
                                                      resultStr.contains("No snapshot found") ||
                                                      resultStr.contains("stale snapshot");
                                    
                                    // CRITICAL: "No snapshot found" is a blocking error - must take snapshot first
                                    boolean isNoSnapshotError = resultStr.contains("No snapshot found");
                                    
                                    // Detect placeholder UIDs (text-based instead of numeric like "1_5", "2_3")
                                    // Valid UIDs are numeric patterns like "1_5", "2_10", "3_7", etc.
                                    boolean hasPlaceholderUid = false;
                                    if (args != null && args.containsKey("uid")) {
                                        Object uidObj = args.get("uid");
                                        if (uidObj != null) {
                                            String uid = String.valueOf(uidObj).trim();
                                            // Valid UIDs are numeric patterns like "1_5", "2_10", "3_7", etc.
                                            // Check if UID is NOT a valid numeric pattern
                                            if (!uid.isEmpty() && !uid.matches("\\d+_\\d+")) {
                                                // Check for common placeholder patterns
                                                String lowerUid = uid.toLowerCase();
                                                // Check for descriptive text patterns that indicate placeholder UIDs
                                                if (uid.contains(" ") || // Contains spaces (like "Zones Sales & Service")
                                                    lowerUid.contains("sales") || lowerUid.contains("service") || 
                                                    lowerUid.equals("next") || lowerUid.equals("password") || 
                                                    lowerUid.equals("sign in") || lowerUid.contains("orders") ||
                                                    lowerUid.contains("sandbox") || lowerUid.contains("div") ||
                                                    lowerUid.contains("input") || lowerUid.contains("button") ||
                                                    lowerUid.contains("textbox") || lowerUid.contains("email") ||
                                                    lowerUid.contains("click") || lowerUid.contains("enter") ||
                                                    lowerUid.contains("list item") || lowerUid.contains("containg") ||
                                                    uid.contains("[") || uid.contains("]") || // CSS selectors like "div[contains(...)]"
                                                    uid.contains("(") || uid.contains(")")) { // XPath-like patterns
                                                    hasPlaceholderUid = true;
                                                }
                                            }
                                        }
                                    }
                                    
                                    // Success indicators - these should NOT be treated as errors
                                    boolean isSuccess = resultStr.contains("Successfully clicked") ||
                                                       resultStr.contains("Successfully filled") ||
                                                       resultStr.contains("Successfully navigated") ||
                                                       resultStr.contains("Page content") ||
                                                       resultStr.contains("# navigate_page response") ||
                                                       resultStr.contains("# click response") ||
                                                       resultStr.contains("# fill response") ||
                                                       resultStr.contains("# take_snapshot response") ||
                                                       resultStr.contains("# wait_for response") ||
                                                       resultStr.contains("Element found") ||
                                                       resultStr.contains("Navigation successful") ||
                                                       resultStr.contains("Click successful") ||
                                                       resultStr.contains("Fill successful");
                                    
                                    // Enhanced success detection for specific tools
                                    boolean isToolSpecificSuccess = false;
                                    if ("navigate_page".equals(toolName)) {
                                        isToolSpecificSuccess = resultStr.contains("Successfully navigated") || 
                                                               resultStr.contains("Navigation successful") ||
                                                               (resultStr.contains("# navigate_page response") && !hasExplicitError);
                                    } else if ("fill".equals(toolName) || "fill_form".equals(toolName)) {
                                        isToolSpecificSuccess = resultStr.contains("Successfully filled") ||
                                                               resultStr.contains("Fill successful") ||
                                                               (resultStr.contains("# fill") && !hasExplicitError);
                                    } else if ("click".equals(toolName)) {
                                        isToolSpecificSuccess = resultStr.contains("Successfully clicked") ||
                                                               resultStr.contains("Click successful") ||
                                                               (resultStr.contains("# click response") && !hasExplicitError);
                                    } else if ("take_snapshot".equals(toolName)) {
                                        isToolSpecificSuccess = resultStr.contains("Page content") ||
                                                               resultStr.contains("# take_snapshot response") ||
                                                               (resultStr.contains("uid=") && !hasExplicitError);
                                    } else if ("wait_for".equals(toolName)) {
                                        isToolSpecificSuccess = resultStr.contains("Element found") ||
                                                               resultStr.contains("# wait_for response") ||
                                                               (resultStr.contains("found") && !hasExplicitError);
                                    } else {
                                        // For other tools, use general success indicators
                                        isToolSpecificSuccess = isSuccess;
                                    }
                                    
                                    // Check if interaction tool is called without snapshot
                                    boolean needsSnapshot = ("fill".equals(toolName) || "click".equals(toolName) || 
                                                             "hover".equals(toolName) || "fill_form".equals(toolName)) && 
                                                            !hasValidSnapshot && !isNoSnapshotError;
                                    
                                    // Only treat as error if it has explicit error OR error pattern AND NOT a success
                                    // OR if placeholder UID detected OR if snapshot needed
                                    boolean hasError = ((hasExplicitError || hasErrorPattern) && !isToolSpecificSuccess) ||
                                                       hasPlaceholderUid || needsSnapshot;
                                    
                                    if (hasError) {
                                        hasFailedTool = true; // Mark that this turn has a failure
                                        failedToolCalls++; // Count failed tool calls
                                        
                                        // Build specific error message
                                        String errorReason = "";
                                        if (isNoSnapshotError) {
                                            errorReason = "CRITICAL: No snapshot found. You MUST call take_snapshot FIRST before any fill/click/hover operations.";
                                        } else if (hasPlaceholderUid) {
                                            Object uidObj = args != null ? args.get("uid") : null;
                                            String uid = uidObj != null ? String.valueOf(uidObj) : "unknown";
                                            errorReason = "CRITICAL: Invalid UID '" + uid + "'. You are using placeholder text instead of actual UIDs from snapshot. " +
                                                         "You MUST call take_snapshot first to get real UIDs (format: '1_5', '2_3', etc.). " +
                                                         "NEVER use placeholder UIDs like 'Next', 'Password', 'Zones Sales & Service' - these will FAIL.";
                                        } else if (needsSnapshot) {
                                            errorReason = "CRITICAL: You attempted to call " + toolName + " without taking a snapshot first. " +
                                                         "You MUST call take_snapshot before any fill/click/hover operations to get current element UIDs.";
                                        } else {
                                            errorReason = toolName + " failed. Result: " + resultStr;
                                        }
                                        
                                        String currentError = toolName + ":" + errorReason.substring(0, Math.min(100, errorReason.length()));
                                        if (currentError.equals(lastError)) {
                                            sameErrorCount++;
                                            System.err.println("  [ERROR REPEATED " + sameErrorCount + " times: " + currentError.substring(0, Math.min(80, currentError.length())) + "]");
                                            if (sameErrorCount >= maxSameErrors) {
                                                String errorMsg = "FAILED: Same error repeated " + sameErrorCount + 
                                                    " times - " + currentError + ". Aborting to prevent infinite loop.";
                                                allResults.append("\n[").append(errorMsg).append("]");
                                                throw new RuntimeException(errorMsg);
                                            }
                                        } else {
                                            lastError = currentError;
                                            sameErrorCount = 1;
                                            System.err.println("  [DETECTED ERROR: " + currentError.substring(0, Math.min(80, currentError.length())) + "]");
                                        }
                                        
                                        // Add error message that forces retry with specific guidance
                                        String errorContent = "ERROR: " + errorReason + 
                                            " You MUST fix this issue and retry before proceeding. Do NOT continue to next step until this one succeeds. " +
                                            "If you see 'No snapshot found', call take_snapshot first. If you see invalid UID, call take_snapshot to get real UIDs.";
                                        toolMessages.add(Map.of(
                                                "role", "tool",
                                                "tool_call_id", toolCallId,
                                                "content", errorContent
                                        ));
                                    } else {
                                        // Success - reset error counter
                                        System.out.println("  ✓ Action completed successfully");
                                        successfulToolCalls++;
                                        lastError = null;
                                        sameErrorCount = 0;
                                        if ("navigate_page".equals(toolName)) {
                                            navigationCompleted.set(true);
                                            hasValidSnapshot = false; // Reset snapshot flag after navigation
                                        } else if ("take_snapshot".equals(toolName) && isToolSpecificSuccess) {
                                            hasValidSnapshot = true; // Mark that we have a valid snapshot
                                        } else if ("fill".equals(toolName) || "click".equals(toolName) || 
                                                   "hover".equals(toolName) || "fill_form".equals(toolName)) {
                                            // After successful interaction, snapshot may be stale - reset flag
                                            // AI should take new snapshot before next interaction
                                            hasValidSnapshot = false;
                                        }
                                        
                                        // Add tool result message
                                        toolMessages.add(Map.of(
                                                "role", "tool",
                                                "tool_call_id", toolCallId,
                                                "content", resultStr
                                        ));
                                    }
                                    
                                    // Break out if we have too many consecutive errors across all tools
                                    if (sameErrorCount >= maxSameErrors) {
                                        String errorMsg = "FAILED: Too many consecutive errors (" + sameErrorCount + "). Aborting to prevent stuck execution.";
                                        allResults.append("\n[").append(errorMsg).append("]");
                                        throw new RuntimeException(errorMsg);
                                    }
                                } catch (RuntimeException re) {
                                    // Re-throw runtime exceptions (like our loop detection)
                                    throw re;
                                } catch (Exception e) {
                                    hasFailedTool = true; // Mark failure
                                    String errorMsg = "ERROR: " + toolName + " threw exception: " + e.getMessage() + 
                                        ". You MUST retry this step before proceeding. Do NOT continue to next step until this one succeeds.";
                                    allResults.append("Tool: ").append(toolName).append(" FAILED\n");
                                    allResults.append("Error: ").append(errorMsg).append("\n\n");
                                    
                                    toolMessages.add(Map.of(
                                            "role", "tool",
                                            "tool_call_id", toolCallId,
                                            "content", errorMsg
                                    ));
                                }
                            }
                        }
                    }
                    
                    // Add all tool results to conversation
                    messages.addAll(toolMessages);
                    
                    // CRITICAL: If any tool failed, we MUST prevent progression to next step
                    if (hasFailedTool) {
                        String retryMessage = "CRITICAL FAILURE DETECTED: " + failedToolCalls + " tool call(s) failed in this turn. " +
                            "You MUST retry the failed step(s) immediately. Do NOT proceed to the next step until ALL current steps succeed. " +
                            "The workflow is BLOCKED until you fix these errors. ";
                        
                        // Add specific guidance based on error type
                        if (!hasValidSnapshot && (lastError != null && lastError.contains("snapshot"))) {
                            retryMessage += "SPECIFIC FIX: You must call take_snapshot FIRST to get current page structure and element UIDs. " +
                                          "Then use the actual UIDs from the snapshot (format: '1_5', '2_3', etc.) in your fill/click operations. " +
                                          "NEVER use placeholder text as UIDs - they will always fail.";
                        } else if (lastError != null && lastError.contains("UID")) {
                            retryMessage += "SPECIFIC FIX: You used an invalid placeholder UID. Call take_snapshot to get real UIDs from the page, " +
                                          "then use those exact UIDs (like '1_5', '2_10') in your tool calls. " +
                                          "Do NOT use descriptive text like 'Next', 'Password', 'Zones Sales & Service' as UIDs.";
                        } else {
                            retryMessage += "Check the error messages above and fix the issue, then retry the failed tool call.";
                        }
                        
                        retryMessage += " IMPORTANT: You cannot finish or proceed until these failures are resolved. Call the failed tools again with corrected parameters.";
                        
                        messages.add(Map.of(
                                "role", "user",
                                "content", retryMessage
                        ));
                        System.out.println("  [BLOCKING ERROR: " + failedToolCalls + " tool call(s) failed - workflow BLOCKED until fixed]");
                        System.out.println("  [Successful calls: " + successfulToolCalls + ", Failed calls: " + failedToolCalls + "]");
                    } else if (successfulToolCalls > 0) {
                        System.out.println("  [Turn completed: " + successfulToolCalls + " successful tool call(s)]");
                    }
                    pendingFailure = hasFailedTool; // Update pending failure state
                    
                    // Trim conversation history to prevent token overflow
                    // IMPORTANT: Must preserve proper message sequence - tool messages must follow assistant messages with tool_calls
                    int maxHistorySize = 15; // Keep ~7 turns (each turn = assistant + tools) to prevent stuck execution
                    if (messages.size() > maxHistorySize) {
                        // Keep system message (index 0) and user prompt (index 1)
                        List<Map<String, Object>> trimmed = new ArrayList<>();
                        trimmed.add(messages.get(0)); // System
                        trimmed.add(messages.get(1)); // User
                        
                        // Calculate start index, but ensure we don't break assistant/tool pairs
                        int startIdx = messages.size() - (maxHistorySize - 2);
                        
                        // Walk back to find the nearest "assistant" message to avoid orphaning tool messages
                        while (startIdx > 2 && startIdx < messages.size()) {
                            Map<String, Object> msg = messages.get(startIdx);
                            String role = String.valueOf(msg.get("role"));
                            if ("assistant".equals(role)) {
                                break; // Found a safe starting point
                            }
                            startIdx--;
                        }
                        
                        // Add only recent messages from the safe starting point
                        for (int i = startIdx; i < messages.size(); i++) {
                            trimmed.add(messages.get(i));
                        }
                        messages = trimmed;
                        System.out.println("  [Trimmed conversation history to " + messages.size() + " messages]");
                    }
                    
                    // Continue conversation loop
                    
                } else {
                    // No more tool calls - AI has finished or provided final message
                    // BUT we must check if there are pending failures first!
                    if (pendingFailure) {
                        System.out.println("BLOCKING: AI attempted to finish but failures exist. Forcing retry.");
                        String blockMessage = "STOP: You cannot finish the workflow yet. ";
                        if (!navigationCompleted.get()) {
                            blockMessage += "Navigation is NOT complete. You MUST call navigate_page now with ignoreCache=false, timeout>0, type='url', url='" + appUrl + "'. ";
                        }
                        blockMessage += "You have failed tool calls that must be retried and fixed before you can proceed. " +
                                      "Review the error messages above, fix the issues, and retry the failed tool calls. " +
                                      "Do NOT attempt to finish until all steps succeed.";
                        messages.add(Map.of(
                                "role", "user",
                                "content", blockMessage
                        ));
                        continue; // Force another turn to retry
                    }
                    if (!navigationCompleted.get()) {
                        System.out.println("AI attempted to finish without navigation. Prompting retry.");
                        messages.add(Map.of(
                                "role", "user",
                                "content", "Navigation to '" + appUrl + "' is NOT complete. You MUST call navigate_page now with ignoreCache=false, timeout>0, type='url', url='" + appUrl + "'."
                        ));
                        pendingFailure = true;
                        continue;
                    }
                    Object content = assistantMessage.get("content");
                    if (content != null && !String.valueOf(content).isBlank()) {
                        allResults.append("\n[AI Final Message]: ").append(content);
                    }
                    System.out.println("AI finished (no more tool calls)");
                    break;
                }
            }
            this.navigationState = null;
            
            if (turn >= maxTurns) {
                String errorMsg = "FAILED: Reached maximum turns limit (" + maxTurns + "). Automation did not complete.";
                allResults.append("\n[").append(errorMsg).append("]");
                throw new RuntimeException(errorMsg);
            }
            
            // Print metrics summary
            System.out.println(metrics.getSummary());
            
            return allResults.toString();
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute through MCP", e);
        }
    }
    
    /**
     * Extract assistant message from Ollama response
     * Using OpenAI-compatible endpoint, so response format matches OpenAI: { "choices": [{ "message": {...} }] }
     */
    private Map<String, Object> extractAssistantMessage(Map<?, ?> root) {
        try {
            // Try OpenAI-compatible format first (choices array)
            Object choices = root.get("choices");
            if (choices instanceof List<?> cList && !cList.isEmpty()) {
                Object first = cList.get(0);
                if (first instanceof Map<?, ?> choiceMap) {
                    Object msg = choiceMap.get("message");
                    if (msg instanceof Map<?, ?> msgMap) {
                        Map<String, Object> assistantMsg = new HashMap<>();
                        assistantMsg.put("role", "assistant");
                        
                        Object content = msgMap.get("content");
                        if (content != null) {
                            assistantMsg.put("content", content);
                        }
                        
                        Object toolCalls = msgMap.get("tool_calls");
                        if (toolCalls != null) {
                            assistantMsg.put("tool_calls", toolCalls);
                        }
                        
                        return assistantMsg;
                    }
                }
            }
            
            // Fallback: try native Ollama format (message directly)
            Object message = root.get("message");
            if (message instanceof Map<?, ?> msgMap) {
                Map<String, Object> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                
                Object content = msgMap.get("content");
                if (content != null) {
                    assistantMsg.put("content", content);
                }
                
                Object toolCalls = msgMap.get("tool_calls");
                if (toolCalls != null) {
                    assistantMsg.put("tool_calls", toolCalls);
                }
                
                return assistantMsg;
            }
            
            return null;
        } catch (Exception e) {
            System.err.println("Error extracting assistant message: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Normalize tool call arguments using the MCP tool schema to ensure correct data types.
     */
    private Map<String, Object> normalizeArguments(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return args;
        }

        flattenArgumentContainers(args);

        Map<String, Object> schema = toolSchemas.get(toolName);
        if (schema == null) {
            return args;
        }

        Map<String, Object> properties = getSchemaProperties(schema);
        if (properties == null || properties.isEmpty()) {
            return args;
        }

        Map<String, Object> normalized = new HashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Map<String, Object> propertySchema = toStringObjectMap(properties.get(entry.getKey()));
            Object converted = propertySchema != null
                    ? normalizeValue(propertySchema, entry.getValue())
                    : entry.getValue();
            normalized.put(entry.getKey(), converted);
        }

        applyToolSpecificAdjustments(toolName, normalized);

        if ("navigate_page".equals(toolName)) {
            ensureNavigationDefaults(normalized);
        }
        return normalized;
    }

    private Map<String, Object> getSchemaProperties(Map<String, Object> schema) {
        return toStringObjectMap(schema.get("properties"));
    }

    private Object normalizeValue(Map<String, Object> schema, Object value) {
        if (value == null) {
            return null;
        }

        Set<String> types = extractTypes(schema.get("type"));
        if (types.isEmpty()) {
            return value;
        }

        if (types.contains("boolean")) {
            if (value instanceof String str) {
                String trimmed = str.trim().toLowerCase(Locale.ROOT);
                if ("true".equals(trimmed)) return Boolean.TRUE;
                if ("false".equals(trimmed)) return Boolean.FALSE;
            }
            return value;
        }

        if (types.contains("integer") || types.contains("number")) {
            if (value instanceof String str) {
                String trimmed = str.trim();
                try {
                    if (trimmed.contains(".")) {
                        return Double.parseDouble(trimmed);
                    }
                    long longVal = Long.parseLong(trimmed);
                    if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
                        return (int) longVal;
                    }
                    return longVal;
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            } else if (value instanceof Number num) {
                if (types.contains("integer")) {
                    return num.longValue();
                }
                return num.doubleValue();
            }
            return value;
        }

        if (types.contains("array")) {
            return normalizeArray(schema, value);
        }

        if (types.contains("object")) {
            return normalizeObject(schema, value);
        }

        return value;
    }

    private Object normalizeArray(Map<String, Object> schema, Object value) {
        List<Object> list = null;
        if (value instanceof List<?> vList) {
            list = new ArrayList<>(vList.size());
            for (Object item : vList) {
                list.add(item);
            }
        } else if (value instanceof String str) {
            try {
                list = mapper.readValue(str, new TypeReference<List<Object>>() {});
            } catch (Exception ignored) {
                return value;
            }
        } else {
            return value;
        }

        Map<String, Object> itemSchema = toStringObjectMap(schema.get("items"));
        if (itemSchema == null) {
            return list;
        }

        List<Object> normalized = new ArrayList<>(list.size());
        for (Object item : list) {
            normalized.add(normalizeValue(itemSchema, item));
        }
        return normalized;
    }

    private Object normalizeObject(Map<String, Object> schema, Object value) {
        Map<String, Object> mapValue = null;
        if (value instanceof Map<?, ?> map) {
            mapValue = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                mapValue.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else if (value instanceof String str) {
            try {
                mapValue = mapper.readValue(str, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                return value;
            }
        } else {
            return value;
        }

        Map<String, Object> properties = getSchemaProperties(schema);
        if (properties == null || properties.isEmpty()) {
            return mapValue;
        }

        Map<String, Object> normalized = new HashMap<>(mapValue.size());
        for (Map.Entry<String, Object> entry : mapValue.entrySet()) {
            Map<String, Object> propSchema = toStringObjectMap(properties.get(entry.getKey()));
            Object converted = propSchema != null
                    ? normalizeValue(propSchema, entry.getValue())
                    : entry.getValue();
            normalized.put(entry.getKey(), converted);
        }

        return normalized;
    }

    private Set<String> extractTypes(Object typeObj) {
        if (typeObj instanceof String typeStr) {
            return Set.of(typeStr);
        }
        if (typeObj instanceof Collection<?> collection) {
            Set<String> types = new HashSet<>();
            for (Object obj : collection) {
                if (obj instanceof String str) {
                    types.add(str);
                }
            }
            return types;
        }
        return Collections.emptySet();
    }

    private Map<String, Object> toStringObjectMap(Object candidate) {
        if (candidate instanceof Map<?, ?> map) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return null;
    }

    private String extractFirstUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var matcher = java.util.regex.Pattern.compile("https?://\\S+").matcher(text);
        if (matcher.find()) {
            String url = matcher.group();
            // Trim trailing punctuation if present
            while (!url.isEmpty() && ".,;)\'\"]".indexOf(url.charAt(url.length() - 1)) >= 0) {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }
        return null;
    }

    private List<String> extractSteps(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .collect(Collectors.toList());
    }

    private void flattenArgumentContainers(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return;
        }

        Object paramsObj = args.remove("params");
        if (paramsObj != null) {
            Map<String, Object> params = toStringObjectMap(paramsObj);
            if (params != null) {
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    args.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        Object argumentsObj = args.remove("arguments");
        if (argumentsObj != null) {
            Map<String, Object> innerArgs = toStringObjectMap(argumentsObj);
            if (innerArgs != null) {
                for (Map.Entry<String, Object> entry : innerArgs.entrySet()) {
                    args.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        args.remove("id");
    }

    private void applyToolSpecificAdjustments(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return;
        }

        ensureTimeout(toolName, args);

        switch (toolName) {
            case "evaluate_script" -> adjustEvaluateScript(args);
            case "wait_for" -> ensureTextPresent(args);
            case "navigate_page" -> ensureUrlPresent(args);
            case "fill" -> ensureValuePresent(args);
            case "fill_form" -> ensureElementsArray(args);
            default -> {
            }
        }
    }

    private void ensureNavigationDefaults(Map<String, Object> args) {
        if (!args.containsKey("url") || args.get("url") == null || args.get("url").toString().isBlank()) {
            if (currentAppUrl != null && !currentAppUrl.isBlank()) {
                args.put("url", currentAppUrl);
            }
        }
        args.putIfAbsent("type", "url");
        args.putIfAbsent("ignoreCache", false);
        if (!args.containsKey("timeout") || args.get("timeout") == null) {
            args.put("timeout", DEFAULT_TIMEOUT_MS);
        }
    }

    private void ensureTimeout(String toolName, Map<String, Object> args) {
        if (!hasSchemaProperty(toolName, "timeout")) {
            return;
        }

        // Get minimum timeout from system property (defaults to 2000ms = 2 seconds)
        int minTimeoutMs = getMinTimeout();
        
        Long timeoutMs = toLong(args.get("timeout"));
        if (timeoutMs == null || timeoutMs <= 0) {
            args.put("timeout", DEFAULT_TIMEOUT_MS);
        } else {
            // Ensure timeout is at least the minimum, but allow AI to set higher values
            long adjusted = Math.max(timeoutMs, minTimeoutMs);
            int normalizedTimeout = (int) Math.min(adjusted, Integer.MAX_VALUE);
            args.put("timeout", normalizedTimeout);
        }
    }
    
    private static int getMinTimeout() {
        // Check system property for minimum timeout (from secrets.properties or command line)
        String prop = System.getProperty("mcp.minTimeoutMs");
        if (prop != null && !prop.isBlank()) {
            try {
                int value = Integer.parseInt(prop.trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException e) {
                // Invalid value, use default
            }
        }
        // Default minimum is 2 seconds (2000ms) - fast enough for most operations
        return 2000;
    }

    private void adjustEvaluateScript(Map<String, Object> args) {
        Object functionObj = args.get("function");
        if (functionObj == null) {
            Object expression = args.get("expression");
            if (expression != null) {
                functionObj = expression.toString();
            }
        }

        if (functionObj == null || functionObj.toString().isBlank()) {
            functionObj = "() => new Promise(r => setTimeout(r, 2000))";
        }

        args.put("function", functionObj.toString());
        args.remove("expression");
        args.remove("type");
        args.remove("ignoreCache");

        Object rawArgs = args.get("args");
        List<Object> normalizedArgs = new ArrayList<>();
        if (rawArgs instanceof List<?> list) {
            normalizedArgs.addAll(list);
        } else if (rawArgs instanceof Object[] array) {
            normalizedArgs.addAll(Arrays.asList(array));
        } else if (rawArgs != null && !(rawArgs instanceof String && ((String) rawArgs).isBlank())) {
            normalizedArgs.add(rawArgs);
        }
        args.put("args", normalizedArgs);
    }

    private void ensureTextPresent(Map<String, Object> args) {
        Object text = args.get("text");
        if (text == null || text.toString().isBlank()) {
            System.err.println("WARNING: wait_for missing 'text' argument, skipping validation");
            return;
        }
        String normalized = text.toString().trim();
        if (navigationState != null && navigationState.get()) {
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (lower.equals("login") || lower.equals("log in") || lower.equals("sign in page")) {
                System.out.println("INFO: Adjusting wait_for text from '" + normalized + "' to 'Sign in'.");
                normalized = "Sign in";
            }
        }
        args.put("text", normalized);
    }

    private void ensureUrlPresent(Map<String, Object> args) {
        Object url = args.get("url");
        if (url == null || url.toString().isBlank()) {
            System.err.println("WARNING: navigate_page missing 'url' argument, skipping validation");
            return;
        }
        args.put("url", url.toString());
    }

    private void ensureValuePresent(Map<String, Object> args) {
        if (!args.containsKey("value")) {
            System.err.println("WARNING: fill missing 'value' argument, setting empty string");
            args.put("value", "");
            return;
        }
        Object value = args.get("value");
        args.put("value", value != null ? value.toString() : "");
    }

    private void ensureElementsArray(Map<String, Object> args) {
        // Note: We prefer 'fill' over 'fill_form', but if fill_form is used, normalize it
        Object elementsObj = args.get("elements");
        if (elementsObj instanceof List<?> list) {
            List<Map<String, Object>> normalizedList = new ArrayList<>(list.size());
            for (Object item : list) {
                Map<String, Object> elementMap = toStringObjectMap(item);
                if (elementMap == null || !elementMap.containsKey("uid")) {
                    System.err.println("WARNING: fill_form element missing 'uid', skipping");
                    continue;
                }
                elementMap.put("uid", String.valueOf(elementMap.get("uid")));
                if (!elementMap.containsKey("value")) {
                    elementMap.put("value", "");
                } else {
                    elementMap.put("value", String.valueOf(elementMap.get("value")));
                }
                normalizedList.add(elementMap);
            }
            args.put("elements", normalizedList);
        } else if (elementsObj instanceof String str && !str.isBlank()) {
            try {
                List<Map<String, Object>> parsed = mapper.readValue(str, new TypeReference<List<Map<String, Object>>>() {});
                List<Map<String, Object>> normalizedList = new ArrayList<>(parsed.size());
                for (Map<String, Object> element : parsed) {
                    Map<String, Object> elementMap = toStringObjectMap(element);
                    if (elementMap == null || !elementMap.containsKey("uid")) {
                        System.err.println("WARNING: fill_form element missing 'uid', skipping");
                        continue;
                    }
                    elementMap.put("uid", String.valueOf(elementMap.get("uid")));
                    if (!elementMap.containsKey("value")) {
                        elementMap.put("value", "");
                    } else {
                        elementMap.put("value", String.valueOf(elementMap.get("value")));
                    }
                    normalizedList.add(elementMap);
                }
                args.put("elements", normalizedList);
            } catch (Exception e) {
                System.err.println("WARNING: Could not parse fill_form elements: " + e.getMessage());
            }
        } else {
            System.err.println("WARNING: fill_form missing 'elements' array, tool call may fail");
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number num) {
            return num.longValue();
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean hasSchemaProperty(String toolName, String property) {
        Map<String, Object> schema = toolSchemas.get(toolName);
        if (schema == null) {
            return false;
        }
        Map<String, Object> properties = getSchemaProperties(schema);
        return properties != null && properties.containsKey(property);
    }

    /**
     * Strip base64 image data from screenshot results to prevent token overflow.
     * Replaces large base64 strings with a placeholder.
     */
    private String stripBase64FromScreenshot(String resultStr) {
        // Look for base64 image data patterns and replace with placeholder
        // If result is very large or contains image data, strip it to prevent token overflow
        if (resultStr.contains("data:image") || resultStr.length() > 10000) {
            return "{\"type\":\"text\",\"text\":\"Screenshot taken successfully. Image data stripped to conserve tokens.\"}";
        }
        return resultStr;
    }
    
    /**
     * Convert MCP tool definitions to function calling format (OpenAI-compatible, works with Ollama)
     */
    private List<Map<String, Object>> convertMcpToolsToOpenAiFormat(List<Map<String, Object>> mcpTools) {
        List<Map<String, Object>> openAiTools = new ArrayList<>();
        toolSchemas.clear();
        
        for (Map<String, Object> mcpTool : mcpTools) {
            String name = String.valueOf(mcpTool.get("name"));
            String description = String.valueOf(mcpTool.getOrDefault("description", ""));
            Object inputSchema = mcpTool.get("inputSchema");
            
            Map<String, Object> parameters = toStringObjectMap(inputSchema);
            if (parameters == null) {
                parameters = Map.of("type", "object", "properties", Map.of());
            }
            toolSchemas.put(name, parameters);
            
            openAiTools.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", name,
                            "description", description,
                            "parameters", parameters
                    )
            ));
        }
        
        return openAiTools;
    }
    
    /**
     * Resolve variable placeholders in the prompt text
     */
    private String resolveVariables(String text, String username, String password) {
        if (text == null) return null;
        String result = text;
        
        // Replace username placeholders
        if (username != null && !username.isBlank()) {
            result = result.replace("${CRM_USERNAME}", username);
            result = result.replace("${crm.username}", username);
        }
        
        // Replace password placeholders
        if (password != null && !password.isBlank()) {
            result = result.replace("${CRM_PASSWORD}", password);
            result = result.replace("${crm.password}", password);
        }
        
        return result;
    }
}


