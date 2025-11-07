package com.acme.e2e.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP (Model Context Protocol) client for communicating with chrome-devtools-mcp server.
 * Uses stdio to send JSON-RPC 2.0 messages to the MCP server process.
 */
public final class McpClient implements AutoCloseable {
    private final ObjectMapper mapper;
    private Process mcpProcess;
    private BufferedReader stdoutReader;
    private BufferedWriter stdinWriter;
    private BufferedReader stderrReader;
    private final AtomicLong requestId = new AtomicLong(1);
    private volatile boolean connected = false;
    private volatile boolean shutdownHookRegistered = false;
    private Thread shutdownHook;

    public McpClient() {
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Start the chrome-devtools-mcp server process
     */
    public void connect() throws IOException {
        if (connected && mcpProcess != null && mcpProcess.isAlive()) {
            return;
        }

        String command = System.getProperty("mcp.chrome.command", 
                System.getProperty("os.name").toLowerCase().contains("win") 
                        ? "chrome-devtools-mcp.cmd" 
                        : "chrome-devtools-mcp");
        String argsStr = System.getProperty("mcp.chrome.args", "--isolated");
        
        // Build command list: command + split args
        List<String> cmdList = new ArrayList<>();
        cmdList.add(command);
        if (argsStr != null && !argsStr.isBlank()) {
            cmdList.addAll(Arrays.asList(argsStr.split("\\s+")));
        }
        
        ProcessBuilder pb = new ProcessBuilder(cmdList);
        pb.redirectErrorStream(false);
        
        System.out.println("=== Starting MCP Chrome DevTools Server ===");
        System.out.println("Command: " + String.join(" ", cmdList));
        
        mcpProcess = pb.start();
        stdinWriter = new BufferedWriter(new OutputStreamWriter(mcpProcess.getOutputStream()));
        stdoutReader = new BufferedReader(new InputStreamReader(mcpProcess.getInputStream()));
        stderrReader = new BufferedReader(new InputStreamReader(mcpProcess.getErrorStream()));
        
        // Start stderr monitor thread
        new Thread(() -> {
            try {
                String line;
                while ((line = stderrReader.readLine()) != null) {
                    System.err.println("[MCP stderr] " + line);
                }
            } catch (IOException ignored) {}
        }, "mcp-stderr-monitor").start();
        
        // Initialize the MCP connection
        initialize();
        
        // Maximize browser window for better visibility - use larger resolution to ensure all controls are visible
        try {
            int width = Integer.getInteger("browser.window.width", 2850);
            int height = Integer.getInteger("browser.window.height", 1200);
            resizePage(width, height);
            System.out.println("Browser window resized to " + width + "x" + height);
        } catch (Exception e) {
            System.out.println("Note: Couldn't resize browser window: " + e.getMessage());
        }
        
        // Set zoom level to 60% to see more content
        try {
            double zoomLevel = Double.parseDouble(System.getProperty("browser.zoom.level", "0.6"));
            setZoomLevel(zoomLevel);
            System.out.println("Browser zoom set to " + (int)(zoomLevel * 100) + "%");
        } catch (Exception e) {
            System.out.println("Note: Couldn't set zoom level: " + e.getMessage());
        }
        
        // Register shutdown hook to ensure cleanup even on JVM exit
        registerShutdownHook();
        
        connected = true;
        System.out.println("=== MCP Client Connected ===");
    }
    
    /**
     * Register a shutdown hook to ensure browser cleanup on JVM exit
     */
    private void registerShutdownHook() {
        if (!shutdownHookRegistered) {
            shutdownHook = new Thread(() -> {
                System.out.println("=== Shutdown hook triggered - cleaning up browser ===");
                try {
                    closeInternal(true); // Skip shutdown hook removal in this path
                } catch (Exception e) {
                    System.err.println("Error in shutdown hook: " + e.getMessage());
                }
            }, "mcp-cleanup-hook");
            
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                shutdownHookRegistered = true;
                System.out.println("Shutdown hook registered for browser cleanup");
            } catch (Exception e) {
                System.err.println("Warning: Could not register shutdown hook: " + e.getMessage());
            }
        }
    }

    /**
     * Send initialize request to MCP server
     */
    private void initialize() throws IOException {
        Map<String, Object> initParams = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(
                        "roots", Map.of("listChanged", true),
                        "sampling", Map.of()
                ),
                "clientInfo", Map.of(
                        "name", "mcp-e2e-tests",
                        "version", "0.1.0"
                )
        );
        
        Map<String, Object> response = sendRequest("initialize", initParams);
        System.out.println("MCP initialized: " + response);
        
        // Send initialized notification
        sendNotification("notifications/initialized");
    }

    /**
     * List available tools from MCP server
     */
    public List<Map<String, Object>> listTools() throws IOException {
        Map<String, Object> response = sendRequest("tools/list", Map.of());
        Object tools = response.get("tools");
        if (tools instanceof List<?>) {
            return (List<Map<String, Object>>) tools;
        }
        return List.of();
    }

    /**
     * Call an MCP tool
     */
    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) throws IOException {
        Map<String, Object> params = Map.of(
                "name", toolName,
                "arguments", arguments != null ? arguments : Map.of()
        );
        
        System.out.println("=== Calling MCP Tool ===");
        System.out.println("Tool: " + toolName);
        System.out.println("Arguments: " + arguments);
        
        Map<String, Object> response = sendRequest("tools/call", params);
        
        System.out.println("Response: " + response);
        System.out.println("========================");
        
        return response;
    }

    /**
     * Send a JSON-RPC request and wait for response
     */
    private Map<String, Object> sendRequest(String method, Map<String, Object> params) throws IOException {
        if (!connected && mcpProcess == null) {
            throw new IllegalStateException("MCP client not connected");
        }

        long id = requestId.getAndIncrement();
        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);

        String requestJson = mapper.writeValueAsString(request);
        
        synchronized (stdinWriter) {
            stdinWriter.write(requestJson);
            stdinWriter.newLine();
            stdinWriter.flush();
        }

        // Read response
        String responseLine = stdoutReader.readLine();
        if (responseLine == null) {
            throw new IOException("MCP server closed connection");
        }

        Map<?, ?> responseMap = mapper.readValue(responseLine, Map.class);
        
        if (responseMap.containsKey("error")) {
            Map<?, ?> error = (Map<?, ?>) responseMap.get("error");
            throw new IOException("MCP error: " + error.get("message"));
        }
        
        Object result = responseMap.get("result");
        return result instanceof Map ? (Map<String, Object>) result : Map.of();
    }

    /**
     * Send a JSON-RPC notification (no response expected)
     */
    private void sendNotification(String method) throws IOException {
        Map<String, Object> notification = new HashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);

        String notificationJson = mapper.writeValueAsString(notification);
        
        synchronized (stdinWriter) {
            stdinWriter.write(notificationJson);
            stdinWriter.newLine();
            stdinWriter.flush();
        }
    }

    @Override
    public void close() {
        closeInternal(false);
    }
    
    /**
     * Internal close method with option to skip shutdown hook removal
     */
    private void closeInternal(boolean fromShutdownHook) {
        if (!connected && mcpProcess == null) {
            return; // Already closed
        }
        
        connected = false;
        
        try {
            // Step 1: Immediately kill the MCP server process - don't try to communicate with it
            // Any attempt to close streams or send commands can hang if server is unresponsive
            System.out.println("Force terminating MCP server and Chrome processes...");
            
            // Step 2: Terminate the MCP server process and all its children FIRST
            if (mcpProcess != null && mcpProcess.isAlive()) {
                long pid = mcpProcess.pid();
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                
                // On Windows, use taskkill /T to kill process tree immediately
                if (isWindows) {
                    try {
                        System.out.println("Force killing process tree (PID " + pid + ")...");
                        ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid));
                        pb.redirectErrorStream(true);
                        Process killProcess = pb.start();
                        boolean killed = killProcess.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                        if (killed) {
                            System.out.println("Process tree killed (exit code: " + killProcess.exitValue() + ")");
                        }
                    } catch (Exception e) {
                        System.err.println("Error killing process tree: " + e.getMessage());
                    }
                } else {
                    // On Unix/Linux/Mac, use destroyForcibly
                    System.out.println("Force killing MCP server process (PID " + pid + ")...");
                    mcpProcess.destroyForcibly();
                }
                
                // Give it a brief moment to die, but don't wait long
                try {
                    mcpProcess.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {}
                
                if (mcpProcess.isAlive()) {
                    System.err.println("WARNING: MCP server process still running after forced kill!");
                } else {
                    System.out.println("MCP server terminated successfully");
                }
            }
            
            // Step 3: Now close streams (after process is dead, so they won't block)
            try {
                if (stdinWriter != null) stdinWriter.close();
            } catch (Exception ignored) {}
            
            try {
                if (stdoutReader != null) stdoutReader.close();
            } catch (Exception ignored) {}
            
            try {
                if (stderrReader != null) stderrReader.close();
            } catch (Exception ignored) {}
            
            // Step 4: Kill any orphaned Chrome processes on Windows (aggressive cleanup)
            // IMPORTANT: Only safe when running tests sequentially!
            // For parallel tests, set -Dmcp.kill.chrome=false to avoid killing other tests' browsers
            // Default: false (safe for parallel execution)
            boolean killChrome = Boolean.parseBoolean(System.getProperty("mcp.kill.chrome", "false"));
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            
            if (isWindows && killChrome) {
                try {
                    System.out.println("Killing any remaining Chrome processes...");
                    // WARNING: This kills ALL Chrome instances - only use for sequential test execution!
                    ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", "chrome.exe");
                    pb.redirectErrorStream(true);
                    Process killChromeProcess = pb.start();
                    boolean killed = killChromeProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                    if (killed && killChromeProcess.exitValue() == 0) {
                        System.out.println("Chrome processes terminated");
                    } else {
                        System.out.println("No Chrome processes to kill (or already closed)");
                    }
                } catch (Exception e) {
                    System.out.println("Note: Couldn't kill Chrome processes: " + e.getMessage());
                }
            } else {
                System.out.println("Relying on process tree termination for Chrome cleanup (parallel-safe mode)");
            }
            
        } catch (Exception e) {
            System.err.println("Error closing MCP client: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Remove shutdown hook if this is not being called from the shutdown hook
            if (!fromShutdownHook && shutdownHookRegistered && shutdownHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    shutdownHookRegistered = false;
                    System.out.println("Shutdown hook removed (normal cleanup)");
                } catch (IllegalStateException e) {
                    // JVM is already shutting down, ignore
                } catch (Exception e) {
                    System.out.println("Note: Couldn't remove shutdown hook: " + e.getMessage());
                }
            }
            
            System.out.println("=== MCP Client Closed ===");
        }
    }
    
    /**
     * Close a specific browser page
     */
    private void closePage(int pageIdx) throws Exception {
        Map<String, Object> params = Map.of("pageIdx", pageIdx);
        callTool("close_page", params);
    }
    
    /**
     * List all browser pages
     */
    private List<Map<String, Object>> listPages() throws Exception {
        try {
            Map<String, Object> response = callTool("list_pages", Map.of());
            // Parse response to get page list
            Object content = response.get("content");
            if (content instanceof List) {
                return (List<Map<String, Object>>) content;
            }
        } catch (Exception ignored) {}
        return List.of();
    }
    
    /**
     * Resize the browser window
     */
    private void resizePage(int width, int height) throws Exception {
        Map<String, Object> params = Map.of(
                "width", width,
                "height", height
        );
        callTool("resize_page", params);
    }
    
    /**
     * Set browser zoom level via JavaScript
     */
    private void setZoomLevel(double zoomLevel) throws Exception {
        // Use a function format that returns a value
        String script = "() => { document.body.style.zoom = '" + (zoomLevel * 100) + "%'; return 'Zoom set to " + (int)(zoomLevel * 100) + "%'; }";
        Map<String, Object> params = Map.of(
                "function", script,
                "args", new ArrayList<>()
        );
        callTool("evaluate_script", params);
    }

    public boolean isConnected() {
        return connected && mcpProcess != null && mcpProcess.isAlive();
    }
}

