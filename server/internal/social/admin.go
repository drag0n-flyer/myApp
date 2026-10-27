package social

import (
	"context"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

// MarkReportHandled sets handled_at and handled_by on a report. Lane B (B1)
// fills it.
func (m *Module) MarkReportHandled(ctx context.Context, id, tokenID string) error {
	return httpx.Err("internal", "not built")
}
