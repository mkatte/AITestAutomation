package com.acme.e2e.mcp;

import java.time.Duration;

public interface PromptExecutor {
    PromptResult runPrompt(String prompt, Duration timeout);
}


