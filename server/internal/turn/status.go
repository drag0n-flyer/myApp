package turn

import "time"

// RelayStatus is one relay's health as the prober last saw it.
type RelayStatus struct {
	Host, Region string
	Healthy      bool
	RTT          time.Duration
}

// Relays lists every configured relay in APP_RELAYS order with the prober's
// current state. RTT is 0 until a probe has succeeded.
func (m *Module) Relays() []RelayStatus { return m.prober.status() }
