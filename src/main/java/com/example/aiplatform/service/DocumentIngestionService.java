package com.example.aiplatform.service;

import com.example.aiplatform.model.IngestDocumentResponse;

import java.util.Map;

public interface DocumentIngestionService {

    /**
     * @param metadata free-form facets stored with the document and available
     *                 as a retrieval filter (product, component, version,
     *                 audience, ...). May be null or empty.
     */
    IngestDocumentResponse ingest(String title, String source, String content, Map<String, String> metadata);
}
