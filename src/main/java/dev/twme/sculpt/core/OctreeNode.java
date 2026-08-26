package dev.twme.sculpt.core;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.block.data.BlockData;

/**
 * 八元樹節點。核心資料結構，無 plugin 層依賴。
 *
 * <p>每個節點要嘛是葉子（children == null），要嘛是分支（children.length == 8）。
 * 只有葉子可以有 ItemDisplay（透過 head + handle）。分支節點的 mergedHead
 * 在 subdivide 時快取，供 coarsen 時使用（但 coarsen 現在會重新解析，
 * 見漏洞 #8 修復）。
 *
 * <p>座標系統：grid=16 空間。root 覆蓋 [0,16)³，每個 octant 翻倍精細度。
 * side(d) = 16 / 2^depth，minX/Y/Z 由路徑上的 octantIndex 位元決定。
 */
public final class OctreeNode {

    private static final int MAX_SUPPORTED_DEPTH = 4;
    private static final int MAX_SERIALIZED_BYTES = 1_048_576;
    private static final int MAX_BLOCK_DATA_BYTES = 4_096;
    private static final int MAX_NODE_COUNT = 4_681;

    // ====================== 樹結構 ======================

    private final OctreeNode parent;          // null for root
    private OctreeNode[] children;            // null if leaf
    private final int octantIndex;            // 0..7 within parent

    // ====================== 節點狀態 ======================

    private boolean removed;                  // 被挖除（僅對葉子有意義）
    private final int depth;                  // 從根算起 0..maxDepth

    // ====================== 方塊類型 ======================

    private BlockData blockData;             // 此節點涵蓋區域的 blockdata

    // ====================== 紋理座標 (選擇性) ======================

    private ChunkCoord textureCoord;         // null=使用物理座標(octant 路徑)查詢紋理

    /** Held player-head texture. Non-null leaves are indivisible edit cells. */
    private PlayerHeadTexture playerHeadTexture;

    // ====================== 實體 ======================

    ChunkHead head;                  // 非 null 表示有 ItemDisplay
    private dev.twme.sculpt.transport.DisplayHandle handle; // Bukkit entity handle

    public dev.twme.sculpt.transport.DisplayHandle handle() { return handle; }
    public void attachHandle(dev.twme.sculpt.transport.DisplayHandle h) { this.handle = h; }
    public void detachHandle() { this.handle = null; }

    // ====================== 快取標記 ======================

    private boolean hasAnyRemoved;            // 子孫中有任何 removed
    private boolean allRemoved;               // 所有子孫都是 removed

    // ====================== 座標快取 ======================

    private int cachedMinX;
    private int cachedMinY;
    private int cachedMinZ;

    // ====================== 葉子快取 ======================

    private List<OctreeNode> cachedLeaves;
    private boolean leafCacheValid;

    /** 樹結構或 removed 狀態變更時棄用快取（擴散到父鏈）。 */
    private void invalidateLeafCache() {
        this.leafCacheValid = false;
        this.cachedLeaves = null;
        OctreeNode n = parent;
        while (n != null) {
            n.leafCacheValid = false;
            n.cachedLeaves = null;
            n = n.parent;
        }
    }

    // ====================== 建構 ======================

    /** 建立 depth = 0 的 root 節點。 */
    public OctreeNode() {
        this.parent = null;
        this.octantIndex = -1;
        this.depth = 0;
        this.children = null;
        this.removed = false;
        this.blockData = null;
        this.head = null;
        this.hasAnyRemoved = false;
        this.allRemoved = false;
        this.cachedMinX = 0;
        this.cachedMinY = 0;
        this.cachedMinZ = 0;
    }

    /** 建立一個 depth = parent.depth + 1 的葉子，繼承 parent 的 blockData。 */
    OctreeNode(OctreeNode parent, int octantIndex) {
        this.parent = parent;
        this.octantIndex = octantIndex;
        this.depth = (parent != null) ? parent.depth + 1 : 0;
        this.children = null;
        this.removed = false;
        this.blockData = (parent != null) ? parent.blockData : null;
        this.head = null;
        this.hasAnyRemoved = false;
        this.allRemoved = false;
        // 座標快取：一次計算，避免 minX/Y/Z 分別遍歷父鏈
        if (parent != null) {
            final int s = side(); // = 16 >> depth
            this.cachedMinX = parent.cachedMinX + ((octantIndex & 4) != 0 ? s : 0);
            this.cachedMinY = parent.cachedMinY + ((octantIndex & 2) != 0 ? s : 0);
            this.cachedMinZ = parent.cachedMinZ + ((octantIndex & 1) != 0 ? s : 0);
        } else {
            this.cachedMinX = 0;
            this.cachedMinY = 0;
            this.cachedMinZ = 0;
        }
    }

    /**
     * 建立葉子並指定 blockData（用於 extendBlock 等不同材質的情境）。
     */
    OctreeNode(OctreeNode parent, int octantIndex, BlockData blockData) {
        this(parent, octantIndex);
        this.blockData = blockData;
    }

    // ====================== 查詢 ======================

    public OctreeNode parent() { return parent; }
    public OctreeNode[] children() { return children; }
    public int octantIndex() { return octantIndex; }
    public int depth() { return depth; }
    public boolean isLeaf() { return children == null; }
    public boolean isBranch() { return children != null; }
    public boolean isRemoved() { return removed; }
    public boolean hasAnyRemoved() { return hasAnyRemoved; }
    public boolean allRemoved() { return allRemoved; }
    public ChunkHead head() { return head; }
    public void setHead(ChunkHead head) { this.head = head; }
    public boolean hasEntity() { return head != null; }
    public BlockData blockData() { return blockData; }
    public void setBlockData(BlockData data) { this.blockData = data; }

    // ====================== 紋理座標 (選擇性) ======================

    /**
     * 回傳覆寫紋理查詢的 cell 座標，或 null 表示使用此節點的物理座標。
     * <p>當此值非 null 時，紋理查詢 ({@link HeadResolver#headFor}) 會用
     * textureCoord 取代 {@code minX/side()} 計算出的物理座標，
     * 讓此 cell 顯示不同位置的材質。ItemDisplay 的物理變換不受影響。
     */
    public ChunkCoord textureCoord() { return textureCoord; }

    /**
     * 設定覆寫紋理查詢的 cell 座標。傳入 null 以清除覆寫，恢復使用物理座標。
     * @param coord 要顯示紋理的 cell 座標，或 null
     */
    public void setTextureCoord(ChunkCoord coord) { this.textureCoord = coord; }

    public PlayerHeadTexture playerHeadTexture() { return playerHeadTexture; }

    public void setPlayerHeadTexture(PlayerHeadTexture texture) {
        if (texture != null && (!isLeaf() || depth == 0)) {
            throw new IllegalArgumentException(
                "Player-head textures require a non-root leaf");
        }
        this.playerHeadTexture = texture;
    }

    // ====================== 座標 ======================

    /** 此節點在 grid=16 空間中的邊長。side(d) = 16 / 2^d */
    public int side() {
        return 16 >> depth;
    }

    /** 此節點在 grid=16 空間中的最小角落 X（使用建構時快取）。 */
    public int minX() { return cachedMinX; }

    public int minY() { return cachedMinY; }

    public int minZ() { return cachedMinZ; }

    /**
     * 三軸合併回傳（使用快取值）。
     */
    public int[] minCorner() {
        return new int[]{cachedMinX, cachedMinY, cachedMinZ};
    }

    // ====================== 路徑字串 ======================

    /** 快取的路徑字串（首次呼叫後計算，subdivide/coarsen 時清除）。 */
    private String cachedPath = null;

    /** 清除路徑快取（subdivide 或 coarsen 時由父節點呼叫）。 */
    void invalidatePathCache() {
        this.cachedPath = null;
        if (children != null) {
            for (OctreeNode child : children) {
                child.invalidatePathCache();
            }
        }
    }

    /**
     * 回傳從 root 到此節點的路徑字串。
     * 例如 root→octant 3→octant 5→octant 2 → "3.5.2"
     * root 本身回傳空字串 ""。
     */
    public String pathAsString() {
        if (cachedPath != null) return cachedPath;
        if (parent == null) return cachedPath = "";
        final String pp = parent.pathAsString();
        cachedPath = pp.isEmpty()
            ? String.valueOf(octantIndex)
            : pp + "." + octantIndex;
        return cachedPath;
    }

    /**
     * 從 root 跟隨路徑取得節點。
     * @param path 路徑字串如 "3.5.2"，空字串回傳 root
     * @return 節點，若路徑上某節點尚未 subdivide 則回傳 null
     */
    public static OctreeNode fromPath(OctreeNode root, String path) {
        if (path == null || path.isEmpty()) return root;
        OctreeNode node = root;
        for (String part : path.split("\\.")) {
            int idx = Integer.parseInt(part);
            if (node.children == null) return null;
            node = node.children[idx];
        }
        return node;
    }

    // ====================== 遞迴搜尋 ======================

    /**
     * 在 grid=16 空間的絕對座標 (gx,gy,gz) ∈ [0,16)³ 找出最深葉子。
     * 可穿過已移除的葉子（回傳最深節點不論 removed）。
     */
    public OctreeNode findLeaf(int gx, int gy, int gz) {
        if (isLeaf()) return this;
        int half = side() / 2;
        int ox = minX(), oy = minY(), oz = minZ();
        int childIdx = 0;
        if (gx >= ox + half) childIdx |= 4;
        if (gy >= oy + half) childIdx |= 2;
        if (gz >= oz + half) childIdx |= 1;
        return children[childIdx].findLeaf(gx, gy, gz);
    }

    /** 收集此節點下所有未被移除的葉子（遞迴，結果快取）。 */
    public List<OctreeNode> collectLeaves() {
        if (leafCacheValid && cachedLeaves != null) return cachedLeaves;
        List<OctreeNode> result = new ArrayList<>();
        collectLeavesInto(result);
        this.cachedLeaves = result;
        this.leafCacheValid = true;
        return result;
    }

    private void collectLeavesInto(List<OctreeNode> out) {
        if (isLeaf()) {
            if (!removed) out.add(this);
        } else {
            for (OctreeNode child : children) {
                child.collectLeavesInto(out);
            }
        }
    }

    /** 收集此節點下所有葉子（不論 removed，無快取）。 */
    public void collectAllLeaves(List<OctreeNode> out) {
        if (isLeaf()) {
            out.add(this);
        } else {
            for (OctreeNode child : children) {
                child.collectAllLeaves(out);
            }
        }
    }

    // ====================== 純資料操作 ======================

    /**
     * 細分此葉子：建立 8 個 children，轉為分支節點。
     * 僅操作樹資料結構，不產生實體。實體管理由 SculptBlock 負責。
     */
    public void subdivide() {
        if (!isLeaf()) return;
        if (playerHeadTexture != null) {
            throw new IllegalStateException(
                "An atomic player-head leaf cannot be subdivided");
        }
        invalidateLeafCache();
        invalidatePathCache();
        this.children = new OctreeNode[8];
        for (int i = 0; i < 8; i++) {
            this.children[i] = new OctreeNode(this, i);
        }
        // 分支節點的 removed 無意義，清除舊標記以免 updateParentCaches 誤判
        this.removed = false;
        this.hasAnyRemoved = false;
        this.allRemoved = false;
        updateParentCaches();
    }

    /**
     * 粗化此分支：移除 8 個 children，轉為葉子節點。
     * 前置條件：所有子葉子都 non-removed。
     * 僅操作樹資料結構。
     */
    public void coarsen() {
        if (!isBranch()) return;
        invalidateLeafCache();
        invalidatePathCache();
        this.children = null;
        this.playerHeadTexture = null;
        // 重設分支時期的 removed 狀態快取，避免父鏈 updateParentCaches 誤判
        this.hasAnyRemoved = false;
        this.allRemoved = false;
        updateParentCaches();
    }

    /** 標記此葉子為 removed。向上更新 hasAnyRemoved / allRemoved 快取。 */
    public void remove() {
        if (!isLeaf() || removed) return;
        invalidateLeafCache();
        this.removed = true;
        updateParentCaches();
    }

    /** 還原此葉子。向上更新快取。 */
    public void restore() {
        if (!isLeaf() || !removed) return;
        invalidateLeafCache();
        this.removed = false;
        updateParentCaches();
    }

    private void updateParentCaches() {
        OctreeNode n = this.parent;
        while (n != null) {
            boolean anyRem = false;
            boolean allRem = true;
            for (OctreeNode c : n.children) {
                boolean childAnyRemoved = c.isRemoved() || c.hasAnyRemoved();
                boolean childAllRemoved = c.isLeaf() ? c.isRemoved() : c.allRemoved();
                if (childAnyRemoved) anyRem = true;
                if (!childAllRemoved) allRem = false;
            }
            // 提早終止：狀態無變化則不再向上傳播
            if (anyRem == n.hasAnyRemoved && allRem == n.allRemoved) break;
            n.hasAnyRemoved = anyRem;
            n.allRemoved = allRem;
            n = n.parent;
        }
    }

    // ====================== equals / hashCode / toString ======================

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof OctreeNode n)) return false;
        // 由樹中的位置決定 identity
        if (depth != n.depth) return false;
        if (octantIndex != n.octantIndex) return false;
        if (parent == null) return n.parent == null;
        if (n.parent == null) return false;
        return parent.octantIndex == n.parent.octantIndex
            && parent.depth == n.parent.depth;
    }

    @Override
    public int hashCode() {
        return 31 * depth + octantIndex;
    }

    @Override
    public String toString() {
        return "OctreeNode{depth=" + depth + ", path=" + pathAsString()
            + ", leaf=" + isLeaf() + ", removed=" + removed + "}";
    }

    // ====================== 序列化 / 反序列化 ======================

    /**
     * 將此節點及其子樹序列化為 byte[]。
     * 使用先序深度優先遍歷 (preorder DFS)。格式見藍圖規格書 §2.2。
     * <p>
     * 每個節點 1 byte 標頭：
     * <pre>
     * Bit 7: leaf flag (1=leaf, 0=branch)
     * Bit 6: removed flag (僅 leaf 有效)
     * Bit 5: has custom blockData (僅 leaf 有效)
     * Bit 4: has textureCoord (僅 leaf 有效)
     * Bit 3: has held player-head texture (僅 leaf 有效)
     * Bit 2-0: reserved
     * </pre>
     * Leaf with custom blockData → 接著寫入 BlockData.asString().length (2 bytes) + UTF-8 bytes
     * Leaf with textureCoord → 接著寫入 3 bytes (x, y, z) 作為無號 byte
     *   (若同時有 blockData，寫在 blockData 之後)
     * Leaf with player-head texture → value length + UTF-8 value + signature
     *   length + UTF-8 signature（每個 length 皆為 2-byte unsigned integer）
     * Branch → 遞迴寫入 8 個 child
     *
     * @return 序列化後的 byte 陣列
     */
    public byte[] serialize() {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(256);
        serializeInto(baos);
        return baos.toByteArray();
    }

    private void serializeInto(java.io.ByteArrayOutputStream baos) {
        byte header = 0;
        if (isLeaf()) {
            header |= 0x80; // Bit 7: leaf flag
            if (removed) header |= 0x40; // Bit 6: removed
            if (blockData != null) {
                header |= 0x20; // Bit 5: has custom blockData
            }
            if (textureCoord != null) {
                header |= 0x10; // Bit 4: has textureCoord
            }
            if (playerHeadTexture != null) {
                header |= 0x08; // Bit 3: has held player-head texture
            }
        }
        ensureSerializedCapacity(baos, 1);
        baos.write(header);

        if (isLeaf()) {
            if (blockData != null) {
                String blockDataStr = blockData.getAsString();
                final byte[] bytes = blockDataStr.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
                if (bytes.length == 0 || bytes.length > MAX_BLOCK_DATA_BYTES) {
                    throw new IllegalStateException(
                        "BlockData exceeds serialized size limit");
                }
                ensureSerializedCapacity(baos, 2 + bytes.length);
                // 2 bytes length (big-endian)
                baos.write((bytes.length >> 8) & 0xFF);
                baos.write(bytes.length & 0xFF);
                baos.write(bytes, 0, bytes.length);
            }
            if (textureCoord != null) {
                ensureSerializedCapacity(baos, 3);
                // 3 bytes: x, y, z as unsigned byte (values 0..15)
                baos.write(textureCoord.x() & 0xFF);
                baos.write(textureCoord.y() & 0xFF);
                baos.write(textureCoord.z() & 0xFF);
            }
            if (playerHeadTexture != null) {
                writeLengthPrefixedUtf8(baos, playerHeadTexture.value());
                writeLengthPrefixedUtf8(baos, playerHeadTexture.signature());
            }
        } else {
            // Branch: recurse into 8 children
            for (OctreeNode child : children) {
                child.serializeInto(baos);
            }
        }
    }

    private static void writeLengthPrefixedUtf8(
            java.io.ByteArrayOutputStream out, String value) {
        final byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ensureSerializedCapacity(out, 2 + bytes.length);
        out.write((bytes.length >> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.write(bytes, 0, bytes.length);
    }

    private static void ensureSerializedCapacity(
            java.io.ByteArrayOutputStream out, int additionalBytes) {
        if ((long) out.size() + additionalBytes > MAX_SERIALIZED_BYTES) {
            throw new IllegalStateException("Octree data exceeds serialized size limit");
        }
    }

    /**
     * 從 byte[] 反序列化還原 OctreeNode 樹。
     * <p>
     * root 節點的 blockData 和 removed 狀態不會被序列化寫入（root 是 branch，
     * 這些狀態由 SculptBlock 管理），但當 root 有 blockData 時可由呼叫者設定。
     *
     * @param data     序列化資料
     * @param maxDepth 樹的最大深度
     * @return root OctreeNode
     * @throws IllegalArgumentException 若資料格式不符
     */
    public static OctreeNode deserialize(byte[] data, int maxDepth) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Octree data is empty");
        }
        if (data.length > MAX_SERIALIZED_BYTES) {
            throw new IllegalArgumentException("Octree data exceeds size limit");
        }
        if (maxDepth < 0 || maxDepth > MAX_SUPPORTED_DEPTH) {
            throw new IllegalArgumentException("Unsupported maxDepth: " + maxDepth);
        }
        OctreeNode root = new OctreeNode();
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
        int[] nodeCount = {0};
        deserializeInto(root, bais, maxDepth, 0, nodeCount);
        if (bais.available() != 0) {
            throw new IllegalArgumentException("Trailing octree data");
        }
        return root;
    }

    private static void deserializeInto(OctreeNode node, java.io.ByteArrayInputStream bais,
                                          int maxDepth, int currentDepth, int[] nodeCount) {
        if (++nodeCount[0] > MAX_NODE_COUNT) {
            throw new IllegalArgumentException("Octree node count exceeds limit");
        }
        int header = bais.read();
        if (header == -1) throw new IllegalArgumentException("Unexpected end of data");
        if ((header & 0x07) != 0) {
            throw new IllegalArgumentException("Unsupported octree header flags");
        }

        boolean isLeaf = (header & 0x80) != 0;

        if (isLeaf) {
            boolean removed = (header & 0x40) != 0;
            boolean hasBlockData = (header & 0x20) != 0;
            boolean hasTextureCoord = (header & 0x10) != 0;
            boolean hasPlayerHeadTexture = (header & 0x08) != 0;

            if (removed) {
                node.remove();
            }

            if (hasBlockData) {
                // Read 2 bytes length (big-endian)
                int lenHigh = bais.read();
                int lenLow = bais.read();
                if (lenHigh == -1 || lenLow == -1) throw new IllegalArgumentException("Unexpected end of data reading blockData length");
                int len = (lenHigh << 8) | lenLow;
                if (len == 0 || len > MAX_BLOCK_DATA_BYTES) {
                    throw new IllegalArgumentException("Invalid blockData length: " + len);
                }

                byte[] blockDataBytes = new byte[len];
                int read = bais.read(blockDataBytes, 0, len);
                if (read < len) throw new IllegalArgumentException("Unexpected end of data reading blockData");

                try {
                    String blockDataStr = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(blockDataBytes)).toString();
                    node.blockData = org.bukkit.Bukkit.createBlockData(blockDataStr);
                } catch (java.nio.charset.CharacterCodingException | IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid blockData", e);
                }
            }

            if (hasTextureCoord) {
                int tx = bais.read();
                int ty = bais.read();
                int tz = bais.read();
                if (tx == -1 || ty == -1 || tz == -1) {
                    throw new IllegalArgumentException("Unexpected end of data reading textureCoord");
                }
                if (tx > 15 || ty > 15 || tz > 15) {
                    throw new IllegalArgumentException("textureCoord is outside grid bounds");
                }
                node.textureCoord = new ChunkCoord(tx, ty, tz);
            }

            if (hasPlayerHeadTexture) {
                final String value = readLengthPrefixedUtf8(
                    bais, PlayerHeadTexture.MAX_VALUE_BYTES, false,
                    "player-head texture value");
                final String signature = readLengthPrefixedUtf8(
                    bais, PlayerHeadTexture.MAX_SIGNATURE_BYTES, true,
                    "player-head texture signature");
                try {
                    node.setPlayerHeadTexture(
                        new PlayerHeadTexture(value, signature));
                } catch (final IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Invalid player-head texture", exception);
                }
            }

            // Leaf node - no further recursion needed
        } else {
            if (header != 0) {
                throw new IllegalArgumentException("Branch node contains leaf-only flags");
            }
            if (currentDepth >= maxDepth) {
                throw new IllegalArgumentException("Branch exceeds maxDepth");
            }
            node.subdivide();
            for (OctreeNode child : node.children) {
                deserializeInto(child, bais, maxDepth, currentDepth + 1, nodeCount);
            }
        }
    }

    private static String readLengthPrefixedUtf8(
            java.io.ByteArrayInputStream input, int maximum, boolean emptyAllowed,
            String fieldName) {
        final int lenHigh = input.read();
        final int lenLow = input.read();
        if (lenHigh == -1 || lenLow == -1) {
            throw new IllegalArgumentException(
                "Unexpected end of data reading " + fieldName + " length");
        }
        final int length = (lenHigh << 8) | lenLow;
        if ((!emptyAllowed && length == 0) || length > maximum) {
            throw new IllegalArgumentException("Invalid " + fieldName + " length: " + length);
        }
        final byte[] bytes = new byte[length];
        final int read = input.read(bytes, 0, length);
        if (read != length) {
            throw new IllegalArgumentException("Unexpected end of data reading " + fieldName);
        }
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (final java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 " + fieldName, exception);
        }
    }
}
