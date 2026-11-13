# 0040 — Shizuku desktop activation

Status: accepted in the Chinese fork, device compatibility under test.

## Context

The fork user requested Shizuku activation to avoid pairing Headswap with wireless debugging separately.
The desktop does more than grant ordinary package permissions: its instrumentation bootstrap adopts shell
permissions, and Android 17 additionally requires a real UID 2000 VirtualDevice host (ADR 0033).

## Decision

Use the official Shizuku API/provider 13.1.5 and a restricted UserService on Shizuku server API 13+.
The UI requests Shizuku authorization only after a tap, independently of ADB/network/notification permissions.
Support ADB-started Shizuku (real shell UID 2000); reject root/Sui rather than weaken the desktop UID checks.

The UserService derives its package, installed APK and app UID from Shizuku's package Context. Every operation
checks the Binder caller UID. It exposes only activation, reading a nonce-scoped activation log, host restart,
and the Shizuku destruction transaction. There is no generic command API. Activation reuses the existing
token validation, installed-copy check, detached launcher and instrumentation; tokens/commands are never logged.

Record nonce, start time and selected backend synchronously before dispatch, because the bootstrap replaces
the calling app process. A Binder connection or dispatch acknowledgment is not proof of desktop readiness.
Only DelegatedDesktopAccess determines success. Failure collection uses the recorded backend.

Use non-daemon UserServices and explicit teardown: the helper exits with its client, while the existing
setsid/nohup child survives app replacement. Both service removal and callback removal are requested.
Bind waits, command execution and retained command output are bounded. Cancellation stops waiting; an already
dispatched instrumentation bootstrap cannot safely be undone by cancelling the UI operation.

On Android 17, VdmHostRestart uses Shizuku for a Shizuku activation and does not pin an ADB TCP port. Shizuku
must remain running and authorized for recovery. Restart rate limits and readiness checks are unchanged.
No promise is made that Shizuku itself survives every vendor's USB/cgroup behavior.

## Consequences

No separate Headswap pairing is required when Shizuku is already running. Android 13 remains the desktop
minimum. Reboot still requires restarting Shizuku and reactivating Headswap. Existing ADB pairing and manual
computer activation remain available. Real-car/vendor compatibility must be tested before a stable release.
