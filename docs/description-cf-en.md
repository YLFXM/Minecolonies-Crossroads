# MineColonies: Crossroads

**Summary (≤256 chars, for Modrinth / CurseForge one-liner):**

> A MineColonies addon for real roads: mark two points, get a native Structurize blueprint, and build it with the vanilla build tool or your own colony builder. Editable, repairable, upgradeable, with junctions and terrain dig/fill.

**Short description:**

MineColonies builds the houses. This addon builds the roads between them.

Roads in MineColonies are something you pave by hand. Crossroads adds them as a first-class feature — and nothing is replaced: the build tool is Structurize's, the work orders are MineColonies', the permissions are MineColonies', and the controller is the vanilla decoration controller.

## Features

- **Plan and build**: left-click A, shift-left-click B with the Road Wand, and the mod generates a Structurize blueprint at the exact road width. Build it with Complete / Pretty (native instant queue) or Survival, where it becomes a real work order for your own builder.
- **Two series, tiers and upgrades**: streets (4 wide, tiers 1–3) and main roads (5 wide, tiers 1–4), 21 units in total. The controller panel switches style, series and tier; a built road is re-laid by a builder through a work order.
- **Junctions and crossings**: same-level junctions merge into one flat platform; overpasses and underpasses keep at least a 3-block height difference and 2 blocks of clearance. If you lack permission on the neighbouring road you can request it; without approval the mod avoids the other road strictly.
- **Terrain dig and fill**: switch the wand to a dig or fill selection, frame an area, and the mod generates a blueprint and opens the native build tool — your builder clears or fills it through the vanilla phases.
- **Management**: a Road Surveyor lists and multi-selects a colony's roads (release or remove); a Work Order Scroll lists and cancels work orders. Permissions reuse MineColonies' `MANAGE_HUTS` without loosening them.

## Items

| Item | Purpose |
|---|---|
| Road Wand | Select endpoints, edit rows, preview ghosts, open the build tool, switch to terrain selection |
| Road Surveyor | List and multi-select the colony's roads |
| Work Order Scroll | List and cancel colony work orders |

All three are in the mod's own creative tab; recipes are visible in the in-game recipe book.

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.259 |
| MineColonies | 1.1.1403-1.21.1 |
| Structurize | 1.0.835 |
| BlockUI | 1.0.212 |
| Domum Ornamentum | 1.0.236 |

**This is an addon and cannot run on its own.** The client, the server and every multiplayer player must use the same jar (network protocol 14).

## Notes

- Beta: joining an existing save generally works, but **back up important worlds** first.
- Road data and unit assets live inside the save; do not edit the `rp_crossroads` folder by hand.
- Licensed under GPL-3.0. Source and issue tracker: https://github.com/YLFXM/Minecolonies-Crossroads
