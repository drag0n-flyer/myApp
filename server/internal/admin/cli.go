package admin

import (
	"context"
	"errors"
	"io"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

// Commands returns the admin CLI commands. Lane A (A2) fills them.
func (m *Module) Commands() map[string]core.Command {
	return map[string]core.Command{
		"admin token create": m.cmdTokenCreate,
		"admin sessions end": m.cmdSessionsEnd,
	}
}

func (m *Module) cmdTokenCreate(ctx context.Context, args []string, out io.Writer) error {
	return errors.New("not built")
}

func (m *Module) cmdSessionsEnd(ctx context.Context, args []string, out io.Writer) error {
	return errors.New("not built")
}
