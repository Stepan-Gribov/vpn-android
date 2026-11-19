# TnDtun Android Amnezia transport

Independent Android executable using the official AmneziaWG Go v3.1.20260828
(MIT). The loopback SOCKS5 adapter and the WireGuard/gVisor netstack adapter
were adapted from KindlyVPN/TnDtun build 19. No iOS repository is needed to
build this Android application.

TCP and SOCKS5 UDP associations run through AmneziaWG. The shared Android
Xray router applies domains/IP/CIDR split rules before choosing this transport.
The app UID is excluded from Android's TUN so the external transport's UDP
socket never loops into itself. Android physical-network changes are delivered
through the helper's persistent stdin control stream. UDP sockets are bound with
android_setsocknetwork inside Bind.Open, before any packet can be sent. Rebind
first reopens UDP sockets with a new source port while retaining current keys,
peer timers, the userspace TUN/netstack, SOCKS listener and TCP/UDP connections.
Only after a failed soft-repair round does the Android owner request a transport
reset with a fresh handshake; that fallback still retains the netstack/streams.
Its 50 ms pause avoids the peer's 20 ms handshake anti-flood window. Repeated
failed checks may ultimately replace the helper, closing its SOCKS streams.
Configured server endpoints use the official Android backend's mobile roaming
policy. Stop shuts down both engines.

Source dependencies are pinned in go.mod/go.sum. Rebuild the three bundled
Android executables with scripts/build-amnezia.sh from the repository root.
They use Android NDK 29, API 24 and 16 KB ELF page alignment. Android Studio
Debug uses the checked-in executables and does not require Go or the NDK.
The build script defaults to Go 1.26.3 (`GOTOOLCHAIN`), matching go.mod.

The local netstack patch reads directly from gVisor's bounded channel endpoint.
It deliberately avoids synchronous WriteNotify delivery: a packet producer must
not wait for the encryption reader while holding transport locks. Read views
and injected packet buffers release their owned references. Close is concurrent
and idempotent, cancels a blocked reader and drains the endpoint. If the AWG
device exits unexpectedly, the executable exits too, allowing Android to report
the failure instead of retaining a live but unusable SOCKS listener. The native
helper itself adds no watchdog or periodic probe; Android's existing service-owned
recovery policy decides when to request soft/hard repair.

go test ./... runs local encrypted AWG3 TCP/UDP, restart, expired-NAT and
established-stream rebind tests. Packet-loss regression tests retain one TCP
stream with fresh handshakes unavailable or all UDP dropped for 500 ms, and
then exercise the hard fallback. These are localhost tests, not a physical
Android Wi-Fi acceptance test. They do not change the host computer's VPN.
