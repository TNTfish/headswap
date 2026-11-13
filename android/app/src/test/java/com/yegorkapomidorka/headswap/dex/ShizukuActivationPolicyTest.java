package com.yegorkapomidorka.headswap.dex;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShizukuActivationPolicyTest {
    private static final String APP = "com.yegorkapomidorka.headswap.smoke";

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
