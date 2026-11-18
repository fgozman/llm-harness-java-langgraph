package com.example.organizer.journal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helpers for the application's own files (checkpoint database, locks, journals, backups).
 *
 * - Owner-only access: the checkpoints contain file excerpts and the backups contain whole files,
 *   so other users of the machine must not be able to read them. Only POSIX systems (Linux, macOS)
 *   are handled; on others the operating system defaults stay.
 * - Folder keys: a short, file-name-safe name per organized folder, used to name its lock, journal
 *   and backups (the folder path itself may contain any character).
 */
public final class DataFiles {

    private static final Logger log = LoggerFactory.getLogger(DataFiles.class);

    private DataFiles() {
    }

    /** Creates the directory (and parents) if needed and makes it accessible to the owner only (rwx------). */
    public static Path privateDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        restrict(directory, "rwx------");
        return directory;
    }

    /** Makes an existing file readable and writable by the owner only (rw-------). */
    public static void privateFile(Path file) throws IOException {
        if (Files.exists(file)) {
            restrict(file, "rw-------");
        }
    }

    /** The key of an organized folder, e.g. "275d81ec6185247c278c3d27417d7e81". */
    static String folderKey(Path folder) {
        String path = folder.toAbsolutePath().normalize().toString();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            // 16 bytes = 32 hex characters: short, and collisions are practically impossible.
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", exception);
        }
    }

    private static void restrict(Path path, String permissions) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) {
            log.debug("No POSIX permissions on this system, leaving {} as it is", path);
            return;
        }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }
}
