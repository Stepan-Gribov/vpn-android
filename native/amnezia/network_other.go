//go:build !android

package main

import awgconn "github.com/amnezia-vpn/amneziawg-go/v3/conn"

// Local synthetic peer tests do not use Android network handles.
func bindPhysicalNetwork(_ awgconn.Bind, _ uint64) error { return nil }
