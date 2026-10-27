package turn

import "time"

// RelayStatus is one relay's health as the prober last saw it.
type RelayStatus struct {
	Host, Region string
	Healthy      bool
	RTT          time.Duration
}

// Relays lists every configured relay. Lane B (B1) fills it.
func (m *Module) Relays() []RelayStatus { return nil }
