package com.example.aiplatform.service;

import com.example.aiplatform.model.AskResponse;

public interface QuestionAnsweringService {

    AskResponse answer(String question);
}
