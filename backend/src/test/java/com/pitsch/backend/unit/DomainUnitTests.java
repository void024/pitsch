package com.pitsch.backend.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.files.FileValidator;
import com.pitsch.backend.workflow.WorkflowStatus;
import org.junit.jupiter.api.Test;

class DomainUnitTests {

    @Test
    void workflowStateMachineAllowsOnlyDocumentedTransitions() {
        assertTrue(WorkflowStatus.canTransition("RECEIVED", "CLASSIFYING"));
        assertTrue(WorkflowStatus.canTransition("AWAITING_USER", "PROCESSING"));
        assertTrue(WorkflowStatus.canTransition("PROCESSING", "WAITING_FOR_APPROVAL"));
        assertTrue(WorkflowStatus.canTransition("FAILED", "PROCESSING"));
        assertTrue(WorkflowStatus.canTransition("NOT_PITCH", "PROCESSING"));
        assertFalse(WorkflowStatus.canTransition("COMPLETED", "PROCESSING"), "final states are final");
        assertFalse(WorkflowStatus.canTransition("STOPPED", "AWAITING_USER"));
        assertFalse(WorkflowStatus.canTransition("RECEIVED", "COMPLETED"), "cannot skip the pipeline");
        assertFalse(WorkflowStatus.canTransition("NOT_PITCH", "COMPLETED"));
        for (String s : WorkflowStatus.ALL) {
            if (!WorkflowStatus.isFinal(s)) {
                assertTrue(WorkflowStatus.canTransition(s, "STOPPED"), s + " must be stoppable");
            }
        }
        ApiException e = assertThrows(ApiException.class, () -> WorkflowStatus.requireTransition("COMPLETED", "FAILED"));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.getCode());
    }

    static FileValidator validator() {
        PitschProperties p = new PitschProperties();
        p.getUploads().setMaxFileMb(1);
        p.getUploads().setMaxPptxUncompressedMb(1);
        p.getUploads().setMaxZipEntries(50);
        return new FileValidator(p);
    }

    @Test
    void filesAreValidatedByContentNotByName() {
        FileValidator v = validator();
        assertEquals(FileValidator.Kind.PDF, v.validate("deck.pdf", "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII)).kind());
        assertEquals(FileValidator.Kind.TEXT, v.validate("notes.md", "# Notes\nhello".getBytes(StandardCharsets.UTF_8)).kind());
        // An executable renamed to .pdf is refused.
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xff, (byte) 0xff, 0, 0};
        ApiException e = assertThrows(ApiException.class, () -> v.validate("deck.pdf", exe));
        assertEquals(ErrorCode.UNSUPPORTED_MEDIA_TYPE, e.getCode());
        assertEquals(ErrorCode.FILE_REJECTED, assertThrows(ApiException.class, () -> v.validate("x.pdf", new byte[0])).getCode());
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE,
                assertThrows(ApiException.class, () -> v.validate("big.txt", new byte[2 * 1024 * 1024])).getCode());
    }

    @Test
    void filenamesAreSanitised() {
        String name = FileValidator.sanitizeFilename("../../etc/passwd\u0000<script>.pdf", FileValidator.Kind.PDF);
        assertFalse(name.contains("/"));
        assertFalse(name.contains(".."));
        assertFalse(name.contains("<"));
        assertTrue(name.endsWith(".pdf"));
        assertTrue(FileValidator.sanitizeFilename("report.exe", FileValidator.Kind.PDF).endsWith(".pdf"),
                "the extension follows the detected type");
    }

    @Test
    void zipBombsAreRefused() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ppt/presentation.xml"));
            zip.write("<p/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ppt/media/huge.bin"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 3; i++) {
                zip.write(zeros);   // 3 MB of zeros compresses to a few KB
            }
            zip.closeEntry();
        }
        ApiException e = assertThrows(ApiException.class, () -> validator().validate("deck.pptx", bytes.toByteArray()));
        assertEquals(ErrorCode.FILE_REJECTED, e.getCode());
    }
}
