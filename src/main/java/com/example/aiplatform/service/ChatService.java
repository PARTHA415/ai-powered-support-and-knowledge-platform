package com.example.aiplatform.service;

import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.model.SupportAnswer;
import reactor.core.publisher.Flux;

public interface ChatService {

    ChatResponse answer(String message);

    SupportAnswer answerStructured(String message);

    /**
     * The same answer, streamed token by token.
     *
     * <p>Offered on this path and not on the grounded ones for a reason that is
     * a limitation rather than a design preference: the output guardrail that
     * scrubs sensitive data and system-prompt leaks needs the complete response,
     * and a token already sent cannot be recalled. Open-ended chat produces
     * model prose; {@code /api/qa}, {@code /api/support/assist} and the agent
     * return customer records and retrieved documents, which are where that
     * guardrail earns its place. See
     * {@link com.example.aiplatform.ai.llm.LlmClientService#generateStream} for
     * what streaming those safely would require.
     */
    Flux<String> answerStreaming(String message);
}
