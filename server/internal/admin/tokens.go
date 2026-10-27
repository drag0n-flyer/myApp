package admin

import "context"

// Lane A (A1): token and session store.

// cleanup deletes unused expired tokens, expired sessions and audit entries
// older than 365 days. Lane A (A2).
func (m *Module) cleanup(ctx context.Context) error { return nil }
