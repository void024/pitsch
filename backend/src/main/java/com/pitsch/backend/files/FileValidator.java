package com.pitsch.backend.files;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.config.PitschProperties;
import org.springframework.stereotype.Component;

/**
 * Validates untrusted uploads before they are stored: size limit, type detected from content (magic bytes) rather
 * than the client's claim, filename sanitisation, and zip-bomb protection for PPTX (entry count, total uncompressed
 * size, compression ratio). Supported: PDF, PPTX, plain text / Markdown / CSV.
 */
@Component
public class FileValidator {

    public enum Kind {
        PDF("application/pdf", ".pdf"),
        PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"),
        TEXT("text/plain", ".txt");

        public final String contentType;
        public final String extension;

        Kind(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    public record Validated(Kind kind, String filename, String contentType) { }

    private static final int MAX_RATIO = 100;
    private final PitschProperties.Uploads limits;

    public FileValidator(PitschProperties props) {
        this.limits = props.getUploads();
    }

    public Validated validate(String declaredName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw reject("The file is empty.");
        }
        if (bytes.length > limits.maxFileBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Files must be at most " + limits.getMaxFileMb() + " MB.");
        }
        Kind kind = detect(bytes);
        if (kind == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Only PDF, PowerPoint (.pptx) and text files are supported.");
        }
        if (kind == Kind.PPTX) {
            inspectZip(bytes);
        }
        String name = sanitizeFilename(declaredName, kind);
        String contentType = kind == Kind.TEXT && name.endsWith(".md") ? "text/markdown"
                : kind == Kind.TEXT && name.endsWith(".csv") ? "text/csv" : kind.contentType;
        return new Validated(kind, name, contentType);
    }

    static Kind detect(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return Kind.PDF;
        }
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            return isPptx(b) ? Kind.PPTX : null;
        }
        return isText(b) ? Kind.TEXT : null;
    }

    private static boolean isPptx(byte[] b) {
        boolean contentTypes = false;
        boolean presentation = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(b))) {
            ZipEntry e;
            int n = 0;
            while ((e = zip.getNextEntry()) != null && n++ < 5000) {
                contentTypes |= "[Content_Types].xml".equals(e.getName());
                presentation |= "ppt/presentation.xml".equals(e.getName());
                if (contentTypes && presentation) {
                    return true;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
        return false;
    }

    private static boolean isText(byte[] b) {
        for (byte x : b) {
            if (x == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private void inspectZip(byte[] bytes) {
        long maxTotal = limits.getMaxPptxUncompressedMb() * 1024L * 1024L;
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > limits.getMaxZipEntries()) {
                    throw reject("The presentation contains too many parts.");
                }
                String name = e.getName();
                if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                    throw reject("The presentation contains invalid paths.");
                }
                int read;
                InputStream in = zip;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > maxTotal) {
                        throw reject("The presentation expands to more than " + limits.getMaxPptxUncompressedMb() + " MB.");
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw reject("The presentation could not be read.");
        }
        if (total > (long) bytes.length * MAX_RATIO && total > 10L * 1024 * 1024) {
            throw reject("The presentation has a suspicious compression ratio.");
        }
    }

    /** Keeps only a safe base name; the extension always matches the detected type. */
    public static String sanitizeFilename(String declared, Kind kind) {
        String name = declared == null ? "" : declared;
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").replaceAll("[^\\w .()\\-]", "_").trim();
        String lower = name.toLowerCase(Locale.ROOT);
        String ext = kind.extension;
        if (kind == Kind.TEXT && (lower.endsWith(".md") || lower.endsWith(".csv"))) {
            ext = lower.substring(lower.lastIndexOf('.'));
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceAll("^[.\\s]+", "");
        if (base.isEmpty()) {
            base = "attachment";
        }
        if (base.length() > 100) {
            base = base.substring(0, 100);
        }
        return base + ext;
    }

    private static ApiException reject(String message) {
        return new ApiException(ErrorCode.FILE_REJECTED, message);
    }
}
