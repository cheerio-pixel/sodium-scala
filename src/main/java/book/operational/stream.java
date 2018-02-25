package book.operational;

import scala.runtime.BoxedUnit;
import sodium.*;

//Listing 8.1
public class stream {
    public static void main(String[] args) {
        StreamSink<Integer> sX = new StreamSink<>();
        Stream<Integer> sXPlus1 = sX.map(x -> x + 1);
        Listener l = sXPlus1.listen(x -> { System.out.println(x);
            return BoxedUnit.UNIT;});
        sX.send(1);
        sX.send(2);
        sX.send(3);
        l.unlisten();
    }
}
