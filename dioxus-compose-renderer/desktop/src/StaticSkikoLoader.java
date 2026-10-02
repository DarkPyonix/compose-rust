package dioxus.compose.ui.platform;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.util.function.BooleanSupplier;

/**
 * Stops Skia being looked for as a file once it is part of the image.
 *
 * Registering a built-in library is only half of it. That half answers
 * System.loadLibrary, and skiko's desktop loader does not call System.loadLibrary: it
 * resolves a path from `skiko.library.path` or unpacks the library out of the jar into a
 * temporary directory, then calls System.load with that absolute path. Android is the one
 * platform where it calls System.loadLibrary, and that is the branch a built-in library
 * replaces. So the image can contain Skia and the loader still fails, looking for a file
 * that does not have to exist any more.
 *
 * The cut is as small as it can be. skiko's loadOnce is a lock, then this, then the
 * caller's own initialisation, then the lock is released and marked done. Replacing this
 * one method leaves the locking, the once-only guarantee and the initialisation pass as
 * skiko's, so nothing about how Skia is set up is restated here where it could drift.
 *
 * The one thing that goes with it is the additional file, which on Windows is the 10MB
 * ICU data table. Skipping the lookup skips unpacking that too, and a table Skia cannot
 * find is its own problem, handled where the rest of that file is.
 *
 * Java rather than Kotlin. The Compose compiler plugin adds a `$stable` field to the
 * Kotlin classes it sees, and a substitution class may only hold members that say what
 * they do to the original, so a generated field it has never heard of stops the build.
 * `Win32DrawCallback` is the other Java file here, written that way for a reason of its
 * own.
 */
final class StaticSkikoLoader {

    private StaticSkikoLoader() {
    }

    /**
     * True when this build put Skia inside the image, which is what makes the
     * substitution correct and what would make it wrong otherwise.
     *
     * A substitution with no condition applies to every build, and an ordinary build
     * still has a file to open.
     */
    static final class StaticSkikoLinked implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return "true".equals(System.getProperty("dioxus.compose.staticSkiko"));
        }
    }
}

@TargetClass(className = "org.jetbrains.skiko.LibraryLoader",
    onlyWith = StaticSkikoLoader.StaticSkikoLinked.class)
final class Target_org_jetbrains_skiko_LibraryLoader {

    @Substitute
    private void findAndLoadLibrary(String name, String additionalFile) {
        // Already here.
    }
}
