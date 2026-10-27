package realtime

// ConnectionCount is the number of live WebSocket connections.
func (m *Module) ConnectionCount() int { return m.hub.count() }
