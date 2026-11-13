package com.yegorkapomidorka.headswap.dex;

/** Restricted commands only; no arbitrary shell execution or app-supplied APK/package. */
interface IShizukuActivation {
    boolean activate(String token, boolean audio, String nonce) = 0;
    String readActivationLog(String nonce) = 1;
    boolean restartHost() = 2;
    void destroy() = 16777114;
}
