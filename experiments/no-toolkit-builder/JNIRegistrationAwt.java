package com.oracle.svm.hosted.jdk;

import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.jdk.JNIRegistrationUtil;
import org.graalvm.nativeimage.hosted.Feature;

/**
 * The builder's own AWT registration, doing nothing.
 *
 * Swapped in over the one the builder ships, which registers the toolkit whenever the
 * platform is one the toolkit runs on, with no option to say otherwise. That registration
 * is the seed for nine and a half megabytes of look and feel this renderer never draws
 * with, and nothing reachable asks for it: it is put there because java.desktop exists.
 */
public class JNIRegistrationAwt extends JNIRegistrationUtil implements InternalFeature {

    @Override
    public void duringSetup(Feature.DuringSetupAccess access) {
    }

    @Override
    public void beforeAnalysis(Feature.BeforeAnalysisAccess access) {
    }
}
