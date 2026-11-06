"""Export/verify canonical road units from the installed MineColonies source blueprints.

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

def read_nbt(raw):
    stream = io.BytesIO(gzip.decompress(raw))
    def number(fmt): return struct.unpack('>'+fmt, stream.read(struct.calcsize('>'+fmt)))[0]
    def string(): return stream.read(number('H')).decode('utf-8')
    def read(kind):
        if kind in range(1, 7): return number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
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

def encode_nbt(data):
    stream = io.BytesIO()
    def number(fmt, value): stream.write(struct.pack('>'+fmt, value))
    def string(value):
        encoded = value.encode('utf-8'); number('H', len(encoded)); stream.write(encoded)
    def inferred(value):
        if isinstance(value, str): return 8
        if isinstance(value, dict): return 10
        if isinstance(value, list): return 9
        if isinstance(value, int): return 3
        raise ValueError(type(value))
    def write(kind, value):
        if kind in range(1, 7): number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind], value)
        elif kind == 8: string(value)
        elif kind == 9:
            child = inferred(value[0]) if value else 10
            number('B', child); number('i', len(value))
            for item in value: write(child, item)
        elif kind == 10:
            for key, item in value.items():
                child = (2 if key in ('size_x','size_y','size_z') else
                         1 if key == 'version' else 11 if key == 'blocks' else inferred(item))
                number('B', child); string(key); write(child, item)
            number('B', 0)
        elif kind == 11:
            number('i', len(value))
            for item in value: number('i', item)
        else: raise ValueError(kind)
    number('B', 10); string(''); write(10, data)
    return gzip.compress(stream.getvalue(), mtime=0)

def state_at(data, x, y, z):
    index = (y*data['size_z']+z)*data['size_x']+x
    value = data['blocks'][index//2]
    palette = ((value >> 16) if index % 2 == 0 else value) & 65535
    return data['palette'][palette]

def extract(data, kind):
    # These are the existing RoadUnitExtractor rules, including explicit controller air.
    width, column = (2, 3) if kind == 'lamp' else (4, 1 if kind == 'stair' else 0)
    entries, skip, tiles = {}, [], {}
    source_tiles = {(t['x'],t['y'],t['z']):t for t in data['tile_entities']}
    for y in range(data['size_y']):
        for z in range(width):
            pos = (0,y,z)
            if kind == 'lamp' and y < 1: continue
            if kind == 'lamp' and y == 1 and z > 0:
                skip.append(list(pos)); continue
            state = copy.deepcopy(state_at(data,column,y,z))
            if state['Name'] in ('minecraft:air','minecraft:cave_air','minecraft:void_air'): continue
            if state['Name'] == 'minecolonies:decorationcontroller':
                entries[pos] = AIR.copy(); continue
            if kind == 'stair' and 'facing' in state.get('Properties', {}):
                state['Properties']['facing'] = 'east'
            entries[pos] = state
            tile = source_tiles.get((column,y,z))
            if tile:
                tile = copy.deepcopy(tile); tile.update(x=0,y=y,z=z); tiles[pos] = tile
    palette, indices = [AIR.copy()], []
    for y in range(data['size_y']):
        for z in range(width):
            state = entries.get((0,y,z), AIR)
            if state not in palette: palette.append(state)
            indices.append(palette.index(state))
    if len(indices)%2: indices.append(0)
    blocks=[]
    for i in range(0,len(indices),2):
        value=(indices[i]<<16)|indices[i+1]
        blocks.append(value if value<2**31 else value-2**32)
    result = {'version':1,'mcversion':data['mcversion'],'size_x':1,'size_y':data['size_y'],
              'size_z':width,'palette':palette,'blocks':blocks,'tile_entities':list(tiles.values()),
              'entities':[],'required_mods':data.get('required_mods',[])}
    metadata = {'file':f'street_{kind}.blueprint','axis':'x','surfaceY':1,'width':width,
                'walkRange':None if kind=='lamp' else [1,2],
                'writeCells':[list(pos) for pos in entries], 'skipCells':skip}
    if kind == 'stair': metadata['canonicalFacing']='east'
    return result, metadata, entries, tiles

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-jar',type=Path)
    parser.add_argument('--verify',action='store_true')
    args=parser.parse_args()
    jar=args.source_jar
    if jar is None:
        jar=next(Path(p) for p in (ROOT/'cp.txt').read_text(encoding='utf-8-sig').strip().split(';')
                 if Path(p).name.lower()=='minecolonies.jar')
    manifest={'schemaVersion':1,'units':{},'sources':{}}
    count=0
    with zipfile.ZipFile(jar) as package:
        for kind in ('road','lamp','stair'):
            path='blueprints/minecolonies/original/infrastructure/roads/streets/'+('smallstairs1' if kind=='stair' else 'longroad1')+'.blueprint'
            source=package.read(path); data=read_nbt(source)
            unit, metadata, entries, tiles=extract(data,kind)
            manifest['sources'][path]=hashlib.sha256(source).hexdigest()
            manifest['units'][kind]=metadata
            if args.verify:
                actual=read_nbt((DEST/metadata['file']).read_bytes())
                assert actual==unit, f'{kind}: serialized unit changed'
                for pos,state in entries.items():
                    assert state_at(actual,*pos)==state, (kind,pos)
                    count+=1
                assert {(t['x'],t['y'],t['z']):t for t in actual['tile_entities']}==tiles
                assert read_nbt(encode_nbt(unit))==unit
            else:
                DEST.mkdir(parents=True,exist_ok=True)
                (DEST/metadata['file']).write_bytes(encode_nbt(unit))
    if args.verify:
        assert json.loads((DEST/'units.json').read_text(encoding='utf-8'))==manifest
        print(f'PASS {count} unit cells, all states, tile data, sparse masks and NBT round trips')
    else:
        (DEST/'units.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print('Exported road/lamp/stair resources and source fingerprints')

if __name__=='__main__': main()
