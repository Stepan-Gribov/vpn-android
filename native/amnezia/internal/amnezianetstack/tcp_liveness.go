// SPDX-License-Identifier: MIT
package netstack

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// The proxy acknowledges the application's local TCP separately from the
// encrypted remote TCP leg. Bound unacknowledged remote data so a dead leg
// propagates closure back to the app instead of hiding behind a healthy probe.
// This is a TCP retransmission timeout, NOT an application read/idle deadline.
const tcpUnacknowledgedTimeout = 60 * time.Second

func (tnet *Net) dialTCP(ctx context.Context, address netip.AddrPort) (*gonet.TCPConn, error) {
	remote, protocol := convertToFullAddr(address)
	var queue waiter.Queue
	endpoint, err := tnet.stack.NewEndpoint(tcp.ProtocolNumber, protocol, &queue)
	if err != nil {
		return nil, fmt.Errorf("TCP endpoint: %s", err)
	}
	owned := true
	defer func() {
		if owned {
			endpoint.Close()
		}
	}()
	timeout := tcpip.TCPUserTimeoutOption(tcpUnacknowledgedTimeout)
	idle := tcpip.KeepaliveIdleOption(2 * time.Minute)
	interval := tcpip.KeepaliveIntervalOption(30 * time.Second)
	for _, option := range []tcpip.SettableSocketOption{&timeout, &idle, &interval} {
		if err = endpoint.SetSockOpt(option); err != nil {
			return nil, fmt.Errorf("TCP liveness: %s", err)
		}
	}
	if err = endpoint.SetSockOptInt(tcpip.KeepaliveCountOption, 3); err != nil {
		return nil, fmt.Errorf("TCP keepalive: %s", err)
	}
	endpoint.SocketOptions().SetKeepAlive(true)
	entry, ready := waiter.NewChannelEntry(waiter.WritableEvents)
	queue.EventRegister(&entry)
	defer queue.EventUnregister(&entry)
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	err = endpoint.Connect(remote)
	if _, pending := err.(*tcpip.ErrConnectStarted); pending {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-ready:
		}
		err = endpoint.LastError()
	}
	if err != nil {
		return nil, &net.OpError{Op: "connect", Net: "tcp", Addr: net.TCPAddrFromAddrPort(address), Err: fmt.Errorf("%s", err)}
	}
	owned = false
	return gonet.NewTCPConn(&queue, endpoint), nil
}
