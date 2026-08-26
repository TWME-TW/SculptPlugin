package dev.twme.sculpt.skin.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.twme.sculpt.assets.model.ModelResolver.VariantRotation;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;

/**
 * Writable SQLite view for one grid size in the plugin's runtime cache.
 * Multiple views can safely share one database file; SQLite WAL keeps readers
 * non-blocking while the bake executor commits a batch.
 */
public final class SqliteHeadsStore implements HeadsStore {

    private static final int SCHEMA_VERSION = 1;
    private static final int BUSY_TIMEOUT_MS = 5_000;

    private final Logger logger;
    private final Path path;
    private final int gridN;
    private final String mcVersion;
    private final String producer;
    private final Connection connection;

    public SqliteHeadsStore(Logger logger, Path path, int gridN,
                            String mcVersion, String producer) {
        if (logger == null || path == null) {
            throw new IllegalArgumentException("logger and path are required");
        }
        if (gridN < 1 || gridN > 16) {
            throw new IllegalArgumentException("gridN must be between 1 and 16: " + gridN);
        }
        this.logger = logger;
        this.path = path.toAbsolutePath().normalize();
        this.gridN = gridN;
        this.mcVersion = mcVersion == null ? "" : mcVersion;
        this.producer = producer == null ? "sculpt-runtime" : producer;
        Connection opened = null;
        try {
            Path parent = this.path.getParent();
            if (parent != null) Files.createDirectories(parent);
            opened = DriverManager.getConnection("jdbc:sqlite:" + this.path);
            this.connection = opened;
            configureConnection();
            initializeSchema();
        } catch (SQLException | java.io.IOException | RuntimeException e) {
            if (opened != null) {
                try { opened.close(); } catch (SQLException closeFailure) { e.addSuppressed(closeFailure); }
            }
            throw new IllegalStateException("Failed to open SQLite heads store " + this.path, e);
        }
    }

    public Path path() { return path; }

    @Override public int gridN() { return gridN; }

    private void configureConnection() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA temp_store=MEMORY");
            statement.execute("PRAGMA cache_size=-8192");
            statement.execute("PRAGMA mmap_size=67108864");
        }
    }

    private synchronized void initializeSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
                version = rs.next() ? rs.getInt(1) : 0;
            }
            if (version != 0 && version != SCHEMA_VERSION) {
                throw new SQLException("Unsupported SQLite heads schema version " + version);
            }
            statement.execute("CREATE TABLE IF NOT EXISTS metadata ("
                    + "key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS skins ("
                    + "hash TEXT PRIMARY KEY, value TEXT NOT NULL, signature TEXT NOT NULL, "
                    + "mineskin_uuid TEXT)");
            statement.execute("CREATE TABLE IF NOT EXISTS blocks ("
                    + "id INTEGER PRIMARY KEY, grid_n INTEGER NOT NULL, "
                    + "bake_key TEXT NOT NULL, block_key TEXT NOT NULL, tint_argb INTEGER NOT NULL, "
                    + "UNIQUE(grid_n, bake_key))");
            // A runtime block can retain hashes resolved from an SBH layer,
            // so chunks intentionally do not foreign-key into SQLite skins.
            statement.execute("CREATE TABLE IF NOT EXISTS chunks ("
                    + "block_id INTEGER NOT NULL REFERENCES blocks(id) ON DELETE CASCADE, "
                    + "x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, "
                    + "skin_hash TEXT NOT NULL, PRIMARY KEY(block_id, x, y, z)) WITHOUT ROWID");
            statement.execute("CREATE TABLE IF NOT EXISTS variants ("
                    + "block_id INTEGER NOT NULL REFERENCES blocks(id) ON DELETE CASCADE, "
                    + "variant_key TEXT NOT NULL, x_deg INTEGER NOT NULL, y_deg INTEGER NOT NULL, "
                    + "PRIMARY KEY(block_id, variant_key)) WITHOUT ROWID");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_blocks_grid_block "
                    + "ON blocks(grid_n, block_key)");
            if (version == 0) statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        }

        String storedVersion = metadataValue("mc_version");
        if (storedVersion != null && !storedVersion.equals(mcVersion)) {
            logger.info("[heads-sqlite] Minecraft version changed from " + storedVersion
                    + " to " + mcVersion + "; clearing runtime head cache");
            withTransaction(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM blocks");
                    statement.executeUpdate("DELETE FROM skins");
                }
                return null;
            });
        }
        upsertMetadata("mc_version", mcVersion);
        upsertMetadata("producer", producer);
    }

    @Override
    public synchronized Optional<Metadata> metadata() {
        String version = metadataValue("mc_version");
        String storedProducer = metadataValue("producer");
        if (version == null && storedProducer == null) return Optional.empty();
        return Optional.of(new Metadata(version == null ? "" : version,
                storedProducer == null ? "" : storedProducer));
    }

    @Override
    public synchronized Collection<BakeKey> listBlocks() {
        List<BakeKey> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT bake_key FROM blocks WHERE grid_n=? ORDER BY bake_key")) {
            ps.setInt(1, gridN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(BakeKey.parse(rs.getString(1)));
            }
            return out;
        } catch (SQLException | RuntimeException e) {
            throw failure("list blocks", e);
        }
    }

    @Override
    public synchronized Optional<StoredBlock> readBlock(BakeKey key) {
        long blockId = -1;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM blocks WHERE grid_n=? AND bake_key=?")) {
            ps.setInt(1, gridN);
            ps.setString(2, key.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) blockId = rs.getLong(1);
            }
        } catch (SQLException e) {
            throw failure("read block " + key, e);
        }
        if (blockId < 0) return Optional.empty();

        Map<ChunkCoord, String> chunks = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT x,y,z,skin_hash FROM chunks WHERE block_id=? ORDER BY y,z,x")) {
            ps.setLong(1, blockId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    chunks.put(new ChunkCoord(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                            rs.getString(4));
                }
            }
        } catch (SQLException e) {
            throw failure("read chunks for " + key, e);
        }

        Map<String, VariantRotation> variants = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT variant_key,x_deg,y_deg FROM variants WHERE block_id=? ORDER BY variant_key")) {
            ps.setLong(1, blockId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) variants.put(rs.getString(1),
                        new VariantRotation(rs.getInt(2), rs.getInt(3)));
            }
        } catch (SQLException e) {
            throw failure("read variants for " + key, e);
        }
        return Optional.of(new StoredBlock(key, Map.copyOf(chunks), Map.copyOf(variants)));
    }

    @Override
    public synchronized Optional<StoredSkin> readSkin(String hash) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value,signature,mineskin_uuid FROM skins WHERE hash=?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new StoredSkin(hash, rs.getString(1),
                        rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            throw failure("read skin " + hash, e);
        }
    }

    @Override
    public synchronized boolean skinExists(String hash) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM skins WHERE hash=? LIMIT 1")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw failure("check skin " + hash, e);
        }
    }

    @Override public boolean isWritable() { return true; }

    @Override
    public synchronized void writeSkin(StoredSkin skin) {
        writeBatchInternal(null, List.of(skin));
    }

    @Override
    public synchronized void writeBlock(StoredBlock block) {
        writeBatchInternal(block, Collections.emptyList());
    }

    @Override
    public synchronized void writeBatch(StoredBlock block, Collection<StoredSkin> skins) {
        writeBatchInternal(block, skins == null ? Collections.emptyList() : skins);
    }

    private void writeBatchInternal(StoredBlock block, Collection<StoredSkin> skins) {
        withTransaction(() -> {
            try (PreparedStatement skinPs = connection.prepareStatement(
                    "INSERT INTO skins(hash,value,signature,mineskin_uuid) VALUES(?,?,?,?) "
                            + "ON CONFLICT(hash) DO UPDATE SET value=excluded.value, "
                            + "signature=excluded.signature, mineskin_uuid=excluded.mineskin_uuid")) {
                for (StoredSkin skin : skins) {
                    skinPs.setString(1, required(skin.hash(), "skin hash"));
                    skinPs.setString(2, skin.value() == null ? "" : skin.value());
                    skinPs.setString(3, skin.signature() == null ? "" : skin.signature());
                    skinPs.setString(4, skin.mineskinUuid());
                    skinPs.addBatch();
                }
                skinPs.executeBatch();
            }
            if (block != null) writeBlockInTransaction(block);
            return null;
        });
    }

    private void writeBlockInTransaction(StoredBlock block) throws SQLException {
        if (block.key() == null || block.key().block() == null) {
            throw new IllegalArgumentException("block key is required");
        }
        if (block.chunkHashes() == null || block.variants() == null) {
            throw new IllegalArgumentException("block chunks and variants are required");
        }
        for (ChunkCoord coord : block.chunkHashes().keySet()) {
            if (coord == null || coord.x() < 0 || coord.y() < 0 || coord.z() < 0
                    || coord.x() >= gridN || coord.y() >= gridN || coord.z() >= gridN) {
                throw new IllegalArgumentException("chunk outside gridN=" + gridN + ": " + coord);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO blocks(grid_n,bake_key,block_key,tint_argb) VALUES(?,?,?,?) "
                        + "ON CONFLICT(grid_n,bake_key) DO UPDATE SET block_key=excluded.block_key, "
                        + "tint_argb=excluded.tint_argb")) {
            ps.setInt(1, gridN);
            ps.setString(2, block.key().toString());
            ps.setString(3, block.key().block().toString());
            ps.setInt(4, block.key().tintArgb());
            ps.executeUpdate();
        }
        long blockId;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM blocks WHERE grid_n=? AND bake_key=?")) {
            ps.setInt(1, gridN);
            ps.setString(2, block.key().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("block upsert did not return an id");
                blockId = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM chunks WHERE block_id=?")) {
            ps.setLong(1, blockId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM variants WHERE block_id=?")) {
            ps.setLong(1, blockId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO chunks(block_id,x,y,z,skin_hash) VALUES(?,?,?,?,?)")) {
            for (Map.Entry<ChunkCoord, String> entry : block.chunkHashes().entrySet()) {
                ChunkCoord coord = entry.getKey();
                ps.setLong(1, blockId);
                ps.setInt(2, coord.x());
                ps.setInt(3, coord.y());
                ps.setInt(4, coord.z());
                ps.setString(5, required(entry.getValue(), "chunk skin hash"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO variants(block_id,variant_key,x_deg,y_deg) VALUES(?,?,?,?)")) {
            for (Map.Entry<String, VariantRotation> entry : block.variants().entrySet()) {
                ps.setLong(1, blockId);
                ps.setString(2, entry.getKey());
                ps.setInt(3, entry.getValue().xDeg());
                ps.setInt(4, entry.getValue().yDeg());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public synchronized void removeBlock(BlockKey block) {
        withTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM blocks WHERE grid_n=? AND block_key=?")) {
                ps.setInt(1, gridN);
                ps.setString(2, block.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public synchronized void clearBlocks() {
        withTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM blocks WHERE grid_n=?")) {
                ps.setInt(1, gridN);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private String metadataValue(String key) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM metadata WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) {
            throw failure("read metadata " + key, e);
        }
    }

    private void upsertMetadata(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value == null ? "" : value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("write metadata " + key, e);
        }
    }

    private <T> T withTransaction(SqlWork<T> work) {
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run();
                connection.commit();
                return result;
            } catch (Exception e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                if (e instanceof RuntimeException re) throw re;
                throw new IllegalStateException(e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw failure("SQLite transaction", e);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> { T run() throws Exception; }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private IllegalStateException failure(String operation, Exception cause) {
        logger.log(Level.WARNING, "[heads-sqlite] failed to " + operation + " in " + path, cause);
        return new IllegalStateException("SQLite heads store failed to " + operation, cause);
    }

    @Override
    public synchronized void close() {
        try {
            if (connection.isClosed()) return;
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA optimize");
            }
            connection.close();
        } catch (SQLException e) {
            throw failure("close store", e);
        }
    }
}
