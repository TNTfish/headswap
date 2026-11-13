package com.yegorkapomidorka.headswap.dex;

/** Privileged helper boundary. Root is deliberately rejected: the desktop requires a real shell UID. */
public final class ShizukuActivationPolicy {
    public static final int MIN_SDK = 33;
    public static final int MIN_SERVER_VERSION = 13;
    public static final int SHELL_UID = 2000;
    /** Select component headers only; do not return instrumentation arguments or activation tokens. */
    public static final String ACTIVE_INSTRUMENTATIONS_COMMAND =
        "dumpsys activity processes | sed -n '/Active instrumentation:/,$p' | grep 'mClass=ComponentInfo'";

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

    /** Android delegates shell permissions to one instrumentation at a time, across package IDs. */
    public static String conflictingActivation(String headers, String ownPackage) {
        ManualAccessPolicy.requirePackage(ownPackage);
        if (headers == null) return "";
        java.util.regex.Pattern component = java.util.regex.Pattern.compile(
            "mClass=ComponentInfo\\{([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/[^}]+\\}.*mFinished=false");
        for (String line : headers.split("\\r?\\n")) {
            java.util.regex.Matcher match = component.matcher(line);
            if (match.find() && !ownPackage.equals(match.group(1))) return match.group(1);
        }
        return "";
    }
}
