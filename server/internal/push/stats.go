package push

// Stats are push counters since start.
type Stats struct {
	Enabled      bool
	Sent, Failed uint64
}

// Stats returns the counters. Lane B (B1) fills it.
func (m *Module) Stats() Stats { return Stats{} }
