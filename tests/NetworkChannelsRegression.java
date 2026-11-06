import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R9-fix1: both sides must declare the same client-bound channel set.
 *
 * <p>R9 moved the client-bound registrations into a client-only class and gated them on
 * {@code Dist.CLIENT}, which left the dedicated server declaring no client-bound channel at
 * all. The handshake compares channel names, so every client was refused with "the server is
 * missing a channel this client requires". These assertions keep the two lists in step and
 * keep vanilla client types out of the common network class (the R9 startup crash).</p>
 */
public final class NetworkChannelsRegression {
    private static final Path CLIENT = Paths
            .get("src/main/java/com/YLFXM/rp/crossroads/client/ClientPayloadHandlers.java");
    private static final Path SERVER = Paths
            .get("src/main/java/com/YLFXM/rp/crossroads/network/Network.java");

    private static int checks;

    private static void check(final boolean value, final String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(final String[] args) throws Exception {
        final Set<String> client = channels(CLIENT);
        final Set<String> server = channels(SERVER);
        check(Files.exists(CLIENT), "client handler class still exists");
        check(client.size() == 7, "client declares seven client-bound channels: " + client);
        check(server.equals(client), "server declares the same client-bound channels: " + server);

        // The two lists must be reachable the way the guard expects: the client list from its own
        // register method, the server list from the dedicated-server branch of Network.register.
        final String clientSource = Files.readString(CLIENT);
        final String serverSource = Files.readString(SERVER);
        check(clientSource.contains("public static void register(final PayloadRegistrar registrar)"),
                "client list is registered from ClientPayloadHandlers.register");
        check(serverSource.contains("declareClientBoundChannels(registrar)"),
                "server list is registered from the dedicated-server branch");

        // R9's crash gate: the common network class must not name a vanilla client type, or the
        // verifier loads it while constructing the mod and the dedicated server aborts.
        check(!serverSource.contains("net.minecraft.client"),
                "network/Network.java names no vanilla client type");
        check(!serverSource.contains("net.minecraft.client.gui"),
                "network/Network.java names no client GUI type");

        System.out.println("PASS " + checks + " channel assertions");
    }

    /** Channel payload names registered with {@code playToClient(<Name>Payload.TYPE, ...)}. */
    private static Set<String> channels(final Path file) throws Exception {
        if (!Files.exists(file)) return new TreeSet<>();
        final Set<String> out = new TreeSet<>();
        final Matcher m = Pattern.compile("playToClient\\(\\s*([A-Za-z0-9_]+)\\.TYPE").matcher(Files.readString(file));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private NetworkChannelsRegression() { }
}
