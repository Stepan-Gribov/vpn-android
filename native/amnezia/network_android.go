package main

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
#include <errno.h>
static int tnd_bind_network(unsigned long long network, int fd) {
    return android_setsocknetwork((net_handle_t)network, fd) == 0 ? 0 : errno;
}
*/
import "C"

import (
	"fmt"
	awgconn "github.com/amnezia-vpn/amneziawg-go/v3/conn"
)

func bindPhysicalNetwork(bind awgconn.Bind, network uint64) error {
	if network == 0 {
		return nil
	}
	sockets, ok := bind.(awgconn.PeekLookAtSocketFd)
	if !ok {
		return fmt.Errorf("Amnezia sockets unavailable")
	}
	bound := false
	for _, peek := range []func() (int, error){sockets.PeekLookAtSocketFd4, sockets.PeekLookAtSocketFd6} {
		fd, err := peek()
		if err != nil {
			continue
		}
		if code := C.tnd_bind_network(C.ulonglong(network), C.int(fd)); code != 0 {
			return fmt.Errorf("Android network binding failed: errno %d", int(code))
		}
		bound = true
	}
	if !bound {
		return fmt.Errorf("Amnezia sockets unavailable")
	}
	return nil
}
