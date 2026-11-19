"""Export/verify canonical road units from the installed MineColonies source blueprints.

Slices are taken from the style pack, never hand-authored, so an upstream layout
change is caught by ``--verify`` instead of silently shifting every road.

Two series exist and they are cut in opposite axes:

* ``streets`` -- authored along x, width on z, four columns. A unit is one x column.
* ``main``    -- authored along z, width on x, five columns. A unit is one z row,
  transposed so every unit in the pack is authored along +x with the width on z.

GPL-3.0-or-later, YLFXM. No game or third-party Python packages required.
"""
import argparse
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'src/main/resources/assets/rp_crossroads/blueprints/units/original'
AIR = {'Name': 'minecraft:air'}
AIR_NAMES = {'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'}
CONTROLLER = 'minecolonies:decorationcontroller'
# Structurize's two placeholders. "solid" fills with the terrain's own solid
# block, plain keeps whatever the world already had.
SOLID_SUB = {'Name': 'structurize:blocksolidsubstitution', 'Properties': {}}
KEEP_SUB = {'Name': 'structurize:blocksubstitution', 'Properties': {}}
SURFACE_Y = 1
# Clockwise quarter turn: the main series is authored along z and is transposed
# to an along-x unit, so its width axis turns from +x into +z.
CW90 = {'north': 'east', 'east': 'south', 'south': 'west', 'west': 'north'}
STONE_ROAD = 'minecraft:stone_bricks'
STONE_STAIR = {
    'Name': 'minecraft:stone_brick_stairs',
    'Properties': {'facing': 'east', 'half': 'bottom', 'shape': 'straight', 'waterlogged': 'false'},
}

# series -> role -> spec. ``levels`` maps level to (source path relative to
# infrastructure/roads, source column/row). ``derive`` reads an already-built
# unit of the same series instead of the style pack.
SPEC = {
    'streets': {
        'road': {
            'width': 4, 'walkRange': [1, 2], 'anchorColumn': 0, 'slice': 'column',
            'levels': {1: ('streets/longroad1', 2), 2: ('streets/longroad2', 2),
                       3: ('streets/longroad3', 2)},
        },
        'stair': {
            'width': 4, 'walkRange': [1, 2], 'anchorColumn': 0, 'slice': 'column',
            'canonicalFacing': 'east',
            'levels': {1: ('streets/smallstairs1', 1), 2: ('streets/smallstairs2', 1),
                       3: ('streets/smallstairs3', 1)},
        },
        'lamp': {
            'width': 2, 'walkRange': None, 'anchorColumn': 0, 'slice': 'street_lamp',
            'levels': {1: ('streets/longroad1', 3), 2: ('streets/longroad2', 3),
                       3: ('streets/longroad3', 3)},
        },
    },
    'main': {
        'road': {
            'width': 5, 'walkRange': [1, 3], 'anchorColumn': 0, 'slice': 'row_transposed',
            # nice is taken from z=1: its z=0 row carries the glowstone the
            # style pack buries under the leaf curb, which is a light source for
            # the authored stretch and has no meaning in a one-row unit.
            'levels': {1: ('poor/poorpathodd', 0), 2: ('simple/simplepathodd', 0),
                       3: ('nice/nicepathodd', 1), 4: ('fancy/fancypathodd', 0)},
        },
        'stair': {
            'width': 5, 'walkRange': [1, 3], 'anchorColumn': 0, 'slice': 'derive_stair',
            'canonicalFacing': 'east',
            'levels': {1: ('@road', 1), 2: ('@road', 2), 3: ('@road', 3), 4: ('@road', 4)},
        },
        'lamp': {
            'width': 2, 'walkRange': None, 'anchorColumn': 0, 'slice': 'main_lamp', 'height': 7,
            # Same anchor rule as the street lamps: row 1 is the road surface,
            # row 0 stays empty, so the base sits on the curb row and the pole
            # starts one row above it.
            'surfaceY': 1,
            'levels': {1: ('lampposts/poor', 0), 2: ('lampposts/simple', 0),
                       3: ('lampposts/nice', 0), 4: ('lampposts/fancy', 0)},
        },
    },
}


def read_nbt(raw):
    stream = io.BytesIO(gzip.decompress(raw))

    def number(fmt): return struct.unpack('>' + fmt, stream.read(struct.calcsize('>' + fmt)))[0]

    def string(): return stream.read(number('H')).decode('utf-8')

    def read(kind):
        if kind in range(1, 7): return number({1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}[kind])
        if kind == 7: return list(stream.read(number('i')))
        if kind == 8: return string()
        if kind == 9:
            child, size = number('B'), number('i')
            return [read(child) for _ in range(size)]
        if kind == 10:
            result = {}
            while True:
                child = number('B')
                if not child: return result
                key = string(); result[key] = read(child)
        if kind in (11, 12): return [number('i' if kind == 11 else 'q') for _ in range(number('i'))]
        raise ValueError(kind)
    assert number('B') == 10
    string()
    return read(10)


def nbt_payload(data):
    """Uncompressed NBT bytes.

    Hashing these instead of the gzip container keeps the manifest hashes
    stable across Python/zlib builds: two interpreters compress the same tree
    into different bytes, so a container hash would make `--verify` depend on
    whichever interpreter produced the assets.
    """
    stream = io.BytesIO()

    def number(fmt, value): stream.write(struct.pack('>' + fmt, value))

    def string(value):
        encoded = value.encode('utf-8'); number('H', len(encoded)); stream.write(encoded)

    def inferred(value):
        if isinstance(value, str): return 8
        if isinstance(value, dict): return 10
        if isinstance(value, list): return 9
        if isinstance(value, int): return 3
        raise ValueError(type(value))

    def write(kind, value):
        if kind in range(1, 7): number({1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}[kind], value)
        elif kind == 8: string(value)
        elif kind == 9:
            child = inferred(value[0]) if value else 10
            number('B', child); number('i', len(value))
            for item in value: write(child, item)
        elif kind == 10:
            for key, item in value.items():
                child = (2 if key in ('size_x', 'size_y', 'size_z') else
                         1 if key == 'version' else 11 if key == 'blocks' else inferred(item))
                number('B', child); string(key); write(child, item)
            number('B', 0)
        elif kind == 11:
            number('i', len(value))
            for item in value: number('i', item)
        else: raise ValueError(kind)
    number('B', 10); string(''); write(10, data)
    return stream.getvalue()


def encode_nbt(data):
    return gzip.compress(nbt_payload(data), mtime=0)


def state_at(data, x, y, z):
    index = (y * data['size_z'] + z) * data['size_x'] + x
    value = data['blocks'][index // 2]
    palette = ((value >> 16) if index % 2 == 0 else value) & 65535
    return data['palette'][palette]


def is_air(state):
    return state['Name'] in AIR_NAMES


def transposed(state):
    """Turns an along-z authored state into the along-x spelling of the same block."""
    properties = state.get('Properties')
    if not properties:
        return state
    mapped = dict(properties)
    if mapped.get('facing') in CW90:
        mapped['facing'] = CW90[mapped['facing']]
    if mapped.get('axis') in ('x', 'z'):
        mapped['axis'] = 'z' if mapped['axis'] == 'x' else 'x'
    # Fence and wall connections are stored per compass direction, so a
    # quarter turn has to rotate the four booleans too. They are rebuilt at
    # build time anyway, but the preview shows the file verbatim and a fence
    # pointing the wrong way is exactly what "the pole looks broken" means.
    if all(side in mapped for side in CW90):
        mapped.update({new: mapped[old] for old, new in CW90.items()})
    result = copy.deepcopy(state)
    result['Properties'] = mapped
    return result


def mirrored(state):
    """Reflects a state across the width axis (z) of an authored unit."""
    properties = state.get('Properties')
    if not properties:
        return state
    mapped = dict(properties)
    if 'north' in mapped and 'south' in mapped:
        mapped['north'], mapped['south'] = mapped['south'], mapped['north']
    if mapped.get('facing') in ('north', 'south'):
        mapped['facing'] = 'south' if mapped['facing'] == 'north' else 'north'
    result = copy.deepcopy(state)
    result['Properties'] = mapped
    return result


def column_entries(data, spec, column):
    """One authored column: streets road and stair."""
    width = spec['width']
    entries, skip, tiles = {}, [], {}
    source_tiles = {(t['x'], t['y'], t['z']): t for t in data.get('tile_entities', [])}
    for y in range(0, SURFACE_Y + 1):
        for z in range(width):
            state = copy.deepcopy(state_at(data, column, y, z))
            if is_air(state): continue
            pos = (0, y, z)
            if state['Name'] == CONTROLLER:
                entries[pos] = AIR.copy()
                continue
            if spec.get('canonicalFacing') and 'facing' in state.get('Properties', {}):
                state['Properties']['facing'] = spec['canonicalFacing']
            entries[pos] = state
            tile = source_tiles.get((column, y, z))
            if tile:
                tile = copy.deepcopy(tile); tile.update(x=0, y=y, z=z); tiles[pos] = tile
    return entries, skip, tiles


def street_lamp_entries(data, spec, column):
    """Street lamp: pole on z0, the overhang and its torch on z1."""
    entries, skip, tiles = {}, [], {}
    source_tiles = {(t['x'], t['y'], t['z']): t for t in data.get('tile_entities', [])}
    for y in range(1, data['size_y']):
        for z in range(spec['width']):
            if y == 1 and z > 0:
                # The overhang cell only claims the pavement below it.
                skip.append([0, y, z]); continue
            state = copy.deepcopy(state_at(data, column, y, z))
            if is_air(state): continue
            pos = (0, y, z)
            if state['Name'] == CONTROLLER:
                entries[pos] = AIR.copy()
                continue
            entries[pos] = state
            tile = source_tiles.get((column, y, z))
            if tile:
                tile = copy.deepcopy(tile); tile.update(x=0, y=y, z=z); tiles[pos] = tile
    return entries, skip, tiles


def row_transposed_entries(data, spec, row):
    """Main road: one z row, transposed so the width lands on z.

    Only ``y <= surfaceY`` is taken. The source rows carry stray
    ``blockSubstitution`` markers several blocks up (y4) which are layout hints
    for the style pack, not road geometry; copying them would put floating
    placeholders in the unit.
    """
    width = data['size_x']
    entries, skip, tiles = {}, [], {}
    source_tiles = {(t['x'], t['y'], t['z']): t for t in data.get('tile_entities', [])}
    for w in range(width):
        for y in range(0, SURFACE_Y + 1):
            state = copy.deepcopy(state_at(data, w, y, row))
            if is_air(state): continue
            if state['Name'] == CONTROLLER:
                entries[(0, y, w)] = AIR.copy()
                continue
            state = transposed(state)
            entries[(0, y, w)] = state
            tile = source_tiles.get((w, y, row))
            if tile:
                tile = copy.deepcopy(tile); tile.update(x=0, y=y, z=w); tiles[(0, y, w)] = tile
    if width == 4:
        # poor: three gravel columns plus one substitution curb column. The
        # authored pack only curbs one side, so the near side is mirrored from
        # the far curb to get the five-column unit the generator expects.
        mirrored = {(0, y, 0): copy.deepcopy(s) for (_, y, z), s in entries.items() if z == 3}
        entries = {(0, y, z + 1): s for (_, y, z), s in entries.items()}
        entries.update(mirrored)
    return entries, skip, tiles


def main_lamp_entries(data, spec, level, curb_state=None):
    """Main lamp posts: three columns, the pole in the middle.

    ``lampposts/simple`` is a half gate: one pole and a single hanging lantern;
    the unit mirrors the overhang onto the other side so every level reads as a
    symmetric span with the pole on the anchor column.

    Levels 3 and 4 build their lights and their crossbar out of
    ``domum_ornamentum:materially_retexturable``, whose appearance lives in the
    block entity (``textureData``), not in the block state. Copying only the
    state leaves the cell looking empty, which is exactly why the higher tiers
    appeared to be missing their post blocks.
    """
    entries, skip, tiles = {}, [], {}
    source_tiles = {(t['x'], t['y'], t['z']): t for t in data.get('tile_entities', [])}

    # A pole has to stand on a full block. Level 2's curb is an oak stair, level
    # 1 has no uniform curb at all, and level 3's curb is leaves — none of them
    # reads as a foundation. Levels 3 and 4 reuse the material the pair is paved
    # with (stone bricks / chiselled bricks); level 4 takes it from the road unit
    # so a restyle carries over. The base sits on row 0, the same row as the
    # pavement's curb, so the pole keeps its height.
    if level == 1:
        base = copy.deepcopy(SOLID_SUB)
    elif level == 2:
        base = {'Name': 'minecraft:oak_planks', 'Properties': {}}
    elif level == 3:
        base = {'Name': 'minecraft:stone_bricks', 'Properties': {}}
    else:
        base = copy.deepcopy(curb_state) if curb_state else copy.deepcopy(SOLID_SUB)
    # Row 0 stays empty and the base takes row 1, exactly how the street lamps
    # are authored (surfaceY=1, one empty row under the base). The surfaceY in
    # the spec moves with it, so the base still lands on the curb row in world
    # space; only the unit gains one empty row at the bottom.
    entries[(0, 1, 0)] = base

    def place(column, y, dest_z, mirror):
        final_y = y + 1
        state = copy.deepcopy(state_at(data, column, y, 0))
        if is_air(state):
            return
        if state['Name'] == CONTROLLER:
            # Air replaces the marker; its block entity must not survive it.
            entries[(0, final_y, dest_z)] = AIR.copy()
            return
        state = transposed(state)
        if mirror:
            state = mirrored(state)
        entries[(0, final_y, dest_z)] = state
        tile = source_tiles.get((column, y, 0))
        if tile:
            tile = copy.deepcopy(tile)
            tile.update(x=0, y=final_y, z=dest_z)
            tiles[(0, final_y, dest_z)] = tile

    # One-sided overhang for every level. A three-column gate needs one column
    # outside the curb, and a road blueprint cannot cover it without painting
    # over the shoulder, so the far arm is dropped instead. The pole keeps the
    # column it is authored on (level 2 has it outermost, the rest have it in the
    # middle); the near arm becomes the overhang.
    pole = 0 if level == 2 else 1
    arm = 1 - pole
    for y in range(1, data['size_y']):
        place(pole, y, 0, False)
        place(arm, y, 1, arm < pole)
    return entries, skip, tiles


def derive_stair(road_entries, spec):
    """Main stair: the same tile with its walkable columns turned into stairs."""
    walk = set(range(spec['walkRange'][0], spec['walkRange'][1] + 1))
    entries = {}
    for (_, y, z), state in road_entries.items():
        if y == SURFACE_Y and z in walk and state['Name'] == STONE_ROAD:
            entries[(0, y, z)] = copy.deepcopy(STONE_STAIR)
            continue
        entries[(0, y, z)] = copy.deepcopy(state)
    return entries, [], {}


def rebuild_connections(entries):
    """Recomputes fence and wall connections from the unit's own neighbours.

    The authored pack stores them for its own layout, and a slice keeps only the
    columns it took, so the stored values describe neighbours that are not in the
    unit. A unit is one block long, so only the width neighbours can connect; the
    build does the same pass over the finished road.
    """
    sides = {'north', 'south', 'east', 'west'}
    for (x, y, z), state in list(entries.items()):
        properties = state.get('Properties', {})
        if not sides <= set(properties):
            continue
        if any(properties[side] not in ('true', 'false') for side in sides):
            continue
        updated = dict(properties)
        for side, dz in (('north', -1), ('south', 1)):
            neighbour = entries.get((x, y, z + dz))
            link = neighbour is not None and sides <= set(neighbour.get('Properties', {}))
            updated[side] = 'true' if link else 'false'
        updated['east'] = 'false'
        updated['west'] = 'false'
        state = copy.deepcopy(state)
        state['Properties'] = updated
        entries[(x, y, z)] = state
    return entries


def serialize(entries, size_y, width, source):
    palette, indices = [AIR.copy()], []
    for y in range(size_y):
        for z in range(width):
            state = entries.get((0, y, z), AIR)
            if state not in palette: palette.append(state)
            indices.append(palette.index(state))
    if len(indices) % 2: indices.append(0)
    blocks = []
    for i in range(0, len(indices), 2):
        value = (indices[i] << 16) | indices[i + 1]
        blocks.append(value if value < 2 ** 31 else value - 2 ** 32)
    result = {'version': 1, 'size_x': 1, 'size_y': size_y, 'size_z': width,
              'palette': palette, 'blocks': blocks, 'tile_entities': [],
              'entities': [], 'required_mods': source.get('required_mods', [])}
    if 'mcversion' in source:
        result['mcversion'] = source['mcversion']
    return result


def build(archive):
    files, manifest_units, sources = {}, {}, {}
    sources_by_path, units_by_key = {}, {}
    for series, roles in SPEC.items():
        manifest_units[series] = {'units': {}}
        for role, spec in roles.items():
            files_in_role = {}
            for level, (path, index) in spec['levels'].items():
                source_data = {}
                if path.startswith('@'):
                    # Derived from a unit of the same series (main stair), so the
                    # two can never drift apart.
                    entries, skip, tiles, total_h = units_by_key[f'{series}:{path[1:]}:{index}']
                    entries, skip, tiles = derive_stair(entries, spec)
                else:
                    key = 'blueprints/minecolonies/original/infrastructure/roads/' + path + '.blueprint'
                    if key not in sources_by_path:
                        raw = archive.read(key)
                        sources[key] = hashlib.sha256(raw).hexdigest()
                        sources_by_path[key] = read_nbt(raw)
                    source_data = sources_by_path[key]
                    if spec['slice'] == 'column':
                        entries, skip, tiles = column_entries(source_data, spec, index)
                        total_h = source_data['size_y']
                    elif spec['slice'] == 'street_lamp':
                        entries, skip, tiles = street_lamp_entries(source_data, spec, index)
                        total_h = source_data['size_y']
                    elif spec['slice'] == 'row_transposed':
                        entries, skip, tiles = row_transposed_entries(source_data, spec, index)
                        total_h = source_data['size_y']
                    elif spec['slice'] == 'main_lamp':
                        # Levels 3/4 take their base material from the same tier's
                        # road curb, so a restyle of the series carries over instead
                        # of leaving the lamp on a hard-coded block.
                        road_unit = units_by_key.get(f'{series}:road:{level}')
                        curb = road_unit[0].get((0, 1, 0)) if road_unit else None
                        entries, skip, tiles = main_lamp_entries(source_data, spec, level, curb)
                        # Every level of the series is as tall as the tallest, so
                        # the previews line up instead of each following its own
                        # source blueprint.
                        total_h = spec.get('height', source_data['size_y'])
                    else:
                        raise ValueError(spec['slice'])
                    units_by_key[f'{series}:{role}:{level}'] = (entries, skip, tiles, total_h)
                rebuild_connections(entries)
                name = f'{role}_{level}.blueprint'
                blueprint = serialize(entries, total_h, spec['width'], source_data)
                blueprint['tile_entities'] = list(tiles.values())
                files[f'{series}/{name}'] = blueprint
                metadata = {
                    'file': f'{series}/{name}', 'axis': 'x',
                    'surfaceY': spec.get('surfaceY', SURFACE_Y),
                    # Byte hash of the encoded file. The pack copy is compared
                    # against it on every open so an older or hand-edited unit
                    # cannot survive next to a newer one in the same folder.
                    'sha256': hashlib.sha256(nbt_payload(blueprint)).hexdigest(),
                    'width': spec['width'], 'anchorColumn': spec['anchorColumn'],
                    'walkRange': spec['walkRange'],
                    'writeCells': [list(pos) for pos in sorted(entries)],
                    'skipCells': sorted([list(pos) for pos in skip]),
                }
                if spec.get('canonicalFacing'):
                    metadata['canonicalFacing'] = spec['canonicalFacing']
                if series == 'main' and role == 'road':
                    metadata['curb'] = [0, spec['width'] - 1]
                files_in_role[str(level)] = metadata
            manifest_units[series]['units'][role] = files_in_role
            if role == 'road':
                manifest_units[series]['width'] = spec['width']
                manifest_units[series]['walkRange'] = spec['walkRange']
    manifest = {'schemaVersion': 2, 'series': manifest_units, 'sources': sources}
    return files, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-jar', type=Path)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    jar = args.source_jar
    if jar is None:
        jar = next(Path(p) for p in (ROOT / 'cp.txt').read_text(encoding='utf-8-sig').strip().split(';')
                   if Path(p).name.lower() == 'minecolonies.jar')
    with zipfile.ZipFile(jar) as archive:
        files, manifest = build(archive)
    if args.verify:
        count = 0
        for name, blueprint in files.items():
            on_disk = read_nbt((DEST / name).read_bytes())
            assert on_disk == blueprint, f'{name}: serialized unit changed'
            assert read_nbt(encode_nbt(blueprint)) == blueprint, f'{name}: NBT round trip'
            count += sum(1 for state in on_disk['palette'] if state['Name'] not in AIR_NAMES)
        assert json.loads((DEST / 'units.json').read_text(encoding='utf-8')) == manifest
        print(f'PASS {len(files)} unit blueprints, {count} non-air palette entries, manifest and NBT round trips')
    else:
        for series in SPEC:
            (DEST / series).mkdir(parents=True, exist_ok=True)
        for name, blueprint in files.items():
            target = DEST / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(encode_nbt(blueprint))
        (DEST / 'units.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        # The pre-v2 flat spellings are gone; leaving them would double every
        # preview entry.
        for stale in DEST.glob('street_*.blueprint'):
            stale.unlink()
        print(f'Exported {len(files)} road units for {len(SPEC)} series and {len(manifest["sources"])} sources')


if __name__ == '__main__':
    main()
