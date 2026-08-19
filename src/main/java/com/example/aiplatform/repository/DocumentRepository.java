package com.example.aiplatform.repository;

import com.example.aiplatform.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {
}
