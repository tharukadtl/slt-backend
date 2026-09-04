package lk.slt.fieldops.controller;

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
 * JOB-008 companion — the legitimate-upload half of the magic-byte fix.
 *
 * <p>{@code JobSecurityTest} proves the attack cases are now rejected (PHP web shell declared as
 * {@code image/jpeg}, 0-byte file) and that a genuine JPEG is still accepted. This class covers
 * what that row does not: that a genuine <b>PNG</b> — the other allowed format, whose signature is
 * a completely different 8 bytes — still uploads successfully, and that rejection is driven by the
 * real file content rather than by the filename extension.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FileUploadContentValidationTest {

    @Autowired private MockMvc          mvc;
    @Autowired private JwtTokenProvider jwt;

    private static final Long TECH_ID   = 9411L;
    private static final Long BRANCH_ID = 1L;

    private String techBearer() {
        return "Bearer " + jwt.createAccessToken(TECH_ID, "uploadtech", "TECHNICIAN", BRANCH_ID);
    }

    private MvcResult upload(String filename, String contentType, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/uploads/photos")
                .file(new MockMultipartFile("files", filename, contentType, content))
                .header("Authorization", techBearer()))
            .andReturn();
    }

    /** 2KB of pixel-ish filler behind a real signature. */
    private static byte[] imageWithSignature(byte[] signature) {
        byte[] data = new byte[2048];
        java.util.Arrays.fill(data, (byte) 0x41);
        System.arraycopy(signature, 0, data, 0, signature.length);
        return data;
    }

    private static byte[] jpegBytes() {
        return imageWithSignature(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 });
    }

    private static byte[] pngBytes() {
        return imageWithSignature(new byte[] {
            (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
            (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A });
    }

    @Test
    void genuineImages_stillUpload_andContentDecidesRejection() throws Exception {
        // ── A genuine PNG must still be stored ───────────────────────────────────────────
        MvcResult png = upload("after.png", "image/png", pngBytes());

        // ── A genuine JPEG must still be stored ──────────────────────────────────────────
        MvcResult jpeg = upload("after.jpg", "image/jpeg", jpegBytes());

        // ── A text payload with a perfectly innocent .jpg name and image/jpeg header ─────
        // Nothing about the extension or the header is wrong here — only the bytes are.
        byte[] text = "this is not an image, it is plain text".getBytes(StandardCharsets.UTF_8);
        MvcResult fake = upload("holiday.jpg", "image/jpeg", text);

        assertAll("upload validation acts on file content",

            () -> assertEquals(200, png.getResponse().getStatus(),
                "A genuine PNG (89 50 4E 47 0D 0A 1A 0A) must still be accepted after the "
                    + "magic-byte check was added. Body: " + png.getResponse().getContentAsString()),

            () -> assertTrue(png.getResponse().getContentAsString().contains(".png"),
                "A genuine PNG must be stored with a .png URL. Body: "
                    + png.getResponse().getContentAsString()),

            () -> assertEquals(200, jpeg.getResponse().getStatus(),
                "A genuine JPEG (FF D8 FF) must still be accepted. Body: "
                    + jpeg.getResponse().getContentAsString()),

            () -> assertEquals(400, fake.getResponse().getStatus(),
                "A file whose bytes are not a JPEG or PNG must be rejected even when both the "
                    + "filename extension and the declared Content-Type say image/jpeg — the "
                    + "check must read the content, not the claims. Got "
                    + fake.getResponse().getStatus() + ": "
                    + fake.getResponse().getContentAsString())
        );
    }
}
