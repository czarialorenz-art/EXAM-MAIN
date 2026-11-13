package data;

import exceptions.DataPersistenceException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * All disk access lives here (no file code anywhere else).
 * <ul>
 *   <li><b>Safe writes:</b> data is written to {@code name.tmp}, the current file is copied to
 *       {@code name.bak}, then the temp file is moved over the original. A crash mid-write never
 *       destroys the last good copy.</li>
 *   <li><b>Recovery:</b> an unreadable file is renamed to {@code name.corrupt-<timestamp>}
 *       (never deleted) and the backup is loaded instead. If that also fails the application
 *       starts with empty data and reports a warning.</li>
 *   <li><b>Security:</b> deserialization is limited to this application's classes and the JDK.</li>
 * </ul>
 */
public class FileManager {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ObjectInputFilter FILTER =
            ObjectInputFilter.Config.createFilter("models.*;data.*;java.base/*;!*");

    private final Path dataDir;
    private final List<String> warnings = new ArrayList<>();

    public FileManager(Path dataDir) throws DataPersistenceException {
        this.dataDir = dataDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dataDir);
        } catch (IOException e) {
            throw new DataPersistenceException("Cannot create the data folder " + this.dataDir + ": " + e.getMessage(), e);
        }
    }

    public Path getDataDir() {
        return dataDir;
    }

    /** Writes an object safely (temp file + backup + atomic replace). */
    public synchronized void save(String fileName, Serializable data) throws DataPersistenceException {
        Path target = dataDir.resolve(fileName);
        Path tmp = dataDir.resolve(fileName + ".tmp");
        Path bak = dataDir.resolve(fileName + ".bak");
        try {
            try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeObject(data);
            }
            if (Files.exists(target)) {
                Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DataPersistenceException("Could not save " + fileName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Loads an object. Missing file -> {@code empty} (normal on first run). Corrupted file ->
     * quarantine it, try the backup, otherwise {@code empty}. Never throws.
     */
    public synchronized <T> T load(String fileName, Class<T> type, Supplier<T> empty) {
        Path target = dataDir.resolve(fileName);
        Path bak = dataDir.resolve(fileName + ".bak");
        if (!Files.exists(target)) {
            if (Files.exists(bak)) {
                warnings.add(fileName + " was missing; restoring it from its backup.");
                return loadBackup(fileName, bak, type, empty);
            }
            return empty.get();
        }
        try {
            return read(target, type);
        } catch (IOException | ClassNotFoundException | ClassCastException | IllegalStateException e) {
            String quarantined = quarantine(target);
            warnings.add(fileName + " could not be read (" + e.getClass().getSimpleName() + "). The damaged file was kept as "
                    + quarantined + ".");
            return loadBackup(fileName, bak, type, empty);
        }
    }

    private <T> T loadBackup(String fileName, Path bak, Class<T> type, Supplier<T> empty) {
        if (Files.exists(bak)) {
            try {
                T value = read(bak, type);
                warnings.add(fileName + " was restored from its backup copy (the most recent change may be missing).");
                return value;
            } catch (IOException | ClassNotFoundException | ClassCastException | IllegalStateException e) {
                warnings.add("The backup of " + fileName + " is also unreadable.");
            }
        }
        warnings.add(fileName + ": starting with no records.");
        return empty.get();
    }

    private <T> T read(Path path, Class<T> type) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            in.setObjectInputFilter(FILTER);
            Object o = in.readObject();
            if (!type.isInstance(o)) {
                throw new InvalidClassException("Unexpected content: " + (o == null ? "null" : o.getClass().getName()));
            }
            return type.cast(o);
        }
    }

    private String quarantine(Path file) {
        Path dest = file.resolveSibling(file.getFileName() + ".corrupt-" + LocalDateTime.now().format(STAMP));
        try {
            Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest.getFileName().toString();
        } catch (IOException e) {
            return file.getFileName() + " (could not be renamed: " + e.getMessage() + ")";
        }
    }

    /** Writes a plain-text report into data/reports and returns its path. */
    public synchronized Path writeTextReport(String baseName, String content) throws DataPersistenceException {
        String safe = baseName.replaceAll("[^A-Za-z0-9_-]+", "_");
        Path dir = dataDir.resolve("reports");
        Path file = dir.resolve(safe + "_" + LocalDateTime.now().format(STAMP) + ".txt");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new DataPersistenceException("Could not write report: " + e.getMessage(), e);
        }
    }

    /** Copies every .dat file into data/backups/backup-<timestamp>/. */
    public synchronized Path backupAll() throws DataPersistenceException {
        Path dir = dataDir.resolve("backups").resolve("backup-" + LocalDateTime.now().format(STAMP));
        try {
            Files.createDirectories(dir);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dataDir, "*.dat")) {
                for (Path f : files) {
                    Files.copy(f, dir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return dir;
        } catch (IOException e) {
            throw new DataPersistenceException("Backup failed: " + e.getMessage(), e);
        }
    }

    /** Warnings collected while loading (shown to the user at startup). */
    public synchronized List<String> getWarnings() {
        return new ArrayList<>(warnings);
    }
}
