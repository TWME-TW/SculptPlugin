package dev.twme.sculpt.blueprint;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.Gson;

/** Persists SculptWeb edit credentials outside shareable blueprint files. */
final class BlueprintPublicationStore {

    private static final int MAX_ENTRIES = 10_000;
    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path blueprintsDir;
    private final Gson gson;

    BlueprintPublicationStore(final Path blueprintsDir, final Gson gson) {
        this.blueprintsDir = blueprintsDir;
        this.gson = gson;
    }

    Path path(final UUID playerId) {
        return blueprintsDir.resolve("private").resolve(playerId.toString())
            .resolve("web-publications.json");
    }

    synchronized void save(final UUID playerId, final Publication publication)
            throws IOException {
        requireValid(publication);
        PublicationIndex index = readIndex(playerId);
        index.publications.removeIf(existing ->
            existing.localBlueprintId.equals(publication.localBlueprintId));
        if (index.publications.size() >= MAX_ENTRIES) {
            throw new IOException("Too many stored SculptWeb publications");
        }
        index.publications.add(publication);
        index.publications.sort(Comparator.comparingLong(Publication::publishedAt).reversed());
        writeIndex(playerId, index);
    }

    synchronized void saveIfAbsent(final UUID playerId, final Publication publication)
            throws IOException {
        requireValid(publication);
        PublicationIndex index = readIndex(playerId);
        boolean exists = index.publications.stream().anyMatch(existing ->
            existing.localBlueprintId.equals(publication.localBlueprintId));
        if (exists) return;
        if (index.publications.size() >= MAX_ENTRIES) {
            throw new IOException("Too many stored SculptWeb publications");
        }
        index.publications.add(publication);
        index.publications.sort(Comparator.comparingLong(Publication::publishedAt).reversed());
        writeIndex(playerId, index);
    }

    synchronized void verifyWritable(final UUID playerId) throws IOException {
        writeIndex(playerId, readIndex(playerId));
    }

    synchronized boolean remove(final UUID playerId, final UUID localBlueprintId)
            throws IOException {
        PublicationIndex index = readIndex(playerId);
        boolean removed = index.publications.removeIf(publication ->
            publication.localBlueprintId.equals(localBlueprintId));
        if (removed) writeIndex(playerId, index);
        return removed;
    }

    synchronized void rename(final UUID playerId, final UUID localBlueprintId,
                             final String name) throws IOException {
        PublicationIndex index = readIndex(playerId);
        for (int i = 0; i < index.publications.size(); i++) {
            Publication publication = index.publications.get(i);
            if (publication.localBlueprintId.equals(localBlueprintId)) {
                index.publications.set(i, new Publication(
                    publication.localBlueprintId, publication.remoteBlueprintId, name,
                    publication.editToken, publication.collectionUri,
                    publication.shareUrl, publication.publishedAt));
                writeIndex(playerId, index);
                return;
            }
        }
    }

    synchronized List<Publication> list(final UUID playerId) throws IOException {
        return List.copyOf(readIndex(playerId).publications);
    }

    @Nullable
    synchronized Publication find(final UUID playerId, final String nameOrId)
            throws IOException {
        PublicationIndex index = readIndex(playerId);
        UUID id = parseUuid(nameOrId);
        if (id != null) {
            for (Publication publication : index.publications) {
                if (publication.localBlueprintId.equals(id)
                        || publication.remoteBlueprintId.equals(id)) {
                    return publication;
                }
            }
        }
        for (Publication publication : index.publications) {
            if (publication.name.equals(nameOrId)) return publication;
        }
        return null;
    }

    private PublicationIndex readIndex(final UUID playerId) throws IOException {
        Path file = path(playerId);
        if (!Files.exists(file)) return new PublicationIndex();
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("SculptWeb publication index exceeds the allowed size");
        }
        PublicationIndex index;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            index = gson.fromJson(reader, PublicationIndex.class);
        } catch (RuntimeException e) {
            throw new IOException("Malformed SculptWeb publication index", e);
        }
        if (index == null) throw new IOException("Empty SculptWeb publication index");
        if (index.publications == null) index.publications = new ArrayList<>();
        if (index.publications.size() > MAX_ENTRIES) {
            throw new IOException("Too many stored SculptWeb publications");
        }
        for (Publication publication : index.publications) requireValid(publication);
        index.version = 1;
        return index;
    }

    private void writeIndex(final UUID playerId, final PublicationIndex index)
            throws IOException {
        Path target = path(playerId);
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Publication index has no parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(
            parent, target.getFileName().toString(), ".tmp");
        try {
            restrictPermissions(temporary);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                gson.toJson(index, writer);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrictPermissions(final Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Non-POSIX file systems use their native access controls.
        }
    }

    private static void requireValid(@Nullable final Publication publication)
            throws IOException {
        if (publication == null || publication.localBlueprintId == null
                || publication.remoteBlueprintId == null
                || publication.name == null || publication.name.isBlank()
                || publication.name.codePointCount(0, publication.name.length()) > 64
                || !validSecret(publication.editToken)
                || tooLong(publication.collectionUri, 2_048)
                || tooLong(publication.shareUrl, 2_048)) {
            throw new IOException("Invalid SculptWeb publication entry");
        }
    }

    private static boolean validSecret(@Nullable final String value) {
        return value != null && !value.isBlank() && value.length() <= 512
            && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static boolean tooLong(@Nullable final String value, final int maximum) {
        return value != null && value.length() > maximum;
    }

    @Nullable
    private static UUID parseUuid(final String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record Publication(
        UUID localBlueprintId,
        UUID remoteBlueprintId,
        String name,
        String editToken,
        @Nullable String collectionUri,
        @Nullable String shareUrl,
        long publishedAt
    ) {}

    private static final class PublicationIndex {
        int version = 1;
        List<Publication> publications = new ArrayList<>();
    }
}
