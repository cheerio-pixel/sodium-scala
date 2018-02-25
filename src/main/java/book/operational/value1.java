package book.operational;

import scala.runtime.BoxedUnit;
import sodium.*;

//Listing 8.5
public class value1 {
    public static void main(String[] args) {
        CellSink<Integer> x = new CellSink<>(0);
        x.send(1);
        //Warning : book syntax change
        Listener l = x.values().listen(x_ -> {
            System.out.println(x_);  return BoxedUnit.UNIT;
        });
        x.send(2);
        x.send(3);
        l.unlisten();
    }
}
