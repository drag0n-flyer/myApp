package push

// Stats are push counters since start.
type Stats struct {
	Enabled      bool
	Sent, Failed uint64
}

// Stats returns the counters. Sent counts gateway answers 2xx; Failed counts
// every other outcome of a gateway send attempt.
func (m *Module) Stats() Stats {
	return Stats{
		Enabled: m.d.Config.PushGatewayURL != "",
		Sent:    m.sent.Load(),
		Failed:  m.failed.Load(),
	}
}
