// Package tun2socks bridges Android VpnService TUN fd to a SOCKS5 proxy.
// Exposed via gomobile bind as Tun2socks package for Android.
package tun2socks

import (
    "fmt"
    "github.com/xjasonlyu/tun2socks/v2/engine"
)

var started bool

// Start routes all traffic from the TUN fd through the SOCKS5 proxy.
// tunFd:  file descriptor integer from VpnService.establish()
// proxy:  SOCKS5 address, e.g. "socks5://127.0.0.1:9000"
func Start(tunFd int64, proxy string) error {
    if started {
        Stop()
    }
    key := &engine.Key{
        Proxy:    proxy,
        Device:   fmt.Sprintf("fd://%d", tunFd),
        LogLevel: "warning",
        UDPTimeout: 30,
    }
    engine.Insert(key)
    if err := engine.Start(); err != nil {
        return err
    }
    started = true
    return nil
}

// Stop stops the tun2socks engine.
func Stop() {
    if started {
        engine.Stop()
        started = false
    }
}

// Version returns the tun2socks version string.
func Version() string {
    return "tun2socks/2.5.2"
}
