package dev.ledgerguard.query.adapter.out.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProcessedEventProjectionRepository extends MongoRepository<ProcessedEventDocument, String> {}
