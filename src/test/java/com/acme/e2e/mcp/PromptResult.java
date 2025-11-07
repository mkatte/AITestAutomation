package com.acme.e2e.mcp;

public final class PromptResult {
    public final String text;
    public final boolean success;
    public final long elapsedMs;

    public PromptResult(String text, boolean success, long elapsedMs) {
        this.text = text;
        this.success = success;
        this.elapsedMs = elapsedMs;
    }
}


