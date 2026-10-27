package realtime

// ConnectionCount is the number of live WebSocket connections. Lane B (B1)
// fills it.
func (m *Module) ConnectionCount() int { return 0 }
