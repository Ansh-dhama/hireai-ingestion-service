package com.hireai_ingestion_service;

import com.hireai_ingestion_service.domain.entity.Company;
import com.hireai_ingestion_service.domain.entity.JobPosting;
import com.hireai_ingestion_service.domain.repository.CandidateRepository;
import com.hireai_ingestion_service.domain.repository.CompanyRepository;
import com.hireai_ingestion_service.domain.repository.JobPostingRepository;
import com.hireai_ingestion_service.dto.response.GitHubVerificationResult;
import com.hireai_ingestion_service.exception.InvalidDocumentException;
import com.hireai_ingestion_service.service.GitHubVerificationService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CandidateIngestionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JobPostingRepository jobPostingRepository;

    @Autowired
    private CandidateRepository candidateRepository;

    @MockBean
    private GitHubVerificationService gitHubVerificationService;

    private UUID activeJobId;

    @BeforeEach
    void setUp() {
        candidateRepository.deleteAllInBatch();
        jobPostingRepository.deleteAllInBatch();
        companyRepository.deleteAllInBatch();

        Company company = companyRepository.save(new Company("Alpha Tech", "alphatech.io", "ENTERPRISE"));
        JobPosting job = jobPostingRepository.save(new JobPosting(
                company,
                "Backend Engineer",
                "Engineering",
                "Remote",
                "Designing scalable backend ingestion services.",
                List.of("Java", "Spring Boot", "MySQL"),
                2
        ));
        this.activeJobId = job.getId();
    }

    private byte[] generateValidPdf(String resumeContent) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText(resumeContent);
                contentStream.endText();
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    @Test
    @DisplayName("TC-01: Valid application with real PDF returns 201 Created")
    void testValidApplicationSubmission() throws Exception {
        byte[] validPdfBytes = generateValidPdf("Jane Doe - Senior Java Backend Engineer with Spring Boot expertise");

        MockMultipartFile resumeFile = new MockMultipartFile(
                "resume", "resume.pdf", "application/pdf", validPdfBytes);

        Mockito.when(gitHubVerificationService.verifyCandidateProfile(eq("octocat")))
                .thenReturn(new GitHubVerificationResult("octocat", 8, 120, "https://github.com/octocat", true));

        mockMvc.perform(multipart("/api/v1/candidates/apply")
                        .file(resumeFile)
                        .param("jobPostingId", activeJobId.toString())
                        .param("fullName", "Jane Doe")
                        .param("email", "jane.doe@example.com")
                        .param("githubUsername", "octocat"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.candidateId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.email").value("jane.doe@example.com"));

        assertEquals(1, candidateRepository.count());
    }

    @Test
    @DisplayName("TC-02: Non-existent GitHub profile returns 400 Bad Request")
    void testInvalidGitHubUsernameThrowsBadRequest() throws Exception {
        byte[] validPdfBytes = generateValidPdf("Candidate resume content");
        MockMultipartFile resumeFile = new MockMultipartFile(
                "resume", "resume.pdf", "application/pdf", validPdfBytes);

        Mockito.when(gitHubVerificationService.verifyCandidateProfile(eq("fake-ghost-user-404")))
                .thenThrow(new InvalidDocumentException("GitHub handle 'fake-ghost-user-404' does not exist."));

        mockMvc.perform(multipart("/api/v1/candidates/apply")
                        .file(resumeFile)
                        .param("jobPostingId", activeJobId.toString())
                        .param("fullName", "Jane Doe")
                        .param("email", "jane.doe@example.com")
                        .param("githubUsername", "fake-ghost-user-404"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Document Validation Failure"))
                .andExpect(jsonPath("$.detail").value("GitHub handle 'fake-ghost-user-404' does not exist."));
    }

    @Test
    @DisplayName("TC-04: Disguised shell script (.sh) returns 400 Bad Request")
    void testDisguisedExecutableRejected() throws Exception {
        byte[] maliciousScriptBytes = "#!/bin/bash\necho 'Malicious execution attempt'".getBytes();
        MockMultipartFile fakePdfFile = new MockMultipartFile(
                "resume", "resume.pdf", "application/pdf", maliciousScriptBytes);

        mockMvc.perform(multipart("/api/v1/candidates/apply")
                        .file(fakePdfFile)
                        .param("jobPostingId", activeJobId.toString())
                        .param("fullName", "Attacker")
                        .param("email", "attacker@example.com")
                        .param("githubUsername", "attacker-handle"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Document Validation Failure"));
    }
}