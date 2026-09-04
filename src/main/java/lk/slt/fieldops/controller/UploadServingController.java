package lk.slt.fieldops.controller;

import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves uploaded fault/job photos at /uploads/{subfolder}/{filename} — the same URL shape
 * FileStorageService.store() has always returned and that is already persisted verbatim in
 * Fault.photoUrls / Job.completionPhotoUrls / Payment.disputePhotoUrl, so no data migration
 * is needed. Replaces the old unauthenticated static ResourceHandler (Application.java) —
 * see QA_Compliance_Consolidated_Report.md, Stage G Minor: "/uploads/** served with no
 * authentication — fault/signature photos world-readable if the URL is known."
 *
 * Every request must be authenticated (SecurityConfig no longer permitAlls this path).
 * Since an <img>/<Image> tag can't attach an Authorization header, callers may authenticate
 * either the normal way or via a ?token= query param (SecurityConfig's jwtAuthFilter fallback).
 *
 * Authorization: staff (any authenticated non-CLIENT role) may read any file — the same trust
 * boundary this codebase already extends staff on the Fault/Job detail views these photos come
 * from. A CLIENT may only read a file that genuinely belongs to one of their own Faults/Jobs —
 * checked by looking the exact path up in Fault.photoUrls and Job.completionPhotoUrls (the two
 * photo sets ever rendered to a Client anywhere in the app) rather than trusting the URL alone.
 */
@RestController
public class UploadServingController {

    @Value("${app.uploads.dir:uploads}")
    private String uploadsDir;

    private final FaultRepository faultRepository;
    private final JobRepository   jobRepository;

    public UploadServingController(FaultRepository faultRepository, JobRepository jobRepository) {
        this.faultRepository = faultRepository;
        this.jobRepository   = jobRepository;
    }

    @GetMapping("/uploads/{subfolder}/{filename:.+}")
    public ResponseEntity<Resource> serve(
            @PathVariable String subfolder,
            @PathVariable String filename,
            @AuthenticationPrincipal Long callerId) throws IOException {

        // Path traversal guard: resolve against the real uploads root and confirm the result
        // is still inside it, rather than trusting subfolder/filename not to contain "..".
        Path root = Paths.get(uploadsDir).toAbsolutePath().normalize();
        Path target = root.resolve(subfolder).resolve(filename).normalize();
        if (!target.startsWith(root) || !Files.exists(target) || !Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }

        String requestPath = "/uploads/" + subfolder + "/" + filename;
        if (!isStaff() && !ownsPhoto(callerId, requestPath)) {
            throw new AccessDeniedException("You do not have access to this file.");
        }

        Resource resource = new FileSystemResource(target);
        MediaType contentType = filename.toLowerCase().endsWith(".png")
            ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;

        return ResponseEntity.ok()
            .contentType(contentType)
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .body(resource);
    }

    private boolean isStaff() {
        return SecurityContextHolder.getContext().getAuthentication()
            .getAuthorities().stream()
            .noneMatch(a -> a.getAuthority().equals("ROLE_CLIENT"));
    }

    /** True if the caller's own Fault (evidence photos) or Job (after-service photos) carries this exact path. */
    private boolean ownsPhoto(Long callerId, String path) {
        for (Fault f : faultRepository.findByPhotoUrlsContaining(path)) {
            if (callerId.equals(f.getCustomerId())) {
                return true;
            }
        }
        for (Job j : jobRepository.findByCompletionPhotoUrlsContaining(path)) {
            if (callerId.equals(j.getCustomerId())) {
                return true;
            }
        }
        return false;
    }
}
