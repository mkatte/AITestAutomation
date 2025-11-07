package com.acme.e2e.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * Uses OpenAI with MCP (Model Context Protocol) to execute browser automation.
 * The AI directly controls Chrome through the MCP Chrome DevTools server.
 * No intermediate executor - pure AI-driven automation.
 */
public final class PromptExecutorMcp implements PromptExecutor {
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private McpClient mcpClient;
    private ApiCallMetrics metrics;

    public PromptExecutorMcp() {
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.apiKey = System.getProperty("openai.apiKey", System.getenv("OPENAI_API_KEY"));
        if (this.apiKey == null || this.apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key not configured: set openai.apiKey or OPENAI_API_KEY");
        }
        this.model = System.getProperty("openai.model", "gpt-4o-mini");
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
            mcpClient = client;
            
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
            
            String appUrl = System.getProperty("app.url", "about:blank");
            String username = System.getProperty("crm.username", System.getenv("CRM_USERNAME"));
            String password = System.getProperty("crm.password", System.getenv("CRM_PASSWORD"));
            
            // Resolve variables in the user prompt
            String resolvedPrompt = resolveVariables(userPrompt, username, password);
            
            // Get available tools from MCP server
            List<Map<String, Object>> mcpTools = mcpClient.listTools();
            System.out.println("=== MCP Tools Available ===");
            mcpTools.forEach(tool -> System.out.println("- " + tool.get("name")));
            System.out.println("===========================");
            
            // Build tools in OpenAI function format
            List<Map<String, Object>> tools = convertMcpToolsToOpenAiFormat(mcpTools);
            
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
                    "1. Execute the user's request directly using MCP tools - do NOT generate instructions first",
                    "2. After navigation, ALWAYS use wait_for to wait for page content before taking snapshot",
                    "3. If snapshot shows only RootWebArea with no interactive elements, wait 2 seconds and retry snapshot",
                    "4. After each successful action, IMMEDIATELY move to the next step",
                    "5. DO NOT retry the same action if it was successful",
                    "6. If you successfully click a link and navigate to a new page, move to the next step",
                    "7. Only retry actions that actually failed (returned error or timeout)",
                    "8. For Microsoft login pages:",
                    "   - After navigate, wait for 'Sign in' text to appear",
                    "   - Take snapshot to get input field UIDs",
                    "   - Fill the email/username textbox (NOT the RootWebArea)",
                    "   - Click the Next button",
                    "   - CRITICAL: After clicking Next, the page transitions (shows progress bar, Next button becomes disabled)",
                    "   - You MUST take a NEW snapshot to see the password field - do NOT use old UIDs!",
                    "   - Wait for page transition by taking snapshots until you see a password field",
                    "   - Then fill password and click Sign in",
                    "",
                    "SNAPSHOT RULES:",
                    "1. NEVER try to fill/click the RootWebArea element - it's not interactive",
                    "2. Look for specific elements: textbox, button, link, etc.",
                    "3. If snapshot is incomplete (only shows RootWebArea), use evaluate_script to wait: () => new Promise(r => setTimeout(r, 2000))",
                    "4. Then take snapshot again",
                    "5. After clicking buttons that cause page transitions:",
                    "   - Wait 3-5 seconds using evaluate_script: () => new Promise(r => setTimeout(r, 3000))",
                    "   - Then take a NEW snapshot to see the new page elements",
                    "   - NEVER reuse old UIDs after a page transition",
                    "6. If you see a progressbar or disabled buttons, the page is loading - wait and take new snapshot",
                    "",
                    "EFFICIENCY RULES:",
                    "1. MINIMIZE snapshots - only take when you need NEW element UIDs",
                    "2. Use wait_for to wait for specific text/elements before taking snapshots",
                    "3. Batch multiple actions when possible (fill multiple fields, then click)",
                    "4. For iframe interactions: take_snapshot → find iframe UID → use iframe_click/iframe_fill",
                    "5. After successful actions, proceed immediately to next step without snapshot",
                    "6. Only take screenshots for critical debugging (they consume many tokens)",
                    "",
                    "IMPORTANT: Execute the user's request directly. Do not generate instructions or plans first."
            );
            
            String userContent = String.join("\n",
                    "Execute this test automation workflow using Chrome DevTools MCP tools:",
                    "",
                    resolvedPrompt,
                    "",
                    "Execute the workflow directly using MCP tools. Do not generate instructions or plans first."
            );
            
            // Conversation history for multi-turn interaction
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemMsg));
            messages.add(Map.of("role", "user", "content", userContent));
            
            StringBuilder allResults = new StringBuilder();
            int maxTurns = 30; // Increased to allow complex workflows to complete
            int turn = 0;
            Instant startTime = Instant.now();
            
            // Loop detection: fail fast if same error repeats
            String lastError = null;
            int sameErrorCount = 0;
            int maxSameErrors = 3; // Allow more retries before aborting
            
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
                body.put("tools", tools);
                body.put("tool_choice", "auto");

                // Track API call start time
                long apiCallStart = System.currentTimeMillis();
                
                Response resp = RestAssured.given()
                        .baseUri("https://api.openai.com")
                        .basePath("/v1/chat/completions")
                        .contentType(ContentType.JSON)
                        .header("Authorization", "Bearer " + apiKey)
                        .body(body)
                        .post();

                long apiCallDuration = System.currentTimeMillis() - apiCallStart;

                if (resp.statusCode() / 100 != 2) {
                    throw new IllegalStateException("OpenAI MCP call failed: status=" + resp.statusCode() + ", body=" + resp.asString());
                }

                Map<?, ?> root = mapper.readValue(resp.asString(), Map.class);
                
                // Extract token usage from response
                int promptTokens = 0;
                int completionTokens = 0;
                int totalTokens = 0;
                Object usageObj = root.get("usage");
                if (usageObj instanceof Map<?, ?> usage) {
                    Object pt = usage.get("prompt_tokens");
                    Object ct = usage.get("completion_tokens");
                    Object tt = usage.get("total_tokens");
                    if (pt instanceof Number) promptTokens = ((Number) pt).intValue();
                    if (ct instanceof Number) completionTokens = ((Number) ct).intValue();
                    if (tt instanceof Number) totalTokens = ((Number) tt).intValue();
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
                    
                    for (Object tc : tcList) {
                        if (tc instanceof Map<?, ?> toolCall) {
                            String toolCallId = String.valueOf(toolCall.get("id"));
                            Object functionObj = toolCall.get("function");
                            
                            if (functionObj instanceof Map<?, ?> function) {
                                String toolName = String.valueOf(function.get("name"));
                                String argsJson = String.valueOf(function.get("arguments"));
                                
                                Map<String, Object> args = mapper.readValue(argsJson, Map.class);
                                
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
                                                      resultStr.contains("No snapshot found");
                                    
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
                                    
                                    // Only treat as error if it has explicit error OR error pattern AND NOT a success
                                    boolean hasError = (hasExplicitError || hasErrorPattern) && !isSuccess;
                                    
                                    if (hasError) {
                                        String currentError = toolName + ":" + resultStr.substring(0, Math.min(100, resultStr.length()));
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
                                    } else {
                                        // Success - reset error counter
                                        System.out.println("  ✓ Action completed successfully");
                                        lastError = null;
                                        sameErrorCount = 0;
                                    }
                                    
                                    // Break out if we have too many consecutive errors across all tools
                                    if (sameErrorCount >= maxSameErrors) {
                                        String errorMsg = "FAILED: Too many consecutive errors (" + sameErrorCount + "). Aborting to prevent stuck execution.";
                                        allResults.append("\n[").append(errorMsg).append("]");
                                        throw new RuntimeException(errorMsg);
                                    }
                                    
                                    // Add tool result message
                                    toolMessages.add(Map.of(
                                            "role", "tool",
                                            "tool_call_id", toolCallId,
                                            "content", resultStr
                                    ));
                                } catch (RuntimeException re) {
                                    // Re-throw runtime exceptions (like our loop detection)
                                    throw re;
                                } catch (Exception e) {
                                    String errorMsg = "Error: " + e.getMessage();
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
                    Object content = assistantMessage.get("content");
                    if (content != null && !String.valueOf(content).isBlank()) {
                        allResults.append("\n[AI Final Message]: ").append(content);
                    }
                    System.out.println("AI finished (no more tool calls)");
                    break;
                }
            }
            
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
     * Extract assistant message from OpenAI response
     */
    private Map<String, Object> extractAssistantMessage(Map<?, ?> root) {
        try {
            Object choices = root.get("choices");
            if (!(choices instanceof List<?> cList) || cList.isEmpty()) return null;
            Object first = cList.get(0);
            if (!(first instanceof Map<?, ?> choiceMap)) return null;
            Object message = choiceMap.get("message");
            if (!(message instanceof Map<?, ?> msgMap)) return null;
            
            // Build a mutable copy of the message
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
        } catch (Exception e) {
            return null;
        }
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
     * Convert MCP tool definitions to OpenAI function calling format
     */
    private List<Map<String, Object>> convertMcpToolsToOpenAiFormat(List<Map<String, Object>> mcpTools) {
        List<Map<String, Object>> openAiTools = new ArrayList<>();
        
        for (Map<String, Object> mcpTool : mcpTools) {
            String name = String.valueOf(mcpTool.get("name"));
            String description = String.valueOf(mcpTool.getOrDefault("description", ""));
            Object inputSchema = mcpTool.get("inputSchema");
            
            Map<String, Object> parameters;
            if (inputSchema instanceof Map) {
                parameters = (Map<String, Object>) inputSchema;
            } else {
                parameters = Map.of("type", "object", "properties", Map.of());
            }
            
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


