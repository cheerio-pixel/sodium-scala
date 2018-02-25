package book.operational;

import scala.runtime.BoxedUnit;
import sodium.*;

//Listing 8.3
public class sametrans {
    public static void main(String[] args) {
        StreamSink<Integer> sX = new StreamSink<>();
        Stream<Integer> sXPlus1 = sX.map(x -> x + 1);
        Listener l = Transaction.apply(Unit -> {
            sX.send(1);
            Listener l_ = sXPlus1.listen(x -> { System.out.println(x);  return BoxedUnit.UNIT;});
            return l_;
        });
        sX.send(2);
        sX.send(3);
        l.unlisten();
    }
}
