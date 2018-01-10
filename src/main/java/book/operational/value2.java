package book.operational;

import scala.runtime.BoxedUnit;
import sodium.*;

public class value2 {
    public static void main(String[] args) {
        CellSink<Integer> x = new CellSink<>(0);
        x.send(1);
        Listener l = Transaction.apply(Unit -> {
            return Operational.value(x).listen(x_ -> {
                System.out.println(x_);  return BoxedUnit.UNIT;
            });
        });
        x.send(2);
        x.send(3);
        l.unlisten();
    }
}
