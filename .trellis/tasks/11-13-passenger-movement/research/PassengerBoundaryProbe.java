import java.util.List;
import work.art1st.javarailways.bukkit.passenger.*;

// Research-only executable probe: calls the already compiled production geometry, no server/world.
class PassengerBoundaryProbe {
    static PassengerSurface surface(PassengerSurfaceSource id, double centerZ) {
        return new PassengerSurface(id,
            new PassengerPose(new PassengerPoint(0.0, 0.0, centerZ), 0.0, 0.0, 0.0, 1.0), 1.0, 1.0);
    }
    public static void main(String[] args) {
        var id = new PassengerSurfaceSource(1.0, 1.0);
        var before = surface(id, 0.0);
        var exit = PassengerWalking.INSTANCE.advance(List.of(before), before,
            new PassengerPoint(0.0, 0.0, 0.99), new PassengerPoint(0.0, 0.0, 1.0), 0.215);
        if (exit.getSurface() != null) throw new AssertionError("Must leave first surface");
        System.out.println("frame N centerZ=0; local startZ=0.99; input +Z; step=0.215");
        System.out.println("outside feet=" + exit.getWorldFeet());
        System.out.println("same frame contact=" + before.contact(exit.getWorldFeet()));
        var after = surface(id, 0.3);
        System.out.println("frame N+1 centerZ=+0.3 (train 6 m/s); outside feet unchanged");
        System.out.println("new frame contact=" + after.contact(exit.getWorldFeet()));
        if (after.contact(exit.getWorldFeet()) == null) throw new AssertionError("Moved platform must cover feet again");
        var falling = new PassengerPoint(exit.getWorldFeet().getX(), -0.08, exit.getWorldFeet().getZ());
        System.out.println("new frame contact after 0.08m fall=" + after.contact(falling));
        if (after.contact(falling) == null) throw new AssertionError("Existing contact tolerance permits first fall tick");
        var sideExit = new PassengerPoint(1.205, 0.0, 0.0);
        System.out.println("side +X exit under pure +Z train motion=" + after.contact(sideExit));
        if (after.contact(sideExit) != null) throw new AssertionError("Pure +Z motion must not cover +X exit");
    }
}
