package com.acme.e2e.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks API call metrics including token usage for each OpenAI API call.
 */
public final class ApiCallMetrics {
    public static final class ApiCall {
        public final int callNumber;
        public final int promptTokens;
        public final int completionTokens;
        public final int totalTokens;
        public final int toolCallsCount;
        public final long responseTimeMs;
        
        public ApiCall(int callNumber, int promptTokens, int completionTokens, int totalTokens, 
                      int toolCallsCount, long responseTimeMs) {
            this.callNumber = callNumber;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.toolCallsCount = toolCallsCount;
            this.responseTimeMs = responseTimeMs;
        }
        
        @Override
        public String toString() {
            return String.format("Call #%d: %d prompt + %d completion = %d total tokens, %d tool calls, %dms",
                    callNumber, promptTokens, completionTokens, totalTokens, toolCallsCount, responseTimeMs);
        }
    }
    
    private final List<ApiCall> calls = new ArrayList<>();
    
    public void addCall(int callNumber, int promptTokens, int completionTokens, int totalTokens, 
                       int toolCallsCount, long responseTimeMs) {
        calls.add(new ApiCall(callNumber, promptTokens, completionTokens, totalTokens, toolCallsCount, responseTimeMs));
    }
    
    public List<ApiCall> getCalls() {
        return new ArrayList<>(calls);
    }
    
    public int getTotalCalls() {
        return calls.size();
    }
    
    public int getTotalPromptTokens() {
        return calls.stream().mapToInt(c -> c.promptTokens).sum();
    }
    
    public int getTotalCompletionTokens() {
        return calls.stream().mapToInt(c -> c.completionTokens).sum();
    }
    
    public int getTotalTokens() {
        return calls.stream().mapToInt(c -> c.totalTokens).sum();
    }
    
    public long getTotalResponseTimeMs() {
        return calls.stream().mapToLong(c -> c.responseTimeMs).sum();
    }
    
    public int getTotalToolCalls() {
        return calls.stream().mapToInt(c -> c.toolCallsCount).sum();
    }
    
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== API Call Metrics Summary ===\n");
        sb.append(String.format("Total API Calls: %d\n", getTotalCalls()));
        sb.append(String.format("Total Prompt Tokens: %d\n", getTotalPromptTokens()));
        sb.append(String.format("Total Completion Tokens: %d\n", getTotalCompletionTokens()));
        sb.append(String.format("Total Tokens: %d\n", getTotalTokens()));
        sb.append(String.format("Total Tool Calls: %d\n", getTotalToolCalls()));
        sb.append(String.format("Total Response Time: %d ms (%.2f s)\n", 
                getTotalResponseTimeMs(), getTotalResponseTimeMs() / 1000.0));
        sb.append(String.format("Average Tokens per Call: %.1f\n", 
                getTotalCalls() > 0 ? (double) getTotalTokens() / getTotalCalls() : 0));
        sb.append(String.format("Average Response Time: %.1f ms\n", 
                getTotalCalls() > 0 ? (double) getTotalResponseTimeMs() / getTotalCalls() : 0));
        sb.append("\n=== Per-Call Breakdown ===\n");
        for (ApiCall call : calls) {
            sb.append(call.toString()).append("\n");
        }
        sb.append("================================\n");
        return sb.toString();
    }
}

