# HireAI

A Java backend that processes candidate resumes and collects public GitHub code evidence for technical recruitment reviews.

## Features

- Resume upload, file validation and text extraction.
- GitHub repository sampling with pinned commits.
- Asynchronous extraction using Redis Streams and bounded workers.
- Durable jobs, automatic retries and failure recovery.
- Static dependency extraction from project manifests.
- Consent tracking and application-specific access tokens.
- REST APIs with Swagger documentation and a Postman collection.

## Tech Stack

Java 21 · Spring Boot · Spring Security · MySQL · Redis · Apache Tika · Flyway · Docker Compose

## Current Progress

Implemented resume ingestion and public GitHub evidence extraction. AI-based candidate analysis and scoring are planned for the next phase.
