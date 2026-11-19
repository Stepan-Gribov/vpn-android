package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/signal"
	"runtime/debug"
	"syscall"
)

func main() { runAmnezia(amneziaSOCKSAddress) }

func runAmnezia(listen string) {
	debug.SetMemoryLimit(128 * 1024 * 1024)
	debug.SetGCPercent(50)
	fmt.Printf("TNDTUN_AMNEZIA_PID %d\n", os.Getpid())
	var config amneziaConfig
	commands := json.NewDecoder(os.Stdin)
	if err := commands.Decode(&config); err != nil {
		fmt.Fprintln(os.Stderr, "Invalid Amnezia configuration")
		os.Exit(1)
	}
	proxy, err := newAmneziaProxy(config, listen)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Println("TNDTUN_AMNEZIA_READY")
	ownerClosed := make(chan struct{})
	go func() {
		defer close(ownerClosed)
		for {
			var command struct {
				Action  string `json:"action"`
				ID      uint64 `json:"id"`
				Network uint64 `json:"network"`
			}
			if commands.Decode(&command) != nil {
				return
			}
			if command.Action == "rebind" || command.Action == "reset" {
				repair := proxy.Rebind
				if command.Action == "reset" {
					repair = proxy.Reset
				}
				if err := repair(command.Network); err != nil {
					fmt.Fprintf(os.Stderr, "TNDTUN_AMNEZIA_REBIND_FAILED %d\n", command.ID)
				} else {
					fmt.Printf("TNDTUN_AMNEZIA_REBOUND %d\n", command.ID)
				}
			}
		}
	}()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	select {
	case <-signals:
	case <-ownerClosed:
		// Android may kill the service process without onDestroy. Its pipe EOF
		// must retire this helper and port 9004 before a sticky service restart.
	case <-proxy.device.Wait():
		// Device failure must terminate the helper, otherwise Android sees a
		// live SOCKS process and keeps advertising a nonfunctional VPN.
		fmt.Fprintln(os.Stderr, "TNDTUN_AMNEZIA_ENGINE_STOPPED")
	}
	proxy.Close()
}
