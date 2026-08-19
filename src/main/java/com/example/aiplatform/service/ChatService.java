package com.example.aiplatform.service;

import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.model.SupportAnswer;

public interface ChatService {

    ChatResponse answer(String message);

    SupportAnswer answerStructured(String message);
}
