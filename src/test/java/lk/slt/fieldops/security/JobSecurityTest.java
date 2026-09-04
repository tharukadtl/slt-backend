package lk.slt.fieldops.security;

import lk.slt.fieldops.config.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * JOB-008 (03_JOB_LIFECYCLE, FR-9) — job photo upload must accept images only: a JPEG is stored, a
 * PHP script disguised as an image, an SVG (a stored-XSS vector) and an empty file are all
 * rejected with 400.
 *
 * <p><b>Tool substitution.</b> Tool column says REST Assured, which is not a dependency of this
 * module; MockMvc through the real filter chain is the established convention here and drives the
 * same controller, service and {@code GlobalExceptionHandler}. Matches {@link FaultSecurityTest}
 * and {@code JobPhotoTest}.</p>
 *
 * <p><b>Endpoint reality.</b> {@code POST /api/jobs/{id}/photos} does not exist; job after-service
 * photos go through the shared {@code POST /api/uploads/photos} (multipart part name
 * {@code files}), whose only gatekeeper is {@code FileStorageService.store}. That is the method
 * under test.</p>
 *
 * <p>All four sub-checks run under {@code assertAll} so each produces its own verdict rather than
 * the first failure masking the rest.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JobSecurityTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;

    private static final Long TECH_ID   = 9401L;
    private static final Long BRANCH_ID = 1L;

    private String techBearer() {
        return "Bearer " + jwt.createAccessToken(TECH_ID, "jobsectech", "TECHNICIAN", BRANCH_ID);
    }

    private MvcResult upload(String filename, String contentType, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/uploads/photos")
                .file(new MockMultipartFile("files", filename, contentType, content))
                .header("Authorization", techBearer()))
            .andReturn();
    }

    private static byte[] jpegBytes() {
        byte[] data = new byte[2048];
        java.util.Arrays.fill(data, (byte) 0x41);
        // Real JPEG magic bytes, so a content-sniffing check would also see a valid image.
        data[0] = (byte) 0xFF; data[1] = (byte) 0xD8; data[2] = (byte) 0xFF; data[3] = (byte) 0xE0;
        return data;
    }

    @Test
    void fileUpload_phpFile_returns400() throws Exception {
        // ── Step 1: a genuine JPEG is accepted ───────────────────────────────────────────
        MvcResult jpeg = upload("after.jpg", "image/jpeg", jpegBytes());

        // ── Step 2: a PHP web shell that merely CLAIMS to be an image ────────────────────
        // This is the attack the row describes: the client controls the Content-Type header, so
        // declaring "image/jpeg" is free. The bytes are a working PHP shell.
        byte[] phpShell = "<?php system($_GET['cmd']); ?>".getBytes(StandardCharsets.UTF_8);
        MvcResult php = upload("shell.php", "image/jpeg", phpShell);

        // ── Step 3: an SVG — a stored-XSS vector, not a raster image ─────────────────────
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\">"
            + "<script>alert(document.cookie)</script></svg>").getBytes(StandardCharsets.UTF_8);
        MvcResult svgUpload = upload("xss.svg", "image/svg+xml", svg);

        // ── Step 4: an empty file ────────────────────────────────────────────────────────
        MvcResult empty = upload("empty.jpg", "image/jpeg", new byte[0]);

        assertAll("job photo upload accepts images only",

            () -> assertEquals(200, jpeg.getResponse().getStatus(),
                "A valid JPEG must be accepted. Body: " + jpeg.getResponse().getContentAsString()),

            () -> assertEquals(400, php.getResponse().getStatus(),
                "A PHP script uploaded with a spoofed image/jpeg Content-Type must be rejected. "
                    + "FileStorageService.store only compares the CLIENT-SUPPLIED Content-Type "
                    + "header against image/jpeg|image/png — it never inspects the file's actual "
                    + "bytes, so any attacker-controlled payload passes by simply lying about its "
                    + "type, and the file is written under /uploads/** which is served by the "
                    + "static resource handler. Got " + php.getResponse().getStatus() + ": "
                    + php.getResponse().getContentAsString()
                    + ". PRODUCTION CHANGE REQUIRED (magic-byte / content sniffing on upload, and "
                    + "an extension allow-list, not just the declared MIME type)."),

            () -> assertEquals(400, svgUpload.getResponse().getStatus(),
                "An SVG must be rejected — it is an XSS vector when served back inline. Body: "
                    + svgUpload.getResponse().getContentAsString()),

            () -> assertEquals(400, empty.getResponse().getStatus(),
                "An empty (0-byte) file must be rejected. FileStorageService.store checks only "
                    + "the upper size bound (5MB) and the declared content type, never that the "
                    + "file has any content at all, so a 0-byte 'photo' is stored and counted as "
                    + "valid after-service evidence. Got " + empty.getResponse().getStatus() + ": "
                    + empty.getResponse().getContentAsString()
                    + ". PRODUCTION CHANGE REQUIRED (reject file.isEmpty() / size == 0).")
        );
    }
}
