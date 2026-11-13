package com.yegorkapomidorka.headswap.dex;

/** Privileged helper boundary. Root is deliberately rejected: the desktop requires a real shell UID. */
public final class ShizukuActivationPolicy {
    public static final int MIN_SDK = 33;
    public static final int MIN_SERVER_VERSION = 13;
    public static final int SHELL_UID = 2000;

    private ShizukuActivationPolicy() { }

    public static void requireCaller(int serviceUid, int callingUid, int applicationUid) {
        if (serviceUid != SHELL_UID || applicationUid < 10000 || callingUid != applicationUid)
            throw new SecurityException("Shizuku activation caller rejected");
    }

    public static String packagePathCommand(String packageName, int applicationUid) {
        ManualAccessPolicy.requirePackage(packageName);
        if (applicationUid < 10000) throw new IllegalArgumentException("Invalid application UID");
        return "pm path --user " + (applicationUid / 100000) + " " + packageName;
    }
}
