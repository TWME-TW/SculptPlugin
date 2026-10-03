# Gizmo 拖曳操作研究報告

> 狀態：**已實作**。見 `feat/transform-gizmo`。
> 基準：`main` @ `101a01e`（已含 PR #6 雙面預覽與平滑次數）。
> 研究日期：2026-10-03。
>
> 實作結果與 §6 待決策問題的結論：
> 1. Gizmo **取代**「抓起／放下」，不並存。
> 2. 旋轉保留連續角度（吸附級距 90°／45°／15°／5°／1°／自由），非 90° 的倍數以目的地重取樣實作。
> 3. 把手仍只服務 `TransformTool`；`Gizmo` 已與 Bukkit 解耦（吃射線而非 `Player`），日後通用化不需要再拆。
> 4. 不做可調吸附值；平移固定吸附 1 體素（1/16 格）。
>
> 實作時另外修掉三個既有缺陷：`VoxelRotation.destination()` 的 epsilon 方向錯誤會少一格、鏡射方塊與平移箭頭重疊導致箭頭點不到、`editor.transform.reset` 語言鍵重複定義。
> 參考對象：[PZDonny/DisplayEntityUtils](https://github.com/PZDonny/DisplayEntityUtils) @ `d1326b0`（GPL-3.0）。

## 1. 摘要

DisplayEntityUtils（下稱 DEU）的 Gizmo 是**在 3D 空間中用滑鼠直接拖曳**的變形控制器：三個軸向箭頭做平移、三個平面方塊做平面平移、三個圓環做旋轉、三個方塊做縮放，中央白色方塊可複製選取。它把「選一個軸 → 拖曳 → 放開」變成一種肌肉記憶，不需要打字也不需要輪盤。

Sculpt 目前的變形工具（`TransformTool`）語意完全不同：

| 面向 | Sculpt 現在 | DEU Gizmo |
| --- | --- | --- |
| 位移 | 左鍵「抓起」選取，移動視線讓幽靈跟隨，再左鍵放下 | 左鍵鎖定軸向把手後，把手持續跟隨視線（§2.4） |
| 旋轉 | `Shift`+滾輪，固定 90° 級距 | 拖曳圓環，連續角度（可吸附） |
| 鏡射 | 對話框勾選 | 無 |
| 縮放 | 無（體素模型不縮放） | 拖曳方塊 |
| 吸附 | 無（體素本來就對齊格線） | 可調吸附值 + 開關 |
| 軸向 | 只有世界軸 | 世界軸／區域軸切換 |

**結論：Gizmo 的互動模型值得引進，但不能照抄 DEU 的程式碼。**

原因有三，第 1 點是硬性的：

1. **授權不相容。** DEU 是 GPL-3.0，Sculpt 是 Apache-2.0。直接複製或改寫 DEU 的 Java 原始碼會讓 Sculpt 變成 GPL 衍生作品。**互動設計與數學（射線／軸線最近距離、射線／平面交點、吸附四捨五入）屬於方法與演算法，不受著作權保護，可以獨立重寫。** 因此本報告只借鏡**概念與數學**，所有程式碼都從 Sculpt 自己的 `PreviewScene`、`VoxelBox`、`VoxelTransform` 重寫。
2. **資料模型不同。** DEU 拖曳的是 Display 實體（浮點 transform），Sculpt 拖曳的是**體素選取**（整數、最小單位 1/16 方塊）。Sculpt 沒有「縮放」與「連續角度」這兩種操作的位置——體素不能被縮放，旋轉只能是 90° 的倍數（`VoxelTransform` 只有 `quarterTurns`）。
3. **輸入通道不同。** DEU 用 Bukkit 事件（`PlayerInteractEvent`、`EntityDamageByEntityEvent`）並用「Gizmo 魔杖」物品當開關；Sculpt 已經有一套**封包層**輸入（`InputInterceptor` + `HotbarOverlay`），真實背包是隱藏的。Gizmo 必須接進 Sculpt 的既有通道，而不是另外拉一條事件流。

因此建議的落地方式是：**保留 Sculpt 的體素語意，把「視線跟隨」換成「拖曳軸向把手」**，並且只實作對體素有實質意義的軸（平移、旋轉 90°、鏡射）。

---

## 2. DEU Gizmo 的設計拆解

### 2.1 模型與控制點

Gizmo 本體是一個 `.deg` 模型檔（`plugin/src/main/resources/models/gizmo/gizmo.deg`，4 KB），以**封包實體群組**（`PacketDisplayEntityGroup`）生成，只給操作者看、可發光、不持久化：

```java
// GizmoSessionImpl 建構子（節錄）
GroupSpawnSettings settings = new GroupSpawnSettings()
        .setTeleportationDuration(SCAN_FREQUENCY)   // 掃描頻率 = 1 tick
        .visibleByDefault(false, null)
        .allowPersistenceOverride(false)
        .persistentByDefault(false);
gizmoModel = SAVED_GIZMO_MODEL.createPacketGroup(finalSpawnLoc, INTERNAL, settings);
gizmoModel.removeCulling();
gizmoModel.glow();
gizmoModel.setSelectable(false);
```

模型中的每個部件帶有**標籤**（tag），控制項用標籤找出自己負責的部件來改變發光顏色：

| 標籤 | 控制項 | 顏色 |
| --- | --- | --- |
| `move_x` / `move_y` / `move_z` | 平移軸向箭頭 | 紅 / 綠 / 藍 |
| `move_yz` / `move_zx` / `move_yx` | 平移平面方塊 | 紅 / 綠 / 藍 |
| `rotate_x` / `rotate_y` / `rotate_z` | 旋轉圓環 | 紅 / 綠 / 藍 |
| `scale_x` / `scale_y` / `scale_z` | 縮放方塊 | 紅 / 綠 / 藍 |
| `pivot` | 中央白色方塊（複製） | 白 |

`GizmoAxis` 這個 enum 同時定義了方向、顏色與標籤，是整個設計的單一真實來源：

```java
public enum GizmoAxis {
    CENTER(Color.WHITE, "pivot", new Vector3f(0,0,0)),
    X(Color.RED,  "move_x", new Vector3f(1, 0, 0)),
    Y(Color.LIME, "move_y", new Vector3f(0, 1, 0)),
    Z(Color.BLUE, "move_z", new Vector3f(0, 0, 1)),
    YZ(Color.RED,  "move_yz", new Vector3f(0,1,0), new Vector3f(0,0,1)),
    ZX(Color.LIME, "move_zx", new Vector3f(0,0,1), new Vector3f(1,0,0)),
    XY(Color.BLUE, "move_yx", new Vector3f(1,0,0), new Vector3f(0,1,0));

    public boolean isPlane() { return directions.length == 2; }
    public String getRotationTag() { return isPlane() ? null : "rotate_" + tag.substring(5); }
    public String getScaleTag()    { return isPlane() ? null : "scale_"  + tag.substring(5); }
}
```

### 2.2 抽象層

三個抽象類別把「控制項」與「拖曳行為」分開：

```
Control (axis, controlType, MAX_DISTANCE = 15)
├── Selector        ← 只負責「視線有沒有打中我」＋「產生對應的 Drag」
│   ├── AxisSelector
│   │   ├── TranslationAxisSelector     （細圓柱，radius 0.075）
│   │   └── CubeSelector → ScaleSelector / CloneSelector（radius 0.125）
│   ├── TranslationPlaneSelector        （0.25 見方的方塊）
│   └── RotationSelector                （半徑 1.125、厚度 0.075 的環）
└── Drag            ← 只負責「每一 tick 依視線更新位置」
    ├── TranslationAxisDrag / TranslationPlaneDrag
    ├── RotationDrag
    └── ScaleDrag
```

關鍵分工：**Selector 管命中測試，Drag 管套用變形。** 每一 tick 的掃描只做兩件事——沒有拖曳時找 hover 中的 Selector 並發光，有拖曳時呼叫 `activeDrag.updatePosition(player)`：

```java
// GizmoSessionImpl.scan()，每 1 tick
if (activeDrag == null) {                    // Selector
    Selector hovered = getCollidingControl(player);
    if (hovered != hoveredSelector) {
        if (hoveredSelector != null) hoveredSelector.unglow(gizmoModel);
        if (hovered != null) hovered.glow(gizmoModel);
        hoveredSelector = hovered;
    }
} else {                                     // Drag
    dragLock.lock();
    try { activeDrag.updatePosition(player); }
    finally { dragLock.unlock(); }
}
```

命中測試用「取最近」而不是「取第一個」：

```java
public Selector getCollidingControl(Player player) {
    float closest = Float.MAX_VALUE;
    Selector hovered = null;
    for (Selector c : selectors) {
        float hit = c.intersect(gizmoSpace, player, gizmoModel.getLocation());
        if (hit >= 0 && hit < closest) { closest = hit; hovered = c; }
    }
    return hovered;
}
```

`intersect()` 回傳的是**沿視線的距離**（`distanceAlongRay`），所以「最近」就是「畫面上最前面」。

### 2.3 幾何數學（可直接重用的部分）

**(a) 射線與軸線段的最近距離**——用來判斷視線是否掃到箭頭，同時排除背面與超出軸長的命中：

```java
// TranslationAxisSelector.intersect()（節錄，已改寫為可讀形式）
Vector3f axisDir = axisEnd.sub(axisStart);          // 軸線段
float axisLength = axisDir.length();
axisDir.normalize();

float rayDotAxis = ray.dot(axisDir);
float denom = 1 - rayDotAxis * rayDotAxis;          // 1 - cos²θ
if (denom < 1e-6f) return -1;                       // 平行或反向，無命中

float startDotAxis = axisStartToRayOrigin.dot(axisDir);
float distanceAlongRay =
        (rayDotAxis * startDotAxis - axisStartToRayOrigin.dot(ray)) / denom;
if (distanceAlongRay < 0) return -1;                // 在玩家背後

Vector3f closestPointOnRay = rayOrigin.fma(distanceAlongRay, ray);
float distanceAlongAxis = axisStartToRayOrigin
        .add(ray.mul(distanceAlongRay)).dot(axisDir);
if (distanceAlongAxis < 0 || distanceAlongAxis > axisLength) return -1;  // 超出線段

Vector3f axisPoint = axisStart.fma(distanceAlongAxis, axisDir);
if (closestPointOnRay.distance(axisPoint) > radius) return -1;          // 太遠
return distanceAlongRay;
```

**(b) 射線與平面交點**——平面平移、旋轉環、縮放都用它：

```java
float denom = ray.dot(planeNormal);
if (Math.abs(denom) < 1e-5f) return eyePos;          // 平行
float distance = planePoint.sub(eyePos).dot(planeNormal) / denom;
if (distance < 0.0f) return lastHitPoint;            // 在背後，維持上一點
distance = Math.min(distance, MAX_LOOK_DISTANCE * gizmo.getScale());
return eyePos.fma(distance, ray);
```

**(c) 軸向平移的關鍵一步**——把射線投影到「包含軸線且面向玩家」的平面上，再取軸向分量。這解決了「沿軸拖曳時滑鼠怎麼動」的問題：

```java
// 平面法線：玩家視線減去軸向分量，仍垂直於軸
private Vector3f createPlaneNormal(Vector3f playerLookDir) {
    float dot = playerLookDir.dot(currentAxesDir[0]);
    return playerLookDir.sub(currentAxesDir[0].mul(dot)).normalize();
}
```

拖曳時把「與上一 tick 的位移」投影到軸向，得到純量位移量：

```java
protected Vector3f getMovementAmounts(Player player, Vector3f delta) {
    return new Vector3f(delta.dot(currentAxesDir[0]), 0, 0);   // 只取軸向
}
```

**(d) 區域軸／世界軸**——LOCAL 時把軸向依 gizmo 的 pitch/yaw 旋轉：

```java
public static Vector3f rotate(Vector3f vec, GizmoSpace space, Location loc) {
    if (space == GizmoSpace.WORLD) return vec;
    return DisplayUtils.rotateVector(vec, loc.getPitch(), loc.getYaw());
}
```

**(e) 吸附**——累積位移再四捨五入，只送出「尚未套用的差額」，並在跨過一格時播音效：

```java
totalMovementAmounts.add(movementAmounts);
Vector3f snappedAmounts = new Vector3f(
        snapAmount(totalMovementAmounts.x, snapValue), /* y, z 同理 */);
Vector3f snappedDelta = snappedAmounts.sub(appliedSnappedAmounts);
appliedSnappedAmounts.set(snappedAmounts);
if (snappedDelta.lengthSquared() > 1e-6f) {
    player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 1, 1);   // 每跨一格喀一聲
}
return snappedDelta;
```

**(f) 旋轉**——把「上一 tick 方向」與「這一 tick 方向」投影到旋轉平面後取有號夾角：

```java
Vector3f currentDirection = hit.sub(pivotPoint).normalize();
float angle = lastDirection.angleSigned(currentDirection, dragAxis);
lastDirection.set(currentDirection);
totalAngleChange += angle;
// 吸附：snappedAngle = round(totalAngleChange / snapValueRad) * snapValueRad;
```

### 2.4 輸入對應（Gizmo 魔杖）

DEU 用一支 `STICK`（PDC 標記 `WAND`）當作 Gizmo 的開關，所有操作都掛在它身上：

| 輸入 | 動作 |
| --- | --- |
| 左鍵 | 選取 hover 中的軸（進入拖曳）；已有軸時切換「連結／解除連結」 |
| 右鍵 | 釋放選取的軸；沒有軸時切換選取模式（Group/Filter/Part） |
| 滾輪（已選軸時） | 調整該控制項的吸附值 |
| 換副手 | 切換世界軸／區域軸 |
| `Shift`+換副手 | 開關吸附 |
| 丟棄鍵（`Q`） | 切換平移模式（Translate/Teleport） |
| 換手持物 | 離開魔杖即停止掃描（`setScanning(false)`） |

**這裡有一個容易誤解的關鍵：DEU 的「拖曳」不是「按住滑鼠拖」，而是「點一下鎖定軸 → 把手持續跟隨視線 → 右鍵釋放」。**

證據：`activeDrag.updatePosition(player)` 只被呼叫一次——在 `GizmoSessionImpl.scan()` 這個**每 tick 執行的排程器**裡：

```java
DisplayAPI.getScheduler().entityRunTimerAsync(player, new Scheduler.SchedulerRunnable() {
    public void run() {
        ...
        if (activeDrag == null) { /* 找 hover 中的 Selector */ }
        else {
            dragLock.lock();
            try { activeDrag.updatePosition(player); }   // ← 唯一呼叫點，每 tick
            finally { dragLock.unlock(); }
        }
    }
}, 0, SCAN_FREQUENCY);                                   // SCAN_FREQUENCY = 1 tick
```

`updatePosition()` 內部也是「比對上一 tick 的命中點」而非讀取滑鼠按鍵狀態：

```java
// TranslationDrag.updateTranslationMovement()
Vector3f hit = playerRayAndPlaneCollision(player);
Vector3f deltaFromLast = hit.sub(this.lastHitPoint, new Vector3f());   // 與上一 tick 的差
...
this.lastHitPoint.set(hit);
```

所以 DEU 的拖曳狀態是由 **Selector/Drag 物件是否存在**（`activeDrag != null`）表達，不是由滑鼠按鍵表達。左鍵只是「建立 Drag」，右鍵只是「丟掉 Drag」。

這個發現對 Sculpt 的可行性影響很大：**Sculpt 不需要攔截「按住滑鼠」這個動作，只需要一次左鍵事件就能進入拖曳。** 現有的 `PlayerInteractEvent` 左鍵事件（`EditorListener.onInteract`）已經足夠，不必新增任何封包處理。

注意 `changeGizmoSnapValue()` 判斷滾輪方向的方式：

```java
int difference = newSlot - oldSlot;
if (difference == 1 || difference == -8)      isScrollDown = true;   // 含環繞
else if (difference == -1 || difference == 8) isScrollDown = false;
else return true;                                                    // 數字鍵 → 取消
```

這與 Sculpt `EditorListener.scrollDirection()` 的做法一致，可以直接對照。

### 2.5 值得借鏡的五個設計決策

1. **掃描與套用分離。** Selector 只回答「打中沒有、多遠」，Drag 只回答「這一 tick 要位移多少」。新增一種控制項不必碰掃描迴圈。
2. **用距離排序取最近命中。** 軸向把手在畫面上會重疊，取最近才符合直覺。
3. **拖曳時鎖住玩家移動。** `selectHovered()` 在建立 Drag 前先 `player.setVelocity(new Vector())`，避免邊走邊拖。Sculpt 若沿用「點一下鎖定」的語意，同樣需要在進入拖曳時清一次速度。
4. **`player.isSneaking()` 在拖曳中＝暫停。** `TranslationDrag`、`RotationDrag`、`ScaleDrag` 的 `updatePosition()` 第一行都是 `if (player.isSneaking()) return;`，等於免費的「暫時凍結」，放開蹲下就繼續拖。
5. **每一格都有回饋。** 選軸、跨格、切模式都有音效與 title/subtitle，操作有明確的觸感。

---

## 3. 授權與可行性

### 3.1 授權

| 專案 | 授權 |
| --- | --- |
| Sculpt（本外掛） | Apache-2.0（`pom.xml`、`LICENSE`） |
| DisplayEntityUtils | GPL-3.0（`LICENSE`） |

GPL-3.0 具有傳染性。**不得複製 DEU 的原始碼、模型檔或任何受著作權保護的表達。** 本報告引用的片段僅作為設計說明與數學公式的佐證；實際實作必須獨立撰寫。數學方法、演算法、互動概念不受著作權保護，可以自由實作。

`gizmo.deg` 模型檔同樣是 DEU 的著作，**不可取用**；Sculpt 要用 `PreviewScene` 自己的線框／面片畫出把手。

### 3.2 與 Sculpt 資料模型的落差

| DEU 操作 | Sculpt 對應 | 說明 |
| --- | --- | --- |
| 軸向平移 | ✅ 可做 | `VoxelTransform` 的 `offsetX/Y/Z` |
| 平面平移 | ✅ 可做 | 同時改變兩個 offset |
| 軸向旋轉 | ⚠️ 需量化 | 體素只能 90° 的倍數（`quarterTurns`） |
| 縮放 | ❌ 無意義 | 體素不可縮放；材質與碰撞都會失真 |
| 鏡射 | ✅ 可做 | `mirrorX` / `mirrorZ` 已存在 |
| 複製（中央方塊） | ✅ 可做 | `TransformTool.copy` 已存在 |
| 吸附 | ⚠️ 已隱含 | 體素本來就吸附到 1/16 格；可改成「吸附到整格」 |
| 世界軸／區域軸 | ⚠️ 有限 | Sculpt 選取是軸對齊盒，沒有 pitch/yaw，只有世界軸 |

**結論：可行的把手是「三軸平移箭頭 + 三個平面方塊 + 三個旋轉環（每格 90°）+ 中央複製方塊」。縮放不做。**

---

## 4. 建議設計

### 4.1 沿用既有的預覽系統

Gizmo 不需要新的渲染路徑。`PreviewScene` 現有的四種圖元剛好夠用：

| 把手 | 圖元 | 顏色 |
| --- | --- | --- |
| 軸向箭頭 | `outline()`（細長盒）＋ 尖端小方塊 | X 紅、Y 綠、Z 藍 |
| 平面方塊 | `faces()`（半透明面） | 同軸色 |
| 旋轉環 | `polyline()`（圓，24 段） | 同軸色 |
| 中央複製方塊 | `outline()` | `Colors.TRANSFORM` |

hover 中的把手改用 `Colors.CURSOR` 或提高 alpha 表示發光——DEU 是改實體發光顏色，Sculpt 改 `PreviewScene` 的顏色即可，語意相同且零額外成本。

把手尺寸方面，DEU 是用 `/deu gizmo scale <n>` **手動**調整整個 Gizmo 模型的縮放（`GizmoSessionImpl.setScale()` 會同步縮放所有 Selector 的命中半徑與長度），並用 `Control.MAX_DISTANCE = 15` 限制互動距離；它並沒有依玩家距離自動縮放。

Sculpt 若要更好的手感，建議改成**依玩家眼到 pivot 的距離自動縮放**把手，否則遠處的把手會小到按不到。`PreviewScene` 目前把 `VIEW_RANGE` 固定為 4，把手尺寸需要在 `preview()` 每 tick 重算——這是**唯一需要新增的預覽能力**。

### 4.2 拖曳狀態機

沿用 DEU「點一下鎖定、持續跟隨、明確釋放」的模型，並對應到 Sculpt 既有的按鍵語意：

```
IDLE ──左鍵(命中把手)──▶ DRAGGING(axis, anchorPoint, lastHit)
DRAGGING ──每 tick──▶ 依視線更新 offset / quarterTurns / mirror
DRAGGING ──左鍵(未命中把手)──▶ IDLE（保留結果，等待確認）
DRAGGING ──Shift（蹲下）──▶ 凍結（不更新，但不結束）
DRAGGING ──Q──▶ 還原並回到 IDLE
IDLE ──右鍵──▶ 套用（沿用現有 commit）
```

按鍵對照（右邊是 Sculpt 現況，刻意保持一致以免重新學習）：

| 動作 | DEU | Sculpt 建議 |
| --- | --- | --- |
| 鎖定軸／進入拖曳 | 左鍵 | 左鍵（原本就是「抓起」，語意相同） |
| 釋放 | 右鍵 | 左鍵（原本就是「放下」，語意相同） |
| 確認套用 | — | 右鍵（沿用） |
| 取消 | — | `Q`（沿用） |
| 暫停 | 蹲下 | 蹲下（沿用 DEU 的做法） |

與現有 `TransformTool` 的實質差異只有一處：**位移不再來自「玩家看著哪裡」，而是來自「拖曳把手的投影位移」**。這解決目前最大的手感問題——現在要移動選取必須先抓起、再轉頭、再放下，視線一動幽靈就跟著跑；有了把手之後，拖曳軸向箭頭時只有沿軸的分量會生效，轉頭不會亂跑。

### 4.3 與封包輸入層整合

Sculpt 已經有 `InputInterceptor`（讀 `Q`／`F`／右鍵）與 `HotbarOverlay`（隱藏真實背包）。Gizmo 需要新增的只有：

- **進入拖曳**：完全不需要新的輸入。左鍵（`PlayerInteractEvent` 的 `LEFT_CLICK_AIR` / `LEFT_CLICK_BLOCK`）已經是單次事件，正好對應 DEU「點一下鎖定軸」的語意；每 tick 更新則由 `EditorSession` 現有的 `tick()` 排程負責（`scene.beginFrame()` → `tool().preview(this)` → `scene.tick()`）。
- **若日後真的想支援「按住不放」**：`InputInterceptor` 已能讀到 `PLAYER_DIGGING`，封包常數是 `START_DIGGING` / `FINISHED_DIGGING` / `CANCELLED_DIGGING`（已對 `packetevents-api` 2.14.0 核對），可以據此實作真正的按住語意。但這是選配，不是必要條件。
- **不需要新增封包處理**是本次研究最重要的可行性結論：**Gizmo 拖曳可以完全建立在現有的事件與 tick 之上。**
- **滾輪改吸附值**：拖曳中 `Shift`+滾輪目前是「調整工具大小」（`EditorSession.adjust`）。拖曳時可以改為調整吸附值，沿用 `EditorListener.scrollDirection()`。
- **取消**：`Q` 放棄拖曳並還原，與現有 `cancel()` 一致。

### 4.4 分階段實作

| 階段 | 內容 | 預估 |
| --- | --- | --- |
| **G1** | 軸向平移把手（3 箭頭 + 3 平面）＋ 拖曳狀態機 ＋ hover 高亮 | 中 |
| **G2** | 旋轉環（每格 90°，跨格音效）＋ 鏡射把手 | 中 |
| **G3** | 中央複製方塊、整格吸附開關、把手恆定螢幕尺寸 | 小 |
| **G4**（可選） | 取代 `TransformTool` 的「抓起／放下」，或兩者並存由設定切換 | 小 |

### 4.5 風險與取捨

| 風險 | 說明 | 對策 |
| --- | --- | --- |
| 把手點不到 | Sculpt 的預覽只在 `VIEW_RANGE = 4` 內可見，且把手是線框不是實體 | 把手恆定螢幕尺寸；命中半徑放寬（DEU 用 0.075–0.125，Sculpt 可用 0.15） |
| 與既有「抓起」衝突 | 兩種位移語意同時存在會混淆 | G4 前先以 `Shift`+右鍵設定切換，或直接替換並在 README 說明 |
| 旋轉只有 90° | 拖曳圓環卻只能跳 90° 會覺得卡 | 圓環分成四段視覺刻度，拖曳時直接吸附到最近的 90°，並在 HUD 顯示目前角度 |
| 體素 vs 浮點 | 拖曳產生的是連續位移，需量化成體素 | 沿用 `snapAmount()` 概念，但吸附單位固定為 1 體素（或整格） |
| 授權 | 不可參考 DEU 原始碼 | 只依本報告的數學描述獨立實作；模型自行以 `PreviewScene` 繪製 |

---

## 5. 驗證計畫

1. **單元**：把手命中測試（射線／軸線段最近距離、射線／平面交點）以固定座標與方向斷言，含平行、背後、超出線段、超出半徑四種邊界。
2. **單元**：拖曳位移量化——連續位移經吸附後必須落在體素格線上，且總位移與拖曳距離單調。
3. **實機**（Paper 1.21.11 + PacketEvents 2.14.0 + Mineflayer）：以原始封包驅動 `START_DESTROY_BLOCK`／`STOP_DESTROY_BLOCK` 模擬按住拖曳，驗證：
   - hover 中的把手顏色改變；
   - 拖曳期間選取幽靈跟著把手走，且**玩家轉頭不會改變位移**（與現行「抓起」行為的關鍵差異）；
   - 放開後右鍵套用，世界變更與幽靈一致；
   - `Q` 取消後世界與選取皆還原。
4. **回歸**：`mvn verify` 全綠；`TransformTool` 既有測試（`VoxelTransformTest`、`EditorToolLogicTest`）不受影響。

---

## 6. 待決策問題

1. **Gizmo 要取代「抓起／放下」，還是並存？** 並存需要一個切換開關（設定或 `Shift`+右鍵），取代則要改 README 與既有肌肉記憶。
2. **旋轉要不要保留連續角度？** 體素最細到 1/16，理論上可以支援 45°／22.5° 的量化旋轉，但那需要非軸對齊的體素重取樣（目前 `VoxelTransform` 只做軸對稱的 90° 旋轉），成本與失真都要再評估。
3. **把手是否也要給「選取框」以外的對象？** 例如藍圖放置的預覽、形狀工具的控制點。若通用化，`GizmoSession` 應該抽成不依賴 `TransformTool` 的獨立元件。
4. **是否需要 `DisplayEntityUtils` 的 `Snap` 那種「可調吸附值」？** 體素模型的自然吸附是 1/16 格，可調的意義不大；建議只提供「吸附到整格」開關。
