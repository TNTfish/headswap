package com.yegorkapomidorka.headswap.dex;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShizukuActivationPolicyTest {
    private static final String APP = "com.yegorkapomidorka.headswap.smoke";

    @Test public void anotherPackagesUnfinishedInstrumentationBlocksBeforeRestart() {
        String headers = "mClass=ComponentInfo{com.yegorkapomidorka.headswap/"
            + "com.yegorkapomidorka.headswap.dex.DelegatedDesktopInstrumentation} mFinished=false";
        assertEquals("com.yegorkapomidorka.headswap",
            ShizukuActivationPolicy.conflictingActivation(headers, "io.github.tntfish.headswap"));
        assertEquals("", ShizukuActivationPolicy.conflictingActivation(headers, "com.yegorkapomidorka.headswap"));
        assertEquals("", ShizukuActivationPolicy.conflictingActivation(headers.replace("false", "true"), APP));
    }

    @Test public void probeIgnoresEmptyOutputArgumentsAndMalformedComponents() {
        assertEquals("", ShizukuActivationPolicy.conflictingActivation(null, APP));
        assertEquals("", ShizukuActivationPolicy.conflictingActivation("", APP));
        assertEquals("", ShizukuActivationPolicy.conflictingActivation("mArguments=Bundle[parcelled]", APP));
        assertEquals("", ShizukuActivationPolicy.conflictingActivation("mClass=ComponentInfo{bad;id/Test} mFinished=false", APP));
        String own = "mClass=ComponentInfo{" + APP + "/Test} mFinished=false\r\n";
        assertEquals("org.example.test", ShizukuActivationPolicy.conflictingActivation(own
            + "mClass=ComponentInfo{org.example.test/Other} mFinished=false", APP));
    }

    @Test public void onlyTheOwningAppCanCallARealShellHelper() {
        ShizukuActivationPolicy.requireCaller(2000, 10234, 10234);
        ShizukuActivationPolicy.requireCaller(2000, 1010234, 1010234);
        for (int[] ids : new int[][] {{0,10234,10234}, {10234,10234,10234}, {2000,10235,10234},
                {2000,0,10234}, {2000,2000,10234}, {2000,10234,1010234}, {2000,2000,2000}}) {
            try {
                ShizukuActivationPolicy.requireCaller(ids[0], ids[1], ids[2]);
                fail("Rejected caller accepted");
            } catch (SecurityException expected) { }
        }
    }

    @Test public void installedCopyIsCheckedInTheAppsUser() {
        assertEquals("pm path --user 0 " + APP, ShizukuActivationPolicy.packagePathCommand(APP, 10234));
        assertEquals("pm path --user 10 " + APP, ShizukuActivationPolicy.packagePathCommand(APP, 1010234));
    }

    @Test public void packageProbeRejectsCommandsAndSystemUids() {
        for (String pkg : new String[] {null, "", "bad", APP + ";id", APP + "\nreboot", "$(id).app"}) {
            try {
                ShizukuActivationPolicy.packagePathCommand(pkg, 10234);
                fail("Invalid package accepted");
            } catch (IllegalArgumentException expected) { }
        }
        try {
            ShizukuActivationPolicy.packagePathCommand(APP, 2000);
            fail("System UID accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
