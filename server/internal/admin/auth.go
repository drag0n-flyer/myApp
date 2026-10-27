package admin

import (
	"net/http"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

func notBuilt() error { return httpx.Err("internal", "not built") }

// Lane A (A3): login, logout and session.

func (m *Module) login(w http.ResponseWriter, r *http.Request, s Session) error   { return notBuilt() }
func (m *Module) logout(w http.ResponseWriter, r *http.Request, s Session) error  { return notBuilt() }
func (m *Module) session(w http.ResponseWriter, r *http.Request, s Session) error { return notBuilt() }
