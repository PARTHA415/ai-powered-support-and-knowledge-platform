package com.example.aiplatform.service;

import com.example.aiplatform.model.ChatResponse;

public interface SupportAssistantService {

    ChatResponse assist(String message);
}
