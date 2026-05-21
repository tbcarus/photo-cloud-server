package ru.tbcarus.photocloudserver.util;

import jakarta.xml.bind.DatatypeConverter;
import ru.tbcarus.photocloudserver.exception.FileSizeLimitExceededException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileUtils {

    private static final int BUFFER_SIZE = 8192;

    public static StreamedFileInfo writeAndCalculateSHA256(InputStream inputStream,
                                                           OutputStream outputStream,
                                                           long maxSizeBytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            long size = 0;
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                size += read;
                if (size > maxSizeBytes) {
                    throw new FileSizeLimitExceededException(maxSizeBytes);
                }
                digest.update(buffer, 0, read);
                outputStream.write(buffer, 0, read);
            }
            return new StreamedFileInfo(DatatypeConverter.printHexBinary(digest.digest()).toLowerCase(), size);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    public record StreamedFileInfo(String checksum, long size) {
    }
}
