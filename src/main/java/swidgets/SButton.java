package swidgets;

import scala.runtime.BoxedUnit;
import sodium.*;

import javax.swing.*;

public class SButton extends JButton {
    public SButton(String label) {
        this(label, new Cell<Boolean>(true));
    }

    public SButton(String label, Cell<Boolean> enabled) {
        super(label);
        StreamSink<Void> sClickedSink = new StreamSink<>();
        this.sClicked = sClickedSink;
        Void tmp = null;
        addActionListener(e -> sClickedSink.send(null));;
        // Do it at the end of the transaction so it works with looped cells
        Transaction.post(() -> setEnabled(enabled.sample()));
        l = enabled.updates().listen(
            ena -> {
                if (SwingUtilities.isEventDispatchThread())
                    this.setEnabled(ena);
                else {
                    SwingUtilities.invokeLater(() -> {
                        this.setEnabled(ena);
                    });
                }
                return BoxedUnit.UNIT;
            }
        );
    }

    private final Listener l;
    public final Stream<Void> sClicked;

    public void removeNotify() {
        l.unlisten();
        super.removeNotify();
    }
}
