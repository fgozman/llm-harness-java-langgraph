package com.example.organizer.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.organizer.config.OrganizerProperties;

/**
 * Makes sure only one run works on a folder at a time (also across processes).
 *
 * The lock is an operating-system file lock on data/locks/&lt;folder key&gt;.lock, so nothing is written
 * into the organized folder. The OS releases it when the process ends, even after a crash, so a
 * crashed run never blocks the next start. The lock files are never deleted: deleting them would
 * open a small window in which two processes could lock different files for the same folder.
 */
@Component
public class FolderLocks {

    private static final Logger log = LoggerFactory.getLogger(FolderLocks.class);

    private final Path lockDirectory;

    public FolderLocks(OrganizerProperties properties) {
        this.lockDirectory = properties.dataDirectory().toAbsolutePath().normalize().resolve("locks");
    }

    /**
     * @return the held lock; close it to release
     * @throws FolderBusyException when another run holds the lock
     */
    public Lock acquire(Path folder) {
        Path lockFile = lockDirectory.resolve(DataFiles.folderKey(folder) + ".lock");
        FileChannel channel = null;
        try {
            DataFiles.privateDirectory(lockDirectory);
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new FolderBusyException(folder);
            }
            log.debug("Locked {} ({})", folder, lockFile.getFileName());
            return new Lock(channel, lock);
        } catch (OverlappingFileLockException exception) {
            // Another run in this same JVM holds it.
            closeQuietly(channel);
            throw new FolderBusyException(folder);
        } catch (IOException exception) {
            closeQuietly(channel);
            throw new UncheckedIOException("Could not lock " + folder, exception);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // nothing more to do
        }
    }

    /** A held folder lock. */
    public static final class Lock implements AutoCloseable {

        private final FileChannel channel;
        private final FileLock lock;

        private Lock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // closing the channel below releases it anyway
            }
            closeQuietly(channel);
        }
    }

    /** Another run is already working on the folder. */
    public static class FolderBusyException extends RuntimeException {

        public FolderBusyException(Path folder) {
            super("Another organizer run is already working on " + folder + ". Try again when it has finished.");
        }
    }
}
