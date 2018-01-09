package examples;

import scala.runtime.BoxedUnit;
import sodium.*;

import javax.swing.*;
import java.awt.*;

public class spinme {
    public static void main(String[] args) {
        JFrame view = new JFrame("spinme");
        view.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        view.setLayout(new FlowLayout());
        Transaction.apply(Unit -> {
            SSpinner spnr = new SSpinner(0);
            view.add(spnr);
            return BoxedUnit.UNIT;
        });
        view.setSize(400, 160);
        view.setVisible(true);
    }
}

