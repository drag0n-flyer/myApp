package social

import (
	"context"
	"regexp"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

var uuidRE = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// MarkReportHandled sets handled_at and handled_by (an admin user id) on an
// open report. An
// unknown id is core.ErrNotFound; a report that is already handled is left
// unchanged and is not an error.
func (m *Module) MarkReportHandled(ctx context.Context, id, adminUserID string) error {
	if !uuidRE.MatchString(id) || !uuidRE.MatchString(adminUserID) {
		return core.ErrNotFound
	}
	tag, err := m.d.DB.Exec(ctx,
		`update reports set handled_at = $2, handled_by = $3 where id = $1 and handled_at is null`,
		id, m.d.Now(), adminUserID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 1 {
		return nil
	}
	var exists bool
	if err := m.d.DB.QueryRow(ctx, `select exists (select 1 from reports where id = $1)`, id).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return core.ErrNotFound
	}
	return nil
}
