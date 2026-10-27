package serverinfo

import (
	"context"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

// Document returns the bytes GET /v1/server sends. Lane B (B1) fills it.
func (m *Module) Document(ctx context.Context) ([]byte, error) {
	return nil, httpx.Err("internal", "not built")
}
