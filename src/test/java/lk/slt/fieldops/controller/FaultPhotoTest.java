package lk.slt.fieldops.controller;

import lk.slt.fieldops.config.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * FAULT-005 (02_FAULT_TRACKING, FR-4) — evidence-photo upload limits: at most 5 photos,
 * at most 5MB each, JPEG/PNG only.
 *
 * <p><b>Row data corrected, not the code (2026-08-06).</b> The sheet row's test data asserts a cap
 * of 3 photos and a "Maximum 3 photos" message. SRS v1.8's Client fault-report field table
 * ({@code srs_full.txt} line 502) is explicit: {@code Photo Upload | Before-service evidence
 * photos | Multiple images, JPEG/PNG, max 5 files | Optional} — the same row that specifies the
 * Category Selector, Description Field, and Location Picker for this exact screen. The
 * application's cap of <b>5</b> ({@link FileUploadController}, {@code FaultService.joinPhotoUrls})
 * is therefore the spec-correct value; the sheet's "3" was stale, the same
 * stale-test-expectation shape as AUTH-004/AUTH-005. This test asserts the SRS-correct cap (5),
 * not the sheet's literal wording.</p>
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured, which is not a dependency of
 * this module; MockMvc through the real filter chain is the established convention here (see
 * {@link BillDisputeAmendmentIntegrationTest}) and hits the same controller, service and
 * exception handler.</p>
 *
 * <p><b>Endpoint reality.</b> The row names {@code POST /api/faults/{id}/photos}, which does not
 * exist. Photos are uploaded to the generic {@code POST /api/uploads/photos}
 * ({@link FileUploadController}, multipart part name {@code files}) and the returned URLs are then
 * referenced in the fault-report payload — {@code FaultService.joinPhotoUrls} re-validates them at
 * that point. Both of those are exercised below.</p>
 *
 * <p><b>What this row is expected to expose.</b> All four sub-checks run under {@code assertAll}
 * so every one produces a verdict rather than the first failure hiding the rest:
 * <ol>
 *   <li>5 valid JPEGs (the real cap) are accepted.</li>
 *   <li>A 6th photo in the same request is rejected with "maximum of 5".</li>
 *   <li>A 6MB photo is rejected as exceeding 5MB — {@code FileStorageService.store} now enforces
 *       this explicitly, since neither {@code application.yml} nor MockMvc's bypass of the
 *       servlet container's multipart parser provided any size check before.</li>
 *   <li>A PDF is rejected — {@code FileStorageService.store} allows only image/jpeg and
 *       image/png.</li>
 * </ol></p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FaultPhotoTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;

    private static final Long CLIENT_ID = 9101L;

    private String clientBearer() {
        return "Bearer " + jwt.createAccessToken(CLIENT_ID, "photoclient", "CLIENT", 1L);
    }

    /** A byte payload of the requested size — content, not pixels, is what the limits act on. */
    private static byte[] bytesOfSize(int sizeBytes) {
        byte[] data = new byte[sizeBytes];
        java.util.Arrays.fill(data, (byte) 0x41);
        return data;
    }

    /**
     * A JPEG of the requested size. Since the JOB-008 fix, {@code FileStorageService.store}
     * verifies the file's real magic bytes rather than trusting the declared Content-Type, so a
     * fixture that claims to be a JPEG has to actually start with the JPEG signature (FF D8 FF)
     * to represent a genuine photo. The size/count limits under test are unaffected.
     */
    private static MockMultipartFile jpeg(String name, int sizeBytes) {
        byte[] data = bytesOfSize(sizeBytes);
        if (data.length >= 4) {
            data[0] = (byte) 0xFF; data[1] = (byte) 0xD8; data[2] = (byte) 0xFF; data[3] = (byte) 0xE0;
        }
        return new MockMultipartFile("files", name, "image/jpeg", data);
    }

    private MvcResult upload(MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/uploads/photos");
        for (MockMultipartFile file : files) {
            request = (MockMultipartHttpServletRequestBuilder) request.file(file);
        }
        return mvc.perform(request.header("Authorization", clientBearer())).andReturn();
    }

    @Test
    void photoUpload_maxFiveEnforced() throws Exception {
        final int MB = 1024 * 1024;

        // ── Steps 1-5: five valid JPEGs (the real, SRS-specified cap) are accepted ────────
        List<String> acceptedResults = new ArrayList<>();
        int[] sizesMb = {2, 3, 1, 4, 1};
        for (int i = 0; i < sizesMb.length; i++) {
            MvcResult res = upload(jpeg("photo" + (i + 1) + ".jpg", sizesMb[i] * MB));
            acceptedResults.add("photo" + (i + 1) + " (" + sizesMb[i] + "MB) -> "
                + res.getResponse().getStatus() + " " + res.getResponse().getContentAsString());
        }

        // ── Step 6: a 6th photo in the same request must be rejected ─────────────────────
        MvcResult sixth = upload(
            jpeg("p1.jpg", 64), jpeg("p2.jpg", 64), jpeg("p3.jpg", 64),
            jpeg("p4.jpg", 64), jpeg("p5.jpg", 64), jpeg("p6.jpg", 64));
        int sixthStatus = sixth.getResponse().getStatus();
        String sixthBody = sixth.getResponse().getContentAsString();

        // ── Step 7: a 6MB photo must be rejected ─────────────────────────────────────────
        MvcResult oversized = upload(jpeg("huge.jpg", 6 * MB));
        int oversizedStatus = oversized.getResponse().getStatus();
        String oversizedBody = oversized.getResponse().getContentAsString();

        // ── Step 8: a PDF must be rejected ───────────────────────────────────────────────
        MvcResult pdf = mvc.perform(multipart("/api/uploads/photos")
                .file(new MockMultipartFile("files", "doc.pdf", "application/pdf", bytesOfSize(1024)))
                .header("Authorization", clientBearer()))
            .andReturn();
        int pdfStatus = pdf.getResponse().getStatus();
        String pdfBody = pdf.getResponse().getContentAsString();

        assertAll("photo upload limits",

            () -> assertTrue(acceptedResults.stream().allMatch(r -> r.contains("-> 200")),
                "All five valid JPEGs (<= 5MB each, the SRS v1.8 cap) must be accepted with 200. "
                    + "Results: " + acceptedResults),

            () -> {
                assertEquals(400, sixthStatus,
                    "A 6th photo must be rejected with 400 — the cap is 5 per request "
                        + "(FileUploadController) and 5 per fault (FaultService.joinPhotoUrls), "
                        + "matching SRS v1.8's Photo Upload field spec (\"max 5 files\"). "
                        + "Status " + sixthStatus + ", body: " + sixthBody);
                assertTrue(sixthBody.contains("maximum of 5") || sixthBody.contains("Maximum 5"),
                    "Rejection should say the maximum is 5 photos. Body: " + sixthBody);
            },

            () -> {
                assertEquals(400, oversizedStatus,
                    "A 6MB photo must be rejected as exceeding the 5MB limit — "
                        + "FileStorageService.store now enforces this explicitly. Status "
                        + oversizedStatus + ", body: " + oversizedBody);
                assertTrue(oversizedBody.contains("5MB") || oversizedBody.contains("maximum size"),
                    "Rejection should name the size limit. Body: " + oversizedBody);
            },

            () -> {
                assertEquals(400, pdfStatus,
                    "A PDF must be rejected with 400. Status " + pdfStatus + ", body: " + pdfBody);
                assertTrue(pdfBody.contains("JPEG/PNG") || pdfBody.contains("JPG/PNG"),
                    "Rejection should name the allowed image types. Body: " + pdfBody);
            }
        );
    }
}
