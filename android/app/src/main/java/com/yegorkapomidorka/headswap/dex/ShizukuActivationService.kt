package com.yegorkapomidorka.headswap.dex

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Loaded by Shizuku, not an Android Service. The Context belongs to this installed package. */
class ShizukuActivationService(context: Context) : IShizukuActivation.Stub() {
    private val packageName = context.packageName
    private val apk = context.applicationInfo.sourceDir
    private val applicationUid = context.applicationInfo.uid

    override fun activate(token: String, audio: Boolean, nonce: String): Boolean {
        requireCaller()
        check(Build.VERSION.SDK_INT >= ShizukuActivationPolicy.MIN_SDK)
        val options = ManualAccessPolicy.Options(audio, false, packageName, token)
        // Validate everything before running even the preflight. Never log this command or token.
        val command = PhoneActivationPolicy.dispatchCommand(apk, options, nonce)
        if (!installedCopy() || execute(PhoneActivationPolicy.PREFLIGHT).exitCode != 0) return false
        // Recheck immediately before dispatch: another app can activate after the UI preflight.
        if (conflictingActivation().isNotEmpty()) return false
        val result = execute(command)
        return PhoneActivationPolicy.dispatchAccepted(result.exitCode, result.output, nonce)
    }

    override fun readActivationLog(nonce: String): String {
        requireCaller()
        return execute(PhoneActivationPolicy.readLogCommand(nonce)).output
    }

    override fun restartHost(): Boolean {
        requireCaller()
        check(Build.VERSION.SDK_INT >= VdmHostPolicy.MIN_SDK)
        val appPid = Binder.getCallingPid()
        if (!installedCopy()) return false
        return execute(VdmHostPolicy.startCommand(apk, packageName, appPid)).exitCode == 0
    }

    override fun conflictingActivation(): String {
        requireCaller()
        val result = execute(ShizukuActivationPolicy.ACTIVE_INSTRUMENTATIONS_COMMAND)
        // grep returns 1 when there is no active instrumentation header.
        check(result.exitCode == 0 || result.exitCode == 1) { "Cannot inspect desktop activation" }
        return ShizukuActivationPolicy.conflictingActivation(result.output, packageName)
    }

    // The Shizuku server itself invokes this transaction when removing the UserService.
    override fun destroy() {
        val caller = Binder.getCallingUid()
        if (caller != Process.myUid() && caller != applicationUid && caller != 0)
            throw SecurityException("Shizuku service destruction rejected")
        exitProcess(0)
    }

    private fun requireCaller() =
        ShizukuActivationPolicy.requireCaller(Process.myUid(), Binder.getCallingUid(), applicationUid)

    private fun installedCopy(): Boolean {
        val result = execute(ShizukuActivationPolicy.packagePathCommand(packageName, applicationUid))
        return result.exitCode == 0 && PhoneActivationPolicy.packageListed(result.output, apk)
    }

    private data class Result(val exitCode: Int, val output: String)

    /** Drain both streams together, retain bounded output and never wait indefinitely on a shell child. */
    private fun execute(command: String): Result {
        val process = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = ByteArrayOutputStream()
        val reader = thread(name = "headswap-shizuku-output", isDaemon = true) {
            runCatching {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(output) { output.write(buffer, 0, minOf(count, (8192 - output.size()).coerceAtLeast(0))) }
                    }
                }
            }
        }
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Shizuku command timed out" }
            reader.join(500)
            return Result(process.exitValue(), synchronized(output) { output.toString("UTF-8") })
        } finally {
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
    }
}
