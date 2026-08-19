package com.example.aiplatform.ai.llm;

import com.example.aiplatform.exception.LlmIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

@Service
public class SpringAiLlmClientService implements LlmClientService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClientService.class);

    private final ChatClient chatClient;

    public SpringAiLlmClientService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String generate(Prompt prompt) {
        log.debug("Sending prompt to LLM ({} messages)", prompt.getInstructions().size());
        try {
            String response = chatClient.prompt(prompt)
                    .call()
                    .content();
            log.debug("Received LLM response ({} chars)", response == null ? 0 : response.length());
            return response;
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new LlmIntegrationException("Failed to get a response from the LLM", e);
        }
    }

    @Override
    public String generateWithTools(Prompt prompt, Object... tools) {
        log.debug("Sending prompt to LLM with {} tool object(s) ({} messages)",
                tools.length, prompt.getInstructions().size());
        try {
            String response = chatClient.prompt(prompt)
                    .tools(tools)
                    .call()
                    .content();
            log.debug("Received LLM response ({} chars)", response == null ? 0 : response.length());
            return response;
        } catch (Exception e) {
            log.error("LLM call with tools failed", e);
            throw new LlmIntegrationException("Failed to get a response from the LLM", e);
        }
    }
}
