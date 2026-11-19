package main

import (
	"encoding/binary"
	"golang.org/x/net/proxy"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"
)

// Exercise actual encrypted packets and one pre-existing SOCKS/TCP stream.
// Selectively losing handshake packets makes an accidental key reset observable.
func TestSoftRebindPreservesStreamDuringPacketLoss(t *testing.T) {
	for _, handshakeOnly := range []bool{true, false} {
		name := "all-packets-500ms"
		if handshakeOnly {
			name = "handshakes-unavailable"
		}
		t.Run(name, func(t *testing.T) {
			privateA, publicA := amneziaTestKey(t)
			privateB, publicB := amneziaTestKey(t)
			config := func(private, public, address string) amneziaConfig {
				return amneziaConfig{Addresses: []string{address}, MTU: 1280,
					UAPI: "private_key=" + private + "\nreplace_peers=true\npublic_key=" + public + "\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=25\n"}
			}
			server, err := newAmneziaProxy(config(privateA, publicB, "10.82.0.1"), "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer server.Close()
			info, err := server.device.IpcGet()
			if err != nil {
				t.Fatal(err)
			}
			port := ""
			for _, line := range strings.Split(info, "\n") {
				if value, ok := strings.CutPrefix(line, "listen_port="); ok {
					port = value
				}
			}
			upstream, err := net.ResolveUDPAddr("udp", "127.0.0.1:"+port)
			if err != nil {
				t.Fatal(err)
			}
			relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
			if err != nil {
				t.Fatal(err)
			}
			var mu sync.Mutex
			var clientAddr *net.UDPAddr
			var outageUntil time.Time
			dropping := false
			drops := 0
			done := make(chan struct{})
			go func() {
				defer close(done)
				buf := make([]byte, 65536)
				for {
					n, src, e := relay.ReadFromUDP(buf)
					if e != nil {
						return
					}
					mu.Lock()
					target := upstream
					if src.Port == upstream.Port {
						target = clientAddr
					} else {
						clientAddr = src
					}
					blocked := dropping && (handshakeOnly && n >= 4 && binary.LittleEndian.Uint32(buf[:4]) != 4 || !handshakeOnly && time.Now().Before(outageUntil))
					if blocked {
						drops++
					}
					mu.Unlock()
					if !blocked && target != nil {
						relay.WriteToUDP(buf[:n], target)
					}
				}
			}()
			defer func() { relay.Close(); <-done }()
			listener, err := server.network.ListenTCPAddrPort(netip.MustParseAddrPort("10.82.0.1:81"))
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			go func() {
				for {
					c, e := listener.Accept()
					if e != nil {
						return
					}
					go func() { defer c.Close(); io.Copy(c, c) }()
				}
			}()
			cfg := config(privateB, publicA, "10.82.0.2")
			cfg.UAPI += "endpoint=" + relay.LocalAddr().String() + "\n"
			client, err := newAmneziaProxy(cfg, "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			dialer, err := proxy.SOCKS5("tcp", client.listener.Addr().String(), nil, &net.Dialer{Timeout: time.Second})
			if err != nil {
				t.Fatal(err)
			}
			stream, err := dialer.Dial("tcp", "10.82.0.1:81")
			if err != nil {
				t.Fatal(err)
			}
			defer stream.Close()
			exchange := func(message string, timeout time.Duration) {
				t.Helper()
				stream.SetDeadline(time.Now().Add(timeout))
				if _, e := stream.Write([]byte(message)); e != nil {
					t.Fatal(e)
				}
				body := make([]byte, len(message))
				if _, e := io.ReadFull(stream, body); e != nil {
					t.Fatal("existing encrypted stream", e)
				}
				if string(body) != message {
					t.Fatal("corrupt payload")
				}
			}
			exchange("before", 5*time.Second)
			oldNetstack := client.network
			mu.Lock()
			oldPort := clientAddr.Port
			dropping = true
			outageUntil = time.Now().Add(500 * time.Millisecond)
			mu.Unlock()
			started := time.Now()
			if err = client.Rebind(0); err != nil {
				t.Fatal(err)
			}
			exchange("after-soft-rebind", 3*time.Second)
			mu.Lock()
			newPort := clientAddr.Port
			lost := drops
			dropping = false
			mu.Unlock()
			if oldPort == newPort {
				t.Fatal("socket source port did not change")
			}
			if !handshakeOnly && lost == 0 {
				t.Fatal("fixture did not lose packets")
			}
			if client.network != oldNetstack {
				t.Fatal("netstack replaced")
			}
			t.Logf("same TCP stream, socket changed, packet-loss recovery %s", time.Since(started))
			// Escalation still renews keys without retiring this TCP connection.
			if err = client.Reset(0); err != nil {
				t.Fatal(err)
			}
			exchange("after-hard-fallback", 10*time.Second)
			if client.network != oldNetstack {
				t.Fatal("fallback replaced netstack")
			}
		})
	}
}
