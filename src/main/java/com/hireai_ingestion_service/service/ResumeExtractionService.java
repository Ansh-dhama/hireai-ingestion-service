package com.hireai_ingestion_service.service;

import com.hireai_ingestion_service.exception.InvalidDocumentException;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@Component
public class ResumeExtractionService {
    private static final Logger log = LoggerFactory.getLogger(ResumeExtractionService.class);

    private static final Set<String> ALLOWED_MIME_TYPES =
            Set.of(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // .docx
                    "application/msword" // .doc
            );

    private static final int MAX_EXTRACTED_CHARACTERS = 200_000;

    private final Tika tika = new Tika();

    public ExtractionResult extractAndSanitize(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidDocumentException("Target upload payload contains zero bytes.");
        }
        String detechMine;
        try (InputStream detectStream = file.getInputStream()) {
            detechMine = tika.detect(detectStream, file.getOriginalFilename());
        } catch (IOException e) {
            throw new InvalidDocumentException(
                    "Unable to read binary header for MIME identification.", e);
        }

        if (!ALLOWED_MIME_TYPES.contains(detechMine)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Invalid file format: Detected %s. Allowed formats: PDF, DOCX, DOC."
                            .formatted(detechMine));
        }

        // 2. Stream-parse document contents
        BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARACTERS);
        Metadata metadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        AutoDetectParser parser = new AutoDetectParser();

        try (InputStream parseStream = file.getInputStream()) {
            parser.parse(parseStream, handler, metadata, parseContext);
            String rawText = handler.toString();
            if (rawText.isBlank()) {
                throw new InvalidDocumentException(
                        "The uploaded document contains no extractable text content.");
            }

            String sanitized = sanitize(rawText);
            return new ExtractionResult(sanitized, detechMine, metadata.get("Content-Length"));
        } catch (SAXException e) {
            throw new InvalidDocumentException("Document character buffer limit exceeded.", e);
        } catch (TikaException | IOException e) {
            log.error("Structural processing failure during resume extraction: {}", e.getMessage());
            throw new InvalidDocumentException(
                    "Failed to decode corrupted or password-protected document.", e);
        }
    }

    private String sanitize(String input) {
        return input
                .replaceAll("\\x00", "") // Strip dangerous null bytes
                .replaceAll("\\r\\n", "\n") // Normalize CRLF to LF
                .replaceAll("[ \\t\\f]+", " ") // Condense horizontal whitespace
                .replaceAll("\\n{3,}", "\n\n") // Collapse excessive blank lines
                .trim();
    }

    public record ExtractionResult(String text, String mimeType, String declaredLength) {}
}