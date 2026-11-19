package netstack

import (
	"bytes"
	"net/netip"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// Packet production must not wait for the encryption reader while holding
// transport locks. In particular that reader can be stopping during migration.
func TestPacketQueueDoesNotBlockProducerWithoutReader(t *testing.T) {
	d, _, err := CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.0.0.2")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	dev := d.(*netTun)
	packet := []byte{0x45, 0, 0, 20, 1, 2, 3, 4}
	done := make(chan struct{})
	go func() {
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(packet)})
		defer pkt.DecRef()
		var list stack.PacketBufferList
		list.PushBack(pkt)
		dev.ep.WritePackets(list)
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(250 * time.Millisecond):
		// Release the old synchronous notification before reporting regression.
		buf := make([]byte, 1280)
		dev.Read([][]byte{buf}, []int{0}, 0)
		<-done
		dev.Close()
		t.Fatal("packet producer blocked until TUN reader ran")
	}
	buf := make([]byte, 1280)
	sizes := []int{0}
	if n, err := dev.Read([][]byte{buf}, sizes, 0); err != nil || n != 1 || !bytes.Equal(buf[:sizes[0]], packet) {
		t.Fatalf("queued packet corrupted: n=%d error=%v", n, err)
	}
	dev.Close()
}

func TestCloseUnblocksReaderAndIsIdempotent(t *testing.T) {
	d, _, err := CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.0.0.2")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { defer close(done); d.Read([][]byte{make([]byte, 1280)}, []int{0}, 0) }()
	var closing sync.WaitGroup
	for i := 0; i < 4; i++ {
		closing.Add(1)
		go func() { defer closing.Done(); d.Close() }()
	}
	closing.Wait()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("reader did not stop")
	}
}
