import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.minecraft.nbt.*;

/** Native NBT parsing of the bundled unit manifest against the files and the built jar. */
public final class RoadUnitDataRegression {
    private static final String JAR_ROOT="assets/rp_crossroads/blueprints/units/original/";
    private static int checks;
    private static void check(boolean ok,String label) { checks++;if(!ok)throw new AssertionError(label); }
    private static CompoundTag state(CompoundTag data,int x,int y,int z) {
        int index=(y*data.getShort("size_z")+z)*data.getShort("size_x")+x;
        int word=data.getIntArray("blocks")[index/2];
        return data.getList("palette",10).getCompound((index%2==0 ? word>>>16 : word)&65535);
    }
    private static String name(CompoundTag state) { return state.getString("Name"); }
    private static String leaf(String file) { return file.substring(file.lastIndexOf('/')+1); }

    public static void main(String[] args) throws Exception {
        Path directory=Path.of("src/main/resources/assets/rp_crossroads/blueprints/units/original");
        var manifest=JsonParser.parseString(Files.readString(directory.resolve("units.json"))).getAsJsonObject();
        check(manifest.get("schemaVersion").getAsInt()==2,"manifest is schema 2");
        var series=manifest.getAsJsonObject("series");
        check(series.size()==2,"exactly the two series");
        List<String> expectedInJar=new ArrayList<>();
        int units=0;
        for(var seriesEntry:series.entrySet()) {
            final String id=seriesEntry.getKey();
            final var node=seriesEntry.getValue().getAsJsonObject();
            final int width=node.get("width").getAsInt();
            check(width==4 || width==5,"series width is a unit width "+id);
            for(var roleEntry:node.getAsJsonObject("units").entrySet()) {
                final String role=roleEntry.getKey();
                check(List.of("road","stair","lamp").contains(role),"known role "+id+"/"+role);
                for(var levelEntry:roleEntry.getValue().getAsJsonObject().entrySet()) {
                    final String label=id+"/"+role+"/"+levelEntry.getKey();
                    final var spec=levelEntry.getValue().getAsJsonObject();
                    final String file=leaf(spec.get("file").getAsString());
                    check(file.endsWith(".blueprint"),"blueprint file name "+label);
                    check(!file.contains("\\"),"no path separator in unit file "+label);
                    final Path path=directory.resolve(id).resolve(file);
                    check(Files.isRegularFile(path),"unit file exists "+label);
                    expectedInJar.add(JAR_ROOT+id+"/"+file);
                    final CompoundTag unit=NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap());
                    units++;
                    // The pack copy is replaced when its content differs, so the
                    // manifest hash covers the decompressed NBT, not the gzip
                    // container (deflate bytes depend on the zlib build).
                    final byte[] digest;
                    try(var in=new java.util.zip.GZIPInputStream(Files.newInputStream(path))) {
                        final var md=java.security.MessageDigest.getInstance("SHA-256");
                        final byte[] buffer=new byte[8192];int read;
                        while((read=in.read(buffer))>0) md.update(buffer,0,read);
                        digest=md.digest();
                    }
                    final StringBuilder hex=new StringBuilder();
                    for(byte value:digest) hex.append(String.format("%02x",value));
                    check(hex.toString().equals(spec.get("sha256").getAsString()),"unit hash matches the manifest "+label);

                    // A tile spans the whole road; a lamp only spans the columns it
                    // hangs over, so its width is its own number.
                    final int unitWidth=spec.get("width").getAsInt();
                    check(role.equals("lamp") ? unitWidth>=2 && unitWidth<=width : unitWidth==width,
                            "unit width matches its role "+label);
                    check(unit.getShort("size_x")==1,"unit is one block long "+label);
                    check(unit.getShort("size_z")==unitWidth,"unit blueprint is as wide as declared "+label);
                    final int anchor=spec.get("anchorColumn").getAsInt();
                    check(anchor>=0 && anchor<unitWidth,"anchor column inside the span "+label);
                    final int surface=spec.get("surfaceY").getAsInt();
                    // A lamp may anchor one row lower than the pavement: its pole
                    // starts on the surface row, so the unit's own surface marker
                    // sits at 0 and the first solid row is 1.
                    check(surface>=0 && surface<unit.getShort("size_y"),"surface row inside the unit "+label);

                    final Set<String> written=new HashSet<>();
                    for(var element:spec.getAsJsonArray("writeCells")) {
                        final var coords=element.getAsJsonArray();
                        final int x=coords.get(0).getAsInt(),y=coords.get(1).getAsInt(),z=coords.get(2).getAsInt();
                        check(x==0,"write cell on the authored slice "+label);
                        check(y>=0 && y<unit.getShort("size_y"),"write cell inside the height "+label);
                        check(z>=0 && z<unitWidth,"write cell inside the span "+label);
                        check(written.add(x+":"+y+":"+z),"no duplicate write cell "+label);
                    }
                    for(var element:spec.getAsJsonArray("skipCells")) {
                        final var coords=element.getAsJsonArray();
                        check(written.add(coords.get(0).getAsInt()+":"+coords.get(1).getAsInt()+":"+coords.get(2).getAsInt()),
                                "skip cell does not collide with a write cell "+label);
                    }

                    // A write cell may deliberately hold air: that is how the exporter
                    // carves the source pack's decoration controller out of the slice.
                    // Only the structural cells are required to be solid, below.

                    // The anchor column carries the pole, and every walkable column
                    // carries pavement, so neither may be empty at the surface.
                    final int anchorRow=role.equals("lamp") ? Math.min(surface+1,unit.getShort("size_y")-1) : surface;
                    check(!name(state(unit,0,anchorRow,anchor)).endsWith(":air"),"anchor column has a block "+label);
                    if(spec.get("walkRange").isJsonArray()) {
                        final var walk=spec.getAsJsonArray("walkRange");
                        for(int z=walk.get(0).getAsInt();z<=walk.get(1).getAsInt();z++)
                            check(!name(state(unit,0,surface,z)).endsWith(":air"),"walkable column is paved "+label+" z"+z);
                        check(walk.get(0).getAsInt()>0 && walk.get(1).getAsInt()<unitWidth-1,
                                "walk range leaves a curb on both sides "+label);
                    } else {
                        check(role.equals("lamp"),"only a lamp has no walk range "+label);
                    }
                    // domum_ornamentum blocks take their appearance from the block
                    // entity (textureData): without it they render as nothing. Two
                    // rules catch both directions — a block that kept its tile
                    // must still hold a block, and a unit containing domum blocks
                    // must carry at least one texture entry.
                    final var entityTiles=unit.getList("tile_entities",10);
                    int domumCells=0;
                    for(int y=0;y<unit.getShort("size_y");y++)
                        for(int z=0;z<unit.getShort("size_z");z++)
                            if(name(state(unit,0,y,z)).startsWith("domum_ornamentum:")) domumCells++;
                    for(int i=0;i<entityTiles.size();i++) {
                        final var tile=entityTiles.getCompound(i);
                        final int tx=tile.getInt("x"),ty=tile.getInt("y"),tz=tile.getInt("z");
                        check(tx>=0&&tx<unit.getShort("size_x")&&ty>=0&&ty<unit.getShort("size_y")
                                &&tz>=0&&tz<unit.getShort("size_z"),"tile inside the unit "+label);
                        check(!name(state(unit,tx,ty,tz)).endsWith(":air"),"tile sits on a real block "+label);
                    }
                    if(domumCells>0) check(entityTiles.size()>0,"domum blocks keep their texture data "+label+" cells="+domumCells);
                    check(unit.getList("entities",10).isEmpty(),"units carry no spawned entities "+label);
                }
            }
        }
        check(units==21,"twenty-one units: streets 9 and main 12");

        // 0.3.2 seeds two style-level files next to the series. They are not
        // blueprints, so the unit manifest says nothing about them; without an
        // assertion here a fresh save's pack would silently have no cover and
        // the style picker would draw the fallback icon for the shipped style.
        final Path styleJson=directory.resolve("style.json");
        final Path cover=directory.resolve("cover.png");
        check(Files.isRegularFile(styleJson),"shipped style.json exists");
        check(Files.isRegularFile(cover),"shipped cover.png exists");
        final byte[] coverBytes=Files.readAllBytes(cover);
        check(coverBytes.length>8 && coverBytes[0]==(byte)0x89 && coverBytes[1]=='P' && coverBytes[2]=='N'
                && coverBytes[3]=='G',"cover is a PNG");
        final int coverWidth=(coverBytes[16]&0xFF)<<24 | (coverBytes[17]&0xFF)<<16
                | (coverBytes[18]&0xFF)<<8 | (coverBytes[19]&0xFF);
        check(coverWidth>=64,"cover is at least 64 px wide: "+coverWidth);
        final var styleDoc=JsonParser.parseString(Files.readString(styleJson)).getAsJsonObject();
        check(styleDoc.has("cover"),"style.json declares a cover");
        final String declared=styleDoc.get("cover").getAsString();
        check(declared.equals(cover.getFileName().toString()),"style.json names the shipped cover: "+declared);
        final var seeded=com.YLFXM.rp.crossroads.CustomRoadPack.bundledStyleFiles();
        check(seeded.stream().anyMatch(f -> f.relative().equals("original/style.json")),
                "seed list carries the style description");
        check(seeded.stream().anyMatch(f -> f.relative().equals("original/cover.png")),
                "seed list carries the cover");

        final var matcher=java.util.regex.Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\"")
                .matcher(Files.readString(Path.of("src/main/resources/META-INF/neoforge.mods.toml")));
        if(!matcher.find()) throw new IllegalStateException("Mod version missing");
        try(var jar=new ZipFile("dist/rp_crossroads-"+matcher.group(1)+".jar")) {
            for(String file:expectedInJar) check(jar.getEntry(file)!=null,"jar ships "+file);
            check(jar.getEntry(JAR_ROOT+"units.json")!=null,"jar ships the manifest");
            check(jar.getEntry(JAR_ROOT+"style.json")!=null,"jar ships the style description");
            check(jar.getEntry(JAR_ROOT+"cover.png")!=null,"jar ships the cover");
            // The pre-series spellings must not linger: they would show up as
            // duplicate categories in the preview list.
            for(var entries=jar.entries();entries.hasMoreElements();) {
                final String name=entries.nextElement().getName();
                check(!name.contains("units/original/street_"),"jar has no flat pre-series unit "+name);
                check(!name.contains("units/原版/"),"jar has no localized unit tree "+name);
            }
        }
        System.out.println("PASS "+checks+" checks over "+units+" units in two series");
    }
}
