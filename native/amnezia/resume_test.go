package main

import (
	"golang.org/x/net/proxy"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"
)

// A NAT mapping can expire while Android keeps the same Network handle/IP.
// Break the old UDP source port, then require real encrypted HTTP after Rebind.
func TestExpiredNATMappingRecoversWithoutRecreatingNetstack(t *testing.T) {
	privateA, publicA := amneziaTestKey(t)
	privateB, publicB := amneziaTestKey(t)
	config := func(private, public, address string) amneziaConfig {
		return amneziaConfig{Addresses: []string{address}, MTU: 1280,
			UAPI: "private_key=" + private + "\nrekey_after_time=2\nreject_after_time=4\nrekey_timeout=1\nreplace_peers=true\npublic_key=" + public + "\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=25\n"}
	}
	server, err := newAmneziaProxy(config(privateA, publicB, "10.81.0.1"), "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	info, err := server.device.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	var port string
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
	defer relay.Close()
	var mu sync.Mutex
	var clientAddr *net.UDPAddr
	blockedPort := 0
	done := make(chan struct{})
	go func() {
		defer close(done)
		buffer := make([]byte, 65536)
		for {
			n, source, e := relay.ReadFromUDP(buffer)
			if e != nil {
				return
			}
			mu.Lock()
			target := upstream
			if source.Port == upstream.Port {
				target = clientAddr
			} else {
				if source.Port == blockedPort {
					mu.Unlock()
					continue
				}
				clientAddr = source
			}
			mu.Unlock()
			if target != nil {
				relay.WriteToUDP(buffer[:n], target)
			}
		}
	}()
	defer func() { relay.Close(); <-done }()
	listener, err := server.network.ListenTCPAddrPort(netip.MustParseAddrPort("10.81.0.1:80"))
	if err != nil {
		t.Fatal(err)
	}
	httpServer := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "real-encrypted-response") })}
	go httpServer.Serve(listener)
	defer httpServer.Close()
	cfg := config(privateB, publicA, "10.81.0.2")
	cfg.UAPI += "endpoint=" + relay.LocalAddr().String() + "\n"
	client, err := newAmneziaProxy(cfg, "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	echoListener, err := server.network.ListenTCPAddrPort(netip.MustParseAddrPort("10.81.0.1:81"))
	if err != nil {
		t.Fatal(err)
	}
	defer echoListener.Close()
	go func() {
		for {
			connection, e := echoListener.Accept()
			if e != nil {
				return
			}
			go func() { defer connection.Close(); io.Copy(connection, connection) }()
		}
	}()
	dialer, err := proxy.SOCKS5("tcp", client.listener.Addr().String(), nil, &net.Dialer{Timeout: time.Second})
	if err != nil {
		t.Fatal(err)
	}
	stream, err := dialer.Dial("tcp", "10.81.0.1:81")
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	exchange := func(message string) {
		stream.SetDeadline(time.Now().Add(10 * time.Second))
		if _, e := stream.Write([]byte(message)); e != nil {
			t.Fatal(e)
		}
		body := make([]byte, len(message))
		if _, e := io.ReadFull(stream, body); e != nil {
			t.Fatal("existing TCP flow", e)
		}
		if string(body) != message {
			t.Fatal("existing TCP corrupt")
		}
	}
	if client.lastReceiveNotice.Load() != 0 {
		t.Fatal("Handshake counted as payload")
	}
	exchange("before-outage")
	// The reader can consume a successful Write before the writer's callback runs.
	untilNotice := time.Now().Add(time.Second)
	for client.lastReceiveNotice.Load() == 0 && time.Now().Before(untilNotice) {
		time.Sleep(time.Millisecond)
	}
	if client.lastReceiveNotice.Load() == 0 {
		t.Fatal("Real TCP payload not reported")
	}
	httpClient := amneziaHTTPClient(t, client)
	fetch := func() error {
		response, e := httpClient.Get("http://10.81.0.1/")
		if e != nil {
			return e
		}
		defer response.Body.Close()
		body, e := io.ReadAll(response.Body)
		if e == nil && string(body) != "real-encrypted-response" {
			t.Fatal("wrong response")
		}
		return e
	}
	if err = fetch(); err != nil {
		t.Fatal("initial traffic", err)
	}
	mu.Lock()
	blockedPort = clientAddr.Port
	mu.Unlock()
	// Engine, SOCKS and netstack are alive, yet this path is broken.
	httpClient.Timeout = 500 * time.Millisecond
	if fetch() == nil {
		t.Fatal("stale NAT mapping unexpectedly passed HTTP")
	}
	// This established stream stays open throughout the outage and key expiry.
	stream.SetDeadline(time.Now().Add(500 * time.Millisecond))
	stream.Write([]byte("queued-during-outage"))
	if _, err := stream.Read(make([]byte, 1)); err == nil {
		t.Fatal("outage did not affect old stream")
	}
	time.Sleep(5 * time.Second)
	originalNetstack := client.network
	if err = client.Rebind(0); err != nil {
		t.Fatal(err)
	}
	stream.SetDeadline(time.Now().Add(15 * time.Second))
	queued := make([]byte, len("queued-during-outage"))
	if _, err := io.ReadFull(stream, queued); err != nil {
		t.Fatal("queued data after repair", err)
	}
	if string(queued) != "queued-during-outage" {
		t.Fatal("queued data corrupted")
	}
	exchange("same-stream-after-repair")
	httpClient.Timeout = 10 * time.Second
	if err = fetch(); err != nil {
		t.Fatal("traffic did not recover", err)
	}
	if client.network != originalNetstack {
		t.Fatal("netstack was replaced")
	}
	stats, err := client.device.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(stats, "persistent_keepalive_interval=25\n") {
		t.Fatal("keepalive not applied to actual peer")
	}
	if strings.Contains(stats, "last_handshake_time_sec=0\n") || strings.Contains(stats, "rx_bytes=0\n") {
		t.Fatal("missing authenticated handshake/data counters")
	}
}
