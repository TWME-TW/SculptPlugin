# Sculpt

> [!WARNING]
> Most of this plugin was generated with AI. Review it, test it, and back up your data before using it on a production server.

Sculpt is a Paper plugin that lets players turn blocks into editable voxel sculptures. A block can be divided into `1×1×1`, `2×2×2`, `4×4×4`, `8×8×8`, or `16×16×16` cells; players can remove, restore, extend, and mix those cells, then save their work as blueprints.

This README is split into a [player guide](#for-players) and an [administrator guide](#for-administrators). The commands available to a player always depend on the permissions granted by the server. Run `/sculpt help` in game to see the commands you may use.

## For Players

### Quick start

1. Run `/sculpt` to open the Sculpt menu, then choose **Enter editor**. You can also run `/sculpt edit`, or take the **Sculpt Knife** from the menu and right-click it.
2. Your hotbar turns into a tool palette. Your real items are safe; they come back when you leave.
3. Scroll or press `1`–`9` to choose a tool. Look at a block: a preview shows exactly what the tool will change.
4. Left-click for the tool's main action and right-click for its second action.
5. Press `Shift` + `Q` to undo, and `Shift` + `F` to leave the editor.

Previews are only sent to you and change nothing. The world only changes when you confirm with a click, and every change can be undone.

### Controls

The same keys work for every tool:

| Input | Result |
| --- | --- |
| Scroll or `1`–`9` | Choose a tool. |
| Left-click | The tool's main action. |
| Right-click | The tool's second action or confirmation. |
| `Shift` + scroll | Adjust the tool: brush radius, shape thickness, or rotation. |
| `Shift` + right-click | Open the tool's settings. |
| `F` | Cycle through your permitted resolutions. |
| `Shift` + `F` | Leave the editor. |
| `Q` | Cancel what the tool is doing, such as a selection or unfinished shape. |
| `Shift` + `Q` | Undo your last edit. |
| Middle click | Use the material under the cursor. |

The action bar shows the current tool, resolution, and material. A boss bar shows the progress of large edits.

You can target cells behind holes in a sculpture. At `1×1×1`, whole blocks are edited instead of cells.

### Tools

| Slot | Tool | Left-click | Right-click |
| --- | --- | --- | --- |
| 1 | Sculpt | Remove the targeted cell. | Place a cell in front of the targeted face. |
| 2 | Brush | Carve a sphere or cube of cells. | Add material in a sphere or cube. |
| 3 | Smooth | Round off spikes and corners and fill pits. | Same as left-click. |
| 4 | Paint | Pick the targeted material. | Repaint cells without changing the shape. |
| 5 | Select | Set the two corners of a box of cells. | Open the selection actions. |
| 6 | Transform | Grab the selection, then left-click again to drop it. | Move or copy the selection to the preview position. |
| 7 | Shape | Add a control point, or grab and drag an existing one. | Build the previewed shape. |
| 8 | Blueprint | Choose a blueprint. | Preview the paste; right-click the same spot again to paste. |
| 9 | Settings | Open the material palette. | Open the editor settings. |

**Materials.** The editor keeps a palette of nine materials. Middle-click a block or cell to use its material, choose one in the palette (Settings tool, left-click), or pick one with the Paint tool. The material is shown in the action bar.

**Selection actions.** With a selection, right-click with the Select tool to delete, fill, or repaint it, replace its material while keeping the visible shape, restore automatic lighting, change its fill mode, or save it as a blueprint.

**Transform.** Grab the selection and move the cursor; a ghost box follows it. Use `Shift` + scroll to rotate in 90° steps. `Shift` + right-click sets mirroring and chooses between moving and copying. `Q` resets the transform.

**Shapes.** Choose a shape type in the Shape tool settings (`Shift` + right-click). You can also set the thickness, the number of rows in a surface grid, hollow spheres and cylinders, and carving instead of adding. The preview updates live while you add or drag points.

| Shape | Points | Result |
| --- | --- | --- |
| Plane | 3 or more | A multi-angle plane joined as a fan from the first point. Three points form a triangle at any angle; four points form a quad, which may be folded. |
| Surface | a grid of 2×2 to 4×4 | A curved Bézier surface. The surface touches the corner points and is pulled towards the others. |
| Curve | 2 or more | A beam or smooth curve passing through every point. |
| Sphere | 2 | A sphere around the first point, passing through the second. |
| Cylinder | 3 | A cylinder whose axis runs from the first to the second point, with the third point on its side. |

Points snap to cell centers in front of the targeted face. A shape uses at most 16 points. Building replaces air, grass, fluids, and other replaceable blocks, joins existing SculptBlocks, and can carve or extend plain full blocks. Partial blocks such as stairs and blocks with contents such as chests are left untouched.

### Settings

Open the editor settings with the Settings tool (right-click) or from the `/sculpt` menu:

| Setting | Purpose |
| --- | --- |
| Resolution | Cell size: `1`, `2`, `4`, `8`, or `16` cells per block edge. `F` cycles it too. |
| Fill mode | Physical collision inside a SculptBlock: `barrier` (one full barrier), `shulker` (collision follows the shape), or `null` (no collision). |
| Display mode | How cells are rendered: `head` (player-head textures, fewer entities, no transparency), `textdisplay` (texture pixels, including transparency), or `auto` (TextDisplay for transparent materials and heads that are not ready yet). |
| Animations | Turn preview animations on or off. |

Only the choices you have permission to use are listed. Player-head cells are always rendered as a complete texture unit and cannot be split into smaller cells.

Your settings remain associated with your UUID when you leave and rejoin during the same server process. A server restart returns them to the defaults in `config.yml`.

### Undo and redo

`Shift` + `Q` in the editor, `/sculpt undo [steps]`, and `/sculpt redo [steps]` revert and reapply your edits from every tool, blueprint pastes included. A block that someone else changed after your edit is skipped instead of being overwritten, and region protection is checked again. History is kept per player until they leave the server. Replacing a selection's material is not recorded in the history.

### Player commands

| Command | Description |
| --- | --- |
| `/sculpt` | Open the Sculpt menu. |
| `/sculpt help` | Show the commands available to you. |
| `/sculpt edit [on\|off]` | Enter or leave the editor. |
| `/sculpt undo [steps]` / `/sculpt redo [steps]` | Undo or redo your recent edits. |
| `/sculpt blueprint ...` | Manage, share, and download blueprints. |

The old commands `mode`, `resolution`, `preview`, `fill`, `display`, `convert`, `replace`, `relight`, `build`, `brush`, `tool`, and `heads` were replaced by the editor. Running one of them explains where the feature moved.

### Blueprints

Blueprints can store one SculptBlock or a cuboid selection containing SculptBlocks, regular non-air blocks, their `BlockData`, relative positions, and empty space. Container contents and block-entity data are not stored.

1. In the editor, use the Select tool to select a box, right-click, and choose **Save as blueprint**.
2. Use the Blueprint tool to choose a blueprint, preview it in the world, and paste it with two right-clicks on the same spot.

Common blueprint commands:

| Command | Description |
| --- | --- |
| `/sculpt blueprint list [--public] [--page <n>]` | List your blueprints. |
| `/sculpt blueprint save <name> [--public]` | Save the current selection. |
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

Blueprint paste options can control air, overwriting, adhesion, rotation, and mirroring. Pastes from blueprint items can be undone as well.

## For Administrators

### Requirements

- Paper 1.21.11 or a compatible fork
- Java 21 or newer
- [PacketEvents](https://github.com/retrooper/packetevents) 2.14.0 or newer, for the editor
- Optional: WorldEdit or FastAsyncWorldEdit (FAWE)
- Optional for `head` rendering: matching pre-baked head packs or a MineSkin API key

Sculpt supports Folia. PacketEvents, WorldEdit, and FAWE are soft dependencies. Without PacketEvents, Sculpt logs a warning and the editor is unavailable; undo, redo, blueprint, and administrative commands, and existing SculptBlocks, keep working.

### Installation

1. Download the latest `Sculpt-*.jar` from the [Releases page](https://github.com/TWME-TW/SculptPlugin/releases).
2. Put the JAR and PacketEvents in the server's `plugins/` directory.
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

Permissions default conservatively. Grant only the nodes appropriate for each group; do not grant `sculpt.command.*` or `sculpt.editor.*` to ordinary players unless you intend to grant their full scope.

| Need | Permission nodes |
| --- | --- |
| Enter the editor (default: everyone) | `sculpt.command.edit` |
| Undo and redo your own edits (default: everyone) | `sculpt.command.undo` |
| Editor tools | `sculpt.editor.<sculpt\|brush\|smooth\|paint\|select\|transform\|shape\|blueprint>`; Sculpt and Paint default to everyone, the others to operators. The Settings tool needs no permission. |
| Resolutions | `sculpt.command.resolution.<1\|2\|4\|8\|16>` |
| Fill modes | `sculpt.command.fill.<barrier\|shulker\|null>` |
| Display modes | `sculpt.command.display.<head\|textdisplay\|auto>` |
| Selection actions: change fill, replace material, restore lighting | `sculpt.command.convert`, `sculpt.command.replace`, `sculpt.command.relight` |
| Blueprints | Grant the required `sculpt.command.blueprint.<operation>` nodes |
| Browse head textures from the menu | `sculpt.command.heads` |
| Bypass region-protection build checks | `sculpt.bypass.region-protection` |
| Administrative commands | `sculpt.command.admin.*` |

`sculpt.bypass.region-protection` is intentionally separate from the command wildcards and should only be granted to trusted administrators.

### Configuration

The main configuration file is `plugins/Sculpt/config.yml`.

The current configuration schema is `configVersion: 6`, and bundled language files use `languageVersion: 5`. These values are migration markers and should not be edited manually.

| Setting | Default | Purpose |
| --- | --- | --- |
| `sculpt.defaultGridSize` | `2` | Default player resolution. |
| `sculpt.defaultFillMode` | `shulker` | Default collision strategy. |
| `sculpt.defaultDisplayMode` | `auto` | Default cell-rendering strategy. |
| `sculpt.maxActiveBlocks` | `-1` | Server-wide SculptBlock limit; `-1` is unlimited. |
| `sculpt.convertNormalBlocks` | `true` | Let editor tools convert supported normal blocks into SculptBlocks. |
| `storage.autoSaveIntervalSeconds` | `300` | Interval for saving dirty SculptBlock data. |
| `rendering.textDisplay.maxEntitiesPerBlock` | `4096` | Safety limit for TextDisplay entities per SculptBlock. |
| `regionOperations.replace.maxVolume` | `32768` | Maximum world-block volume for `/sculpt replace`. |
| `regionOperations.replace.maxGeneratedLeaves` | `131072` | Safety budget for partial-shape replacement output. |
| `editor.reach` | `6.0` | How far, in blocks, editor tools reach. |
| `editor.previewBudget` | `1024` | Maximum preview entities per player; larger previews are simplified. |
| `editor.animations` | `true` | Default for preview animations. |
| `editor.hudIntervalTicks` | `5` | How often the action bar is refreshed. |
| `editor.progressBarThreshold` | `64` | Edits touching at least this many blocks show a progress bar. |
| `building.maxBlocks` | `4096` | Maximum world blocks one edit may touch. |
| `building.maxCells` | `262144` | Maximum cells one edit may generate. |
| `building.maxThickness` | `16` | Largest shape thickness. |
| `building.maxTransformVoxels` | `2097152` | Largest selection, in 1/16-block voxels, that can be moved or copied. |
| `building.brush.maxRadius` | `8` | Largest brush radius in cells. |
| `building.history.maxEntries` | `30` | Undo steps kept per player. |
| `building.history.maxBlocks` | `32768` | Block snapshots kept per player; the oldest steps are dropped first. |
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

Before Sculpt changes a world location, it performs the same build check used for block placement. This covers every editor tool, selection actions, undo, redo, and blueprint paste operations. Region-protection plugins such as WorldGuard can therefore keep enforcing their normal rules.

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
