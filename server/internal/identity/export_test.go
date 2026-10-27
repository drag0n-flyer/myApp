package identity

// WaitBackground waits for detached work started by handlers, such as the
// email sign-in code send.
func (m *Module) WaitBackground() { m.bg.Wait() }
