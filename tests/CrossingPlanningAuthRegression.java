import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * R10-fix4: authorization for a crossing is not the same question as "is there a grant".
 *
 * <p>Planning heights and preparing construction asked it differently. Construction used
 * the actor-aware overload, so two roads the player manages were authorized and the shared
 * span was rebuilt as real geometry. Height solving used the grant-only overload, so the
 * same pair looked foreign and fell into strict avoidance, which reserves the neighbour's
 * lamp height on top of the normal three-block clearance. A player could therefore set an
 * overpass on their own roads and watch it climb by the lamp height for no visible reason.</p>
 *
 * <p>A pair is authorized when any of these hold: a durable pair grant, one shared colony
 * (or one wilderness creator, which is the same authority without a record), or the
 * recorded planner's live management right. Strict avoidance is left for what it was
 * written for: a neighbour the planner genuinely cannot touch.</p>
 */
public final class CrossingPlanningAuthRegression {
    private static final Path ROAD = Paths.get("src/main/java/com/YLFXM/rp/crossroads/road");

    private static int checks;

    private static void check(final boolean value, final String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(final String[] args) throws Exception {
        final String planning = text(ROAD.resolve("RoadAutoPlanning.java"));
        final String consent = text(ROAD.resolve("RoadCrossingConsent.java"));
        final String actions = text(ROAD.resolve("RoadPlanningActions.java"));
        final String jointWork = text(ROAD.resolve("RoadJointWork.java"));

        // Height solving must ask the planner-aware question, not the grant-only one.
        final String balance = method(planning, "private static void balance(");
        check(balance.contains("canPlanAsPlanner"), "balance() authorizes through canPlanAsPlanner");
        check(!balance.contains("canPlan(level,p,other)"), "balance() no longer uses the grant-only overload");

        // Strict avoidance is the unauthorised branch only: both floor helpers sit inside it.
        final int unauthorised = balance.indexOf("if(!authorized) {");
        check(unauthorised > 0, "balance() keeps an explicit unauthorised branch");
        final int upper = balance.indexOf("RoadCrossingSafety.upperFloor(");
        final int lower = balance.indexOf("RoadCrossingSafety.lowerFloor(");
        check(upper > unauthorised, "upperFloor is applied only when the pair is unauthorised");
        check(lower > unauthorised, "lowerFloor is applied only when the pair is unauthorised");
        check(count(balance, "RoadCrossingSafety.upperFloor(") == 1, "one upper reservation call site");
        check(count(balance, "RoadCrossingSafety.lowerFloor(") == 1, "one lower reservation call site");

        // The three sources of authority, in the order they are tried.
        final String asPlanner = method(consent, "public static boolean canPlanAsPlanner(");
        check(asPlanner.contains("canPlan(level,own,peer)"), "a durable grant always authorizes");
        check(asPlanner.contains("sameAuthority(level,own,peer)"), "shared authority holds without a grant");
        check(asPlanner.contains("planning().planner()"), "the recorded planner is the fallback actor");
        check(asPlanner.contains("RoadPermissions.canManage(level,player,peer)"), "live management right is checked");

        final String same = method(consent, "static boolean sameAuthority(");
        check(same.contains("getColonyByPosFromWorld") && same.contains("getID()==colonyB.getID()"),
                "same colony means one authority");
        check(same.contains("creator()") && same.contains("equals(b.creator())"),
                "wilderness roads of one creator share authority");
        check(same.contains("colonyA!=null && colonyB!=null"),
                "a colony road and a wilderness road are never merged into one authority");

        // The grant-only overload still decides automatic staging; that behaviour is unchanged.
        check(method(consent, "public static boolean canPlan(ServerLevel level,RoadPlan own,RoadPlan peer)")
                .contains("return validGrant(level,own,peer);"), "grant-only overload stays grant-only");
        check(method(actions, "private static void stageAutomaticAvoidance(")
                .contains("RoadCrossingConsent.canPlan(level,plan,n.live())"),
                "automatic avoidance staging still keys off the durable grant");

        // Construction keeps using the actor-aware overload; the two sides now agree.
        check(jointWork.contains("RoadCrossingConsent.canPlan(level,actor,primary,live)"),
                "joint work authorizes with the actor");

        System.out.println("CrossingPlanningAuthRegression: " + checks + " checks passed");
    }

    private static String text(final Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** Body of one method, from its signature to the first closing brace at the same depth. */
    private static String method(final String source, final String signature) {
        final int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("missing method: " + signature);
        int depth = 0;
        final int open = source.indexOf('{', start);
        for (int i = open; i < source.length(); i++) {
            final char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("unterminated method: " + signature);
    }

    private static int count(final String haystack, final String needle) {
        int total = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) total++;
        return total;
    }

    private CrossingPlanningAuthRegression() { }
}
