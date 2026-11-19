package netstack

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/tun"
)

// Drop one established flow (as a forgotten NAT mapping would), while a second
// flow on the same stack remains healthy. A fresh probe must not hide this loss.
func TestLostTCPFlowFailsWithoutClosingHealthyFlow(t *testing.T) {
	a, client, err := CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.90.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	b, server, err := CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.90.0.2")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	var blocked atomic.Bool
	pump := func(from, to tun.Device) {
		packet := make([]byte, 1500)
		sizes := []int{0}
		for {
			n, e := from.Read([][]byte{packet}, sizes, 0)
			if e != nil {
				return
			}
			if n == 0 {
				continue
			}
			p := packet[:sizes[0]]
			if blocked.Load() && len(p) >= 24 && p[0]>>4 == 4 && p[9] == 6 {
				offset := int(p[0]&15) * 4
				if len(p) >= offset+4 && (binary.BigEndian.Uint16(p[offset:]) == 9001 || binary.BigEndian.Uint16(p[offset+2:]) == 9001) {
					continue
				}
			}
			to.Write([][]byte{p}, 0)
		}
	}
	go pump(a, b)
	go pump(b, a)
	for _, port := range []uint16{9001, 9002, 9003} {
		listener, e := server.ListenTCPAddrPort(netip.AddrPortFrom(netip.MustParseAddr("10.90.0.2"), port))
		if e != nil {
			t.Fatal(e)
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
	}
	dial := func(port uint16) net.Conn {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		c, e := client.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(netip.MustParseAddr("10.90.0.2"), port))
		if e != nil {
			t.Fatal(e)
		}
		return c
	}
	bad, good := dial(9001), dial(9002)
	idle := dial(9003)
	defer idle.Close()
	defer bad.Close()
	defer good.Close()
	echo := func(c net.Conn) {
		c.SetDeadline(time.Now().Add(time.Second))
		if _, e := c.Write([]byte("ok")); e != nil {
			t.Fatal(e)
		}
		if _, e := io.ReadFull(c, make([]byte, 2)); e != nil {
			t.Fatal(e)
		}
		c.SetDeadline(time.Time{})
	}
	echo(bad)
	echo(good)
	blocked.Store(true)
	if _, err = bad.Write([]byte("unacknowledged")); err != nil {
		t.Fatal(err)
	}
	failed := make(chan error, 1)
	go func() { _, e := bad.Read(make([]byte, 1)); failed <- e }()
	// No read deadline on the bad connection: the stack itself must terminate it.
	timer := time.NewTimer(70 * time.Second)
	defer timer.Stop()
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		select {
		case e := <-failed:
			if e == nil {
				t.Fatal("lost flow did not fail")
			}
			echo(good)
			echo(idle)
			t.Log("dead flow failed; original healthy and idle flows retained")
			return
		case <-tick.C:
			echo(good)
		case <-timer.C:
			t.Fatal("lost TCP flow still hangs after 70 seconds although the healthy flow works")
		}
	}
}
