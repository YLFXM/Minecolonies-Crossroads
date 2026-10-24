import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.minecraft.nbt.*;

/** Native NBT parsing of bundled assets against the actual installed source JAR. */
public final class RoadUnitDataRegression {
    private static int checks;
    private static void check(boolean ok,String label) { checks++;if(!ok)throw new AssertionError(label); }
    private static CompoundTag state(CompoundTag data,int x,int y,int z) {
        int index=(y*data.getShort("size_z")+z)*data.getShort("size_x")+x;
        int word=data.getIntArray("blocks")[index/2];
        return data.getList("palette",10).getCompound((index%2==0 ? word>>>16 : word)&65535);
    }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of("src/main/resources/assets/mcreconstruction/blueprints/units/original");
        var manifest=JsonParser.parseString(Files.readString(directory.resolve("units.json"))).getAsJsonObject();
        for(String kind:List.of("road","lamp","stair")) {
            var spec=manifest.getAsJsonObject("units").getAsJsonObject(kind);
            CompoundTag source;
            String name=kind.equals("stair") ? "smallstairs1" : "longroad1";
            try(var input=RoadUnitDataRegression.class.getResourceAsStream("/blueprints/minecolonies/original/infrastructure/roads/streets/"+name+".blueprint")) {
                source=NbtIo.readCompressed(input,NbtAccounter.unlimitedHeap());
            }
            var unit=NbtIo.readCompressed(directory.resolve(spec.get("file").getAsString()),NbtAccounter.unlimitedHeap());
            check(unit.getShort("size_x")==1 && unit.getShort("size_z")==spec.get("width").getAsInt(),"native dimensions "+kind);
            int column=kind.equals("lamp") ? 3 : kind.equals("stair") ? 1 : 0;
            var written=new HashSet<String>();
            for(var element:spec.getAsJsonArray("writeCells")) {
                var coords=element.getAsJsonArray();int x=coords.get(0).getAsInt(),y=coords.get(1).getAsInt(),z=coords.get(2).getAsInt();
                check(written.add(x+":"+y+":"+z),"no duplicate write cell "+kind);
                var expected=state(source,column,y,z).copy();
                if(expected.getString("Name").equals("minecolonies:decorationcontroller")) {
                    expected=new CompoundTag();expected.putString("Name","minecraft:air");
                } else if(kind.equals("stair") && expected.getCompound("Properties").contains("facing")) {
                    expected.getCompound("Properties").putString("facing","east");
                }
                check(expected.equals(state(unit,x,y,z)),"native per-cell palette parity "+kind+" "+coords);
            }
            check(spec.getAsJsonArray("skipCells").size()==(kind.equals("lamp") ? 1 : 0),"lamp has one nonwriting pavement cell");
            var originalTiles=source.getList("tile_entities",10);
            var exportedTiles=unit.getList("tile_entities",10);
            int expectedTiles=0;
            for(int i=0;i<originalTiles.size();i++) {
                var tile=originalTiles.getCompound(i); if(tile.getInt("x")!=column) continue;
                if(kind.equals("lamp") && (tile.getInt("y")<1 || tile.getInt("z")>=2)) continue;
                if(tile.getString("id").equals("minecolonies:decorationcontroller")) continue;
                expectedTiles++;var expected=tile.copy();expected.putInt("x",0);expected.putInt("y",tile.getInt("y"));expected.putInt("z",tile.getInt("z"));
                boolean found=false;for(int j=0;j<exportedTiles.size();j++) if(expected.equals(exportedTiles.getCompound(j))) found=true;
                check(found,"native block-entity material and local coordinate parity "+kind);
            }
            check(expectedTiles==exportedTiles.size(),"no controller or unexpected tile entities "+kind);
            check(unit.getList("entities",10).isEmpty(),"units carry no spawned entities");
        }
        var matcher=java.util.regex.Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\"")
                .matcher(Files.readString(Path.of("src/main/resources/META-INF/neoforge.mods.toml")));
        if(!matcher.find()) throw new IllegalStateException("Mod version missing");
        try(var jar=new ZipFile("dist/mcreconstruction-"+matcher.group(1)+".jar")) {
            for(String file:List.of("units.json","street_road.blueprint","street_lamp.blueprint","street_stair.blueprint")) {
                try(var stream=jar.getInputStream(jar.getEntry("assets/mcreconstruction/blueprints/units/original/"+file))) {
                    check(Arrays.equals(stream.readAllBytes(),Files.readAllBytes(directory.resolve(file))),"built jar contains exact unit resource "+file);
                }
            }
        }
        System.out.println("PASS "+checks+" native NBT/unit resource assertions");
    }
}
