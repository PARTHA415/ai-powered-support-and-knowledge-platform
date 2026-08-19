package com.example.aiplatform.service;

import com.example.aiplatform.model.IngestDocumentResponse;

public interface DocumentIngestionService {

    IngestDocumentResponse ingest(String title, String source, String content);
}
