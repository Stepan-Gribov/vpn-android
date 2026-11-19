package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"testing"
	"time"
)

func TestAmneziaHelperOwnerEOF(t *testing.T) {
	if os.Getenv("TNDTUN_TEST_HELPER_CHILD") == "1" {
		runAmnezia("127.0.0.1:0")
		os.Exit(0)
	}
	cmd := exec.Command(os.Args[0], "-test.run=^TestAmneziaHelperOwnerEOF$")
	cmd.Env = append(os.Environ(), "TNDTUN_TEST_HELPER_CHILD=1")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stderr = os.Stderr
	if err = cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer cmd.Process.Kill()
	private, _ := amneziaTestKey(t)
	if err = json.NewEncoder(stdin).Encode(amneziaConfig{Addresses: []string{"10.99.0.1"}, MTU: 1280, UAPI: "private_key=" + private + "\n"}); err != nil {
		t.Fatal(err)
	}
	ready := make(chan bool, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		ready <- scanner.Scan() && scanner.Text() == fmt.Sprintf("TNDTUN_AMNEZIA_PID %d", cmd.Process.Pid) && scanner.Scan() && scanner.Text() == "TNDTUN_AMNEZIA_READY"
	}()
	select {
	case ok := <-ready:
		if !ok {
			t.Fatal("helper not ready")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("helper startup timeout")
	}
	// A dead Android service closes this pipe even without calling onDestroy.
	stdin.Close()
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("orphan helper retained its listener after owner EOF")
	}
}
