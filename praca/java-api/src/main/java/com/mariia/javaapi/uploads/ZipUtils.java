package com.mariia.javaapi.uploads;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.*;

public final class ZipUtils {
    private ZipUtils(){}

    public static void unzip(Path zipFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        // ZipFile z commons-compress obsługuje STORED + data descriptor
        try (ZipFile zf = new ZipFile(zipFile.toFile())) {
            var entries = zf.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry e = entries.nextElement();

                Path out = targetDir.resolve(e.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new IOException("Zip traversal attempt: " + e.getName());
                }

                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }

                Files.createDirectories(out.getParent());
                try (InputStream is = zf.getInputStream(e);
                     OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    is.transferTo(os);
                }
            }
        }
    }
}
