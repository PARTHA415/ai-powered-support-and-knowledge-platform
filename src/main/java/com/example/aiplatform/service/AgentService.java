package com.example.aiplatform.service;

import com.example.aiplatform.model.AgentResponse;

public interface AgentService {

    AgentResponse handle(String conversationId, String question);
}
