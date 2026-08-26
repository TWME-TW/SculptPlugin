# Sculpt

> [!WARNING]
> Most of this plugin was generated with AI. Review it, test it, and back up your data before using it on a production server.

Sculpt is a Paper plugin that lets players turn blocks into editable voxel sculptures. A block can be divided into `1×1×1`, `2×2×2`, `4×4×4`, `8×8×8`, or `16×16×16` cells; players can remove, restore, extend, and mix those cells, then save their work as blueprints.

This README is split into a [player guide](#for-players) and an [administrator guide](#for-administrators). The commands available to a player always depend on the permissions granted by the server. Run `/sculpt help` in game to see the commands you may use.

## For Players

### Quick start

1. Run `/sculpt mode on` to enable sculpting.
2. Press `F` to cycle through the resolutions you are allowed to use.
3. Press `Q` to cycle through your permitted fill modes.
4. Quickly press `Q` twice to change the display mode; quickly press `F` twice whenever you need to pause or resume sculpting without changing your preferences.
5. Left-click a supported block to turn it into a SculptBlock and remove the targeted cell.
6. Right-click a SculptBlock to place a cell using the block in your main hand.
7. Optionally run `/sculpt preview on` to show the cell currently under your cursor.

If a normal block does not convert, automatic conversion may be disabled, its material may not be supported, or the server's region protection may deny building there.

### Controls

| Input | Result |
| --- | --- |
| Left-click a SculptBlock | Remove the targeted cell. |
| Left-click a normal block | Convert it to a SculptBlock and remove the targeted cell. |
| Right-click a SculptBlock | Place the targeted cell using the block in your main hand. |
| Right-click an exposed edge | Extend the sculpture into the adjacent block position. |
| `F` | Cycle forward through permitted resolutions. |
| Quickly press `F` twice | Temporarily pause or resume Sculpt mode. Vanilla clicking and placement are restored while paused. |
| `Q` | Cycle through permitted fill modes without dropping the item. |
| Quickly press `Q` twice | Cycle through permitted display modes. |

You can target cells behind holes in a sculpture. At `1×1×1`, Sculpt does not create subdivided cells: left-clicking a SculptBlock removes the whole block and right-clicking restores its original block. Normal blocks retain vanilla interaction at this resolution.

`F` and `Q` become Sculpt controls only while Sculpt mode is active. A single press waits about 300 ms to make sure it is not part of a double press, so pausing or changing the display mode will not also change the resolution or fill mode. Every cycle skips choices the player does not have permission to use.

Pausing is a temporary safety state. It keeps the selected resolution, fill, and display settings, but clears when the player disconnects or the server restarts. Whether Sculpt mode itself is enabled remains associated with the player's UUID for the lifetime of the current server process.

### Mode, fill, and display

These settings are independent:

| Setting | Purpose |
| --- | --- |
| Sculpt mode | Controls whether ordinary clicks perform sculpting. |
| Fill mode | Controls the physical collision inside a SculptBlock. |
| Display mode | Controls how visible cells are rendered. |

Use `/sculpt mode on` to enable sculpting. Right-clicking with a block in your main hand uses that block as the material for a new cell. Quickly press `F` twice for a temporary pause, or use `/sculpt mode off` to disable sculpting completely.

Choose a fill mode with `/sculpt fill <mode>`:

| Mode | Collision behavior |
| --- | --- |
| `barrier` | The whole SculptBlock is backed by one full barrier block. |
| `shulker` | A full sculpture uses one barrier; partially carved shapes use shulker-based collision that follows the shape. |
| `null` | No physical collision is created; only interaction remains. |

Choose a display mode with `/sculpt display <mode>`:

| Mode | Rendering behavior |
| --- | --- |
| `head` | Uses pre-baked or MineSkin player-head textures. It uses fewer entities but cannot render transparent materials. |
| `textdisplay` | Renders cached vanilla textures as pixel planes, including transparency. Complex shapes can use more entities. |
| `auto` | Uses TextDisplay for transparent materials and while a non-transparent head texture is being prepared, then switches ready cells to heads. |

Player-head cells are always rendered as a complete texture unit and cannot be split into smaller cells.

Mode, fill, and display choices remain associated with the player's UUID when they leave and rejoin during the same server process. A server restart returns them to the defaults in `config.yml`.

### Player commands

| Command | Description |
| --- | --- |
| `/sculpt` or `/sculpt help` | Show the commands available to you. |
| `/sculpt resolution [1\|2\|4\|8\|16]` | Show or change your editing resolution. |
| `/sculpt preview [on\|off]` | Toggle the targeted-cell preview. |
| `/sculpt mode [on\|off]` | Show, enable, or disable persistent Sculpt mode. |
| `/sculpt fill [barrier\|shulker\|null]` | Show or choose a fill mode. |
| `/sculpt display [head\|textdisplay\|auto]` | Show or choose a display mode. |
| `/sculpt convert <fill> [single\|region]` | Change the fill mode of a looked-at SculptBlock or selected region. |
| `/sculpt replace <block data>` | Replace the material of blocks and SculptBlocks in a selected region while preserving their visible shape. |
| `/sculpt relight` | Remove legacy TextDisplay brightness overrides in the selected region and restore automatic environment lighting. |
| `/sculpt tool <selector\|blueprint>` | Receive a region or blueprint selector. |
| `/sculpt heads [search <query> [resolution]]` | Browse or search available head textures. |

### Region selection and replacement

Use `/sculpt tool selector` to receive a region selector. Left-click to set the first corner, right-click to set the second, and press `F` while holding the selector to clear the selection.

The same selection is used by `/sculpt convert` and `/sculpt replace`. `replace` accepts a bakeable full-block material, such as:

```text
/sculpt replace stone
/sculpt replace minecraft:oak_log[axis=x]
```

Regular full blocks remain vanilla blocks. Slabs, fences, walls, doors, panes, and other supported partial shapes are represented with Sculpt cells so their visual model is preserved. Decorative blocks without a collision shape, such as grass and flowers, are left unchanged.

### Blueprints

Blueprints can store one SculptBlock or a cuboid selection containing SculptBlocks, regular non-air blocks, their `BlockData`, relative positions, and empty space. Container contents and block-entity data are not stored.

1. Run `/sculpt tool blueprint` to receive the Blueprint Selector.
2. For a single SculptBlock, left-click it with the selector. Right-click pastes the current selection.
3. Quickly press `F` twice to switch to cuboid selection mode. Left-click the first point and right-click the second point; quickly press `F` twice again to return to single-block/paste mode.
4. Press `F` once to cancel the current single-block, cuboid, or unfinished first-corner selection.
5. Run `/sculpt blueprint save <name>`.

Common blueprint commands:

| Command | Description |
| --- | --- |
| `/sculpt blueprint list [--public] [--page <n>]` | List your blueprints. |
| `/sculpt blueprint save <name> [--public]` | Save the current selector result. |
| `/sculpt blueprint rename <old> <new>` | Rename a blueprint. |
| `/sculpt blueprint delete <name>` | Delete a blueprint. |
| `/sculpt blueprint give <name>` | Receive a blueprint item that can be right-clicked to paste. |
| `/sculpt blueprint bind <name>` | Bind a blueprint to the held item. |
| `/sculpt blueprint unbind` | Remove the blueprint binding from the held item. |
| `/sculpt blueprint settings` | Change your default paste settings. |
| `/sculpt blueprint publish <name> [--visibility <mode>]` | Publish to SculptWeb. |
| `/sculpt blueprint unpublish <name\|UUID>` | Remove a previously published SculptWeb copy. |
| `/sculpt blueprint download <url>` | Download from an administrator-approved SculptWeb domain. |
| `/sculpt blueprint export <name>` / `import <file>` | Export or import a server-side blueprint file. |

Blueprint paste options can control air, overwriting, adhesion, rotation, and mirroring. Use tab completion to see the options allowed for the command and your permissions.

## For Administrators

### Requirements

- Paper 1.21.11 or a compatible fork
- Java 21 or newer
- Optional: WorldEdit or FastAsyncWorldEdit (FAWE)
- Optional for `head` rendering: matching pre-baked head packs or a MineSkin API key

Sculpt supports Folia. WorldEdit and FAWE are optional soft dependencies; basic sculpting works without either plugin.

### Installation

1. Download the latest `Sculpt-*.jar` from the [Releases page](https://github.com/TWME-TW/SculptPlugin/releases).
2. Put the JAR in the server's `plugins/` directory.
3. Start the server once to create `plugins/Sculpt/`.
4. Install head packs or configure runtime baking if you want `head` rendering or want `auto` to switch opaque cells to heads.
5. Restart the server and run `/sculpt admin status` to confirm that Sculpt is ready.

On its first start, Paper downloads `sqlite-jdbc` to the server's `libraries/` cache. The server therefore needs access to Maven Central for that first download; later starts reuse the local cache.

### Textures and `.sbh` head packs

`head`, `textdisplay`, and `auto` share the vanilla model and texture cache, but they render cells differently:

- `head` reads pre-baked player-head textures.
- `textdisplay` renders cached vanilla texture pixels directly, including alpha transparency.
- `auto` chooses TextDisplay for transparent materials and missing opaque head textures, then replaces ready opaque cells with heads.

Install one administrator-provided head-pack file per resolution at:

```text
plugins/Sculpt/
├── config.yml
├── heads/
│   ├── heads-2.sbh
│   ├── heads-4.sbh
│   └── heads-16.sbh
├── cache/
│   └── heads.sqlite
├── lang/
├── blueprints/
└── non-bakeable-blocks.txt
```

Sculpt only reads `.sbh` files; it never creates, exports, or overwrites them. To obtain `.sbh` head-pack files, contact `twme` on Discord.

`cache/heads.sqlite` is managed by Sculpt and stores runtime-generated texture information. Do not edit or distribute it as a replacement for a head pack.

Alternatively, configure `runtimeBaking.mineskin.apiKey` in `plugins/Sculpt/config.yml`. The default API endpoint is `https://api.mineskin.org`; set `runtimeBaking.mineskin.apiUrl` only to a trusted HTTPS-compatible endpoint. A runtime-baking API key must be kept private.

### WorldEdit and FAWE integration

Sculpt automatically integrates with WorldEdit when it is installed. For FAWE, allow Sculpt's paste-tracking extent in `plugins/FastAsyncWorldEdit/config.yml`:

```yaml
extent:
  allowed-plugins:
    - com.example.ExistingPlugin
    - dev.twme.sculpt.integration.SculptPasteExtent
```

Keep existing entries. FAWE checks the full class name, not the plugin name; use `dev.twme.sculpt.integration.SculptPasteExtent` exactly.

### Permissions

Permissions default conservatively. Grant only the nodes appropriate for each group; do not grant `sculpt.command.*` or `sculpt.use.*` to ordinary players unless you intend to grant their full scope.

| Need | Permission nodes |
| --- | --- |
| Choose resolutions | `sculpt.command.resolution` and `sculpt.command.resolution.<1\|2\|4\|8\|16>` |
| Toggle preview | `sculpt.command.preview` |
| Persistent Sculpt mode | `sculpt.command.mode.on`, `sculpt.command.mode.off` |
| Barrier or shulker fill | `sculpt.command.fill.barrier`, `sculpt.command.fill.shulker` |
| No-collision fill | `sculpt.command.fill.null` |
| Choose display mode | `sculpt.command.display.head`, `sculpt.command.display.textdisplay`, `sculpt.command.display.auto` |
| Use the region selector | `sculpt.command.tool.selector`, `sculpt.use.selector` |
| Receive the blueprint selector | `sculpt.command.tool.blueprint` |
| Convert fill modes | `sculpt.command.convert` |
| Replace a selected region's material | `sculpt.command.replace` |
| Restore automatic TextDisplay lighting | `sculpt.command.relight` |
| Use blueprints | Grant the required `sculpt.command.blueprint.<operation>` nodes |
| Bypass region-protection build checks | `sculpt.bypass.region-protection` |
| Administrative commands | `sculpt.command.admin.*` |

`sculpt.bypass.region-protection` is intentionally separate from the command wildcards and should only be granted to trusted administrators.

### Configuration

The main configuration file is `plugins/Sculpt/config.yml`.

The current configuration schema is `configVersion: 5`, and bundled language files use `languageVersion: 2`. These values are migration markers and should not be edited manually.

| Setting | Default | Purpose |
| --- | --- | --- |
| `sculpt.defaultGridSize` | `2` | Default player resolution. |
| `sculpt.defaultFillMode` | `shulker` | Default collision strategy. |
| `sculpt.defaultDisplayMode` | `auto` | Default cell-rendering strategy. |
| `sculpt.maxActiveBlocks` | `-1` | Server-wide SculptBlock limit; `-1` is unlimited. |
| `sculpt.convertNormalBlocks` | `true` | Let Sculpt mode convert supported normal blocks into SculptBlocks. |
| `storage.autoSaveIntervalSeconds` | `300` | Interval for saving dirty SculptBlock data. |
| `rendering.textDisplay.maxEntitiesPerBlock` | `4096` | Safety limit for TextDisplay entities per SculptBlock. |
| `regionOperations.replace.maxVolume` | `32768` | Maximum world-block volume for `/sculpt replace`. |
| `regionOperations.replace.maxGeneratedLeaves` | `131072` | Safety budget for partial-shape replacement output. |
| `language.default` | `en_us` | Fallback language. |
| `language.autoDetect` | `true` | Use the player's client language when possible. |
| `blueprint.enabled` | `true` | Enable the blueprint system. |
| `blueprint.selection.maxVolume` | `4096` | Maximum cuboid blueprint-selection volume. |
| `blueprint.storage.maxPerPlayer` | `100` | Blueprint limit per player. |
| `blueprint.storage.maxFolderDepth` | `3` | Maximum blueprint folder depth. |
| `blueprint.web.apiEndpoint` | SculptWeb API | SculptWeb publishing and download endpoint. |
| `blueprint.download.allowedDomains` | `sculpt-web.twme.workers.dev` | Domains accepted for blueprint downloads. |

Run `/sculpt admin reload` after changing reloadable settings. Restart the server after changing head-pack files, MineSkin settings, or `rendering.textDisplay.maxEntitiesPerBlock`.

### Region protection and backups

Before Sculpt changes a world location, it performs the same build check used for block placement. This covers sculpting, extension, restoration, fill conversion, replacement, and blueprint paste operations. Region-protection plugins such as WorldGuard can therefore keep enforcing their normal rules.

Back up the complete `plugins/Sculpt/` directory and the relevant world data. SculptBlock data is stored in entity PDC data in the world; deleting the plugin directory does not remove Sculpt entities from existing worlds.

### Administrative commands

| Command | Description |
| --- | --- |
| `/sculpt admin status` | Show texture and runtime health. |
| `/sculpt admin reload` | Reload supported configuration, language, blueprint, and debug settings. |
| `/sculpt admin list [--page <n>]` | List active SculptBlocks in your current world. |
| `/sculpt admin teleport <world,x,y,z>` | Teleport to a listed SculptBlock location. |

### Build from source

Sculpt requires JDK 21 and Maven:

```bash
git clone https://github.com/TWME-TW/SculptPlugin.git
cd SculptPlugin
mvn -B verify
```

The built plugin is written to `target/Sculpt-*.jar`.

## License

Copyright 2026 TWME-TW

Sculpt is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution information.
