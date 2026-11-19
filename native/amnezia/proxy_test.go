package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"golang.org/x/net/dns/dnsmessage"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/net/proxy"
)

func amneziaTestKey(t *testing.T) (string, string) {
	t.Helper()
	private := make([]byte, 32)
	if _, err := rand.Read(private); err != nil {
		t.Fatal(err)
	}
	public, err := curve25519.X25519(private, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(private), hex.EncodeToString(public)
}
func amneziaHTTPClient(t *testing.T, p *amneziaProxy) *http.Client {
	t.Helper()
	dialer, err := proxy.SOCKS5("tcp", p.listener.Addr().String(), nil, &net.Dialer{Timeout: 5 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	client := &http.Client{Timeout: 20 * time.Second, Transport: &http.Transport{DisableKeepAlives: true, DialContext: dialer.(proxy.ContextDialer).DialContext}}
	t.Cleanup(client.CloseIdleConnections)
	return client
}
func TestAmneziaWG3EncryptedTCPUDPAndRestart(t *testing.T) {
	privateA, publicA := amneziaTestKey(t)
	privateB, publicB := amneziaTestKey(t)
	protection := strings.Repeat("ab", 32)
	parameters := "jc=3\njmin=30\njmax=60\ns1=17\ns2=19\ns3=13\ns4=15\nh1=150-200\nh2=250-300\nh3=350-400\nh4=450-500\nheader_protection_key=" + protection + "\nrandom_trailers=true\ndisable_cookies=true\n"
	cfg := func(private, public, address string) amneziaConfig {
		return amneziaConfig{Addresses: []string{address}, DNS: []string{"10.80.0.1"}, MTU: 1280, UAPI: "private_key=" + private + "\n" + parameters + "replace_peers=true\npublic_key=" + public + "\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\npersistent_keepalive_interval=1\n"}
	}
	server, err := newAmneziaProxy(cfg(privateA, publicB, "10.80.0.1"), "127.0.0.1:0")
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
		if strings.HasPrefix(line, "listen_port=") {
			port = strings.TrimPrefix(line, "listen_port=")
		}
	}
	if port == "" {
		t.Fatal("server UDP port missing")
	}
	listener, err := server.network.ListenTCPAddrPort(netip.MustParseAddrPort("10.80.0.1:80"))
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	httpServer := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "encrypted-awg3") })}
	go httpServer.Serve(listener)
	defer httpServer.Close()
	udp, err := server.network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.80.0.1"), Port: 53})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	go func() {
		buffer := make([]byte, 4096)
		for {
			n, source, err := udp.ReadFrom(buffer)
			if err != nil {
				return
			}
			udp.WriteTo(buffer[:n], source)
		}
	}()
	dnsFixture, err := server.network.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.80.0.1"), Port: 5353})
	if err != nil {
		t.Fatal(err)
	}
	defer dnsFixture.Close()
	go func() {
		buffer := make([]byte, 4096)
		for {
			n, source, err := dnsFixture.ReadFrom(buffer)
			if err != nil {
				return
			}
			var query dnsmessage.Message
			if query.Unpack(buffer[:n]) != nil {
				continue
			}
			response := dnsmessage.Message{Header: dnsmessage.Header{ID: query.ID, Response: true, RecursionAvailable: true}, Questions: query.Questions}
			for _, question := range query.Questions {
				if question.Type == dnsmessage.TypeA {
					response.Answers = append(response.Answers, dnsmessage.Resource{
						Header: dnsmessage.ResourceHeader{Name: question.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
						Body:   &dnsmessage.AResource{A: [4]byte{10, 80, 0, 1}},
					})
				}
			}
			packet, err := response.Pack()
			if err == nil {
				dnsFixture.WriteTo(packet, source)
			}
		}
	}()
	for attempt := 0; attempt < 2; attempt++ {
		// The server rejects same-peer initiations within 20 ms.
		if attempt > 0 {
			time.Sleep(50 * time.Millisecond)
		}
		clientCfg := cfg(privateB, publicA, "10.80.0.2")
		clientCfg.DNS = []string{"10.80.0.1:5353"}
		clientCfg.UAPI += "endpoint=127.0.0.1:" + port + "\n"
		client, err := newAmneziaProxy(clientCfg, "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		func() {
			defer client.Close()
			// Each newly created client netstack may reuse source TCP ports.
			// Avoid colliding with the previous client's lingering server flow.
			echoAddress := netip.MustParseAddrPort(fmt.Sprintf("10.80.0.1:%d", 81+attempt))
			echoListener, err := server.network.ListenTCPAddrPort(echoAddress)
			if err != nil {
				t.Fatal(err)
			}
			defer echoListener.Close()
			go func() {
				for {
					connection, err := echoListener.Accept()
					if err != nil {
						return
					}
					go func(connection net.Conn) { defer connection.Close(); io.Copy(connection, connection) }(connection)
				}
			}()
			// The first application request must establish the encrypted session.
			// No IP warm-up, ping, or manual handshake is allowed before DNS.
			started := time.Now()
			named, err := amneziaHTTPClient(t, client).Get("http://fixture.test/")
			if err != nil {
				t.Fatal("cold DNS request failed", err)
			}
			data, _ := io.ReadAll(named.Body)
			named.Body.Close()
			if string(data) != "encrypted-awg3" {
				t.Fatal("named response corrupted")
			}
			if time.Since(started) > 3*time.Second {
				t.Fatal("cold request unexpectedly stalled", time.Since(started))
			}

			control, err := net.DialTimeout("tcp", client.listener.Addr().String(), 3*time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer control.Close()
			control.SetDeadline(time.Now().Add(5 * time.Second))
			control.Write([]byte{5, 1, 0})
			var greeting [2]byte
			if _, err := io.ReadFull(control, greeting[:]); err != nil {
				t.Fatal(err)
			}
			control.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
			var reply [4]byte
			if _, err := io.ReadFull(control, reply[:]); err != nil || reply[1] != 0 {
				t.Fatal("UDP association failed")
			}
			target, err := readAmneziaAddress(control, reply[3])
			if err != nil {
				t.Fatal(err)
			}
			local, err := net.DialTimeout("udp", target, 3*time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer local.Close()
			local.SetDeadline(time.Now().Add(5 * time.Second))
			packet := append([]byte{0, 0, 0, 1, 10, 80, 0, 1, 0, 53}, []byte("udp-dns-fixture")...)
			local.Write(packet)
			buffer := make([]byte, 4096)
			n, err := local.Read(buffer)
			if err != nil {
				t.Fatal(err)
			}
			if string(buffer[10:n]) != "udp-dns-fixture" {
				t.Fatal("UDP reply corrupted")
			}
			dialer, err := proxy.SOCKS5("tcp", client.listener.Addr().String(), nil, &net.Dialer{Timeout: 5 * time.Second})
			if err != nil {
				t.Fatal(err)
			}
			stream, err := dialer.Dial("tcp", echoAddress.String())
			if err != nil {
				t.Fatal(err)
			}
			defer stream.Close()
			echo := func(message string) {
				stream.SetDeadline(time.Now().Add(5 * time.Second))
				if _, err := stream.Write([]byte(message)); err != nil {
					t.Fatal(err)
				}
				data := make([]byte, len(message))
				if _, err := io.ReadFull(stream, data); err != nil {
					t.Fatal("persistent TCP stream failed", message, err)
				}
				if string(data) != message {
					t.Fatal("persistent TCP stream corrupted")
				}
			}
			echo("before-network-change")
			if err := client.Rebind(0); err != nil {
				t.Fatal(err)
			}
			echo("after-network-change")
			local.SetDeadline(time.Now().Add(5 * time.Second))
			local.Write(packet)
			n, err = local.Read(buffer)
			if err != nil || string(buffer[10:n]) != "udp-dns-fixture" {
				t.Fatal("existing UDP association did not survive socket rebind", err)
			}
			response, err := amneziaHTTPClient(t, client).Get("http://fixture.test/")
			if err != nil {
				t.Fatal("TCP failed after rebind", err)
			}
			body, _ := io.ReadAll(response.Body)
			response.Body.Close()
			if string(body) != "encrypted-awg3" {
				t.Fatal("TCP response corrupted after rebind")
			}
			t.Logf("cycle %d: AWG3 TCP and UDP transferred", attempt+1)
		}()
	}
}

func TestAmneziaRejectsUnsafeRuntimeSettings(t *testing.T) {
	config := amneziaConfig{Addresses: []string{"10.0.0.2"}, DNS: []string{"1.1.1.1"}, MTU: 1280, UAPI: "private_key=bad\npost_up=command\n"}
	if err := config.validate(); err == nil {
		t.Fatal("unknown setting accepted")
	}
	config.UAPI = "private_key=bad\n"
	if p, err := newAmneziaProxy(config, "127.0.0.1:0"); err == nil {
		p.Close()
		t.Fatal("invalid key accepted")
	}
}
