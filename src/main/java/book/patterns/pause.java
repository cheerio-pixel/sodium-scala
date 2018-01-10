package book.patterns;

import scala.runtime.BoxedUnit;
import sodium.*;

import java.util.Optional;

public class pause {
    public static Cell<Double> pausableClock(Stream<Void> sPause,
            Stream<Void> sResume, Cell<Double> clock) {
        Cell<Optional<Double>> pauseTime =
            sPause.snapshot(clock, (u, t) -> Optional.<Double>of(t))
                .orElse(sResume.map(u -> Optional.<Double>empty()))
                .hold(Optional.<Double>empty());
        Cell<Double> lostTime = sResume.<Double>accum(
            0.0,
            (u, total) -> {
                double tPause = pauseTime.sample().get();
                double now    = clock.sample();
                return total + (now - tPause);
            });
        return pauseTime.lift(clock, lostTime,
        	(otPause, tClk, tLost) ->
				(otPause.isPresent() ? otPause.get()
									 : tClk)
				- tLost);
    }

    public static void main(String[] args) {
        CellSink<Double> mainClock = new CellSink<>(0.0);
        StreamSink<Void> sPause = new StreamSink<>();
        StreamSink<Void> sResume = new StreamSink<>();
        Cell<Double> gameClock = pausableClock(sPause, sResume, mainClock);
        Listener l = mainClock.lift(gameClock,
        	                        (m, g) -> "main="+m+" game="+g)
                              .listen(txt -> { System.out.println(txt); return BoxedUnit.UNIT; });
        mainClock.send(1.0);
        mainClock.send(2.0);
        mainClock.send(3.0);
        sPause.send(null);
        mainClock.send(4.0);
        mainClock.send(5.0);
        mainClock.send(6.0);
        sResume.send(null);
        mainClock.send(7.0);
        l.unlisten();
    }
}

