package dioxus.compose.ui.platform;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.TargetElement;

/**
 * Keeps the toolkit out of an image that does not open a toolkit window.
 *
 * The renderer opens its own window and never asks Compose for one, so none of the
 * toolkit's windows, panels or peers are reached. What is still reached is smaller and
 * harder to see: three places where Compose keeps a toolkit answer as its default and
 * touches it whether or not anyone wanted it.
 *
 * An event is asked whether it came from the toolkit, on every pointer move and every
 * key. The answer here is always no, and it was always no before, but the question names
 * the toolkit's own event classes and naming them is enough to bring the toolkit in.
 *
 * The clipboard and the cursor are the same shape: a default implementation written
 * against the toolkit, reachable from the platform contract whether or not this window
 * uses it. This window answers both for itself.
 *
 * Measured rather than assumed. The analysis report named exactly these, and nothing of
 * this project's own code reaches the toolkit at all.
 *
 * Java rather than Kotlin, for the reason written beside the other substitution here: the
 * Compose compiler adds a field to the Kotlin classes it sees, and a substitution class
 * may only hold members that say what they do to the original.
 */
public final class NoToolkitSubstitutions {

    private NoToolkitSubstitutions() {
    }
}

/**
 * The question "did this event come from the toolkit", answered without naming its
 * classes.
 *
 * Returning the same nothing it returned before. Every event this window delivers was
 * built from parts on this side, so there has never been a toolkit event to hand back.
 */
@TargetClass(className = "androidx.compose.ui.awt.AwtEvents_desktopKt")
final class TargetAwtEvents {

    @Substitute
    public static java.awt.event.MouseEvent getAwtEventOrNull(
            androidx.compose.ui.input.pointer.PointerEvent event) {
        return null;
    }

    // The original's name carries a suffix the Kotlin compiler adds to a function that
    // takes an inline class, which is not a name Java can spell. Said here instead.
    @Substitute
    @TargetElement(name = "getAwtEventOrNull-ZmokQxo")
    public static java.awt.event.KeyEvent getAwtKeyEventOrNull(Object event) {
        return null;
    }
}

/**
 * The clipboard, answered by this window rather than by the toolkit.
 *
 * Compose keeps a toolkit-backed clipboard as its default and holds it whether or not
 * anything asks. This window has its own, so what is left here is the shape of the
 * contract with nothing of the toolkit behind it.
 */
@TargetClass(className = "androidx.compose.ui.platform.AwtPlatformClipboard")
final class TargetAwtClipboard {

    @Substitute
    public Object getNativeClipboard() {
        throw new UnsupportedOperationException(
                "this window answers for the clipboard itself; there is no toolkit one");
    }
}
