// Package app wires the server's modules into one http.Handler and dispatches
// admin CLI commands (spec section 4).
package app

import (
	"context"
	"expvar"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/velixio/myApp/server/internal/attachments"
	"github.com/velixio/myApp/server/internal/calls"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/delivery"
	"github.com/velixio/myApp/server/internal/httpx"
	"github.com/velixio/myApp/server/internal/identity"
	"github.com/velixio/myApp/server/internal/push"
	"github.com/velixio/myApp/server/internal/realtime"
	"github.com/velixio/myApp/server/internal/serverinfo"
	"github.com/velixio/myApp/server/internal/social"
	"github.com/velixio/myApp/server/internal/turn"
)

// readyTimeout bounds the database ping behind /readyz.
const readyTimeout = 2 * time.Second

// App is the wired server.
type App struct {
	deps     core.Deps
	router   *httpx.Router
	modules  []any // each has Routes; some have Run and Commands
	commands map[string]core.Command
}

// New wires the modules in dependency order and registers their routes. d.Blocks,
// d.Push, d.Notify and d.Live are filled in here from the modules that provide them.
//
// Modules are discovered by method, so a module that gains Run or Commands is
// picked up without changing this file.
func New(d core.Deps) *App {
	if d.Log == nil {
		d.Log = slog.New(slog.DiscardHandler)
	}
	soc := social.New(d)
	d.Blocks = soc
	pu := push.New(d)
	d.Push = pu
	rt := realtime.New(d)
	d.Notify = rt
	d.Live = rt

	id := identity.New(d)
	dl := delivery.New(d)
	cl := calls.New(d, rt)
	tn := turn.New(d)
	at := attachments.New(d)
	si := serverinfo.New(d)

	a := &App{
		deps:     d,
		router:   httpx.NewRouter(id, d.Log),
		modules:  []any{soc, pu, rt, id, dl, cl, tn, at, si},
		commands: map[string]core.Command{},
	}
	for _, m := range a.modules {
		if r, ok := m.(interface{ Routes(*httpx.Router) }); ok {
			r.Routes(a.router)
		}
		if c, ok := m.(interface {
			Commands() map[string]core.Command
		}); ok {
			for name, cmd := range c.Commands() {
				if _, dup := a.commands[name]; dup {
					panic(fmt.Sprintf("app: command %q is defined by two modules", name))
				}
				a.commands[name] = cmd
			}
		}
	}
	return a
}

// Handler returns the public HTTP handler.
func (a *App) Handler() http.Handler { return a.router }

// AdminHandler returns the handler for the admin listener: /healthz, /readyz
// and /debug/vars. It must not be exposed publicly.
func (a *App) AdminHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = io.WriteString(w, "ok\n")
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		ctx, cancel := context.WithTimeout(r.Context(), readyTimeout)
		defer cancel()
		if a.deps.DB == nil || a.deps.DB.Ping(ctx) != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = io.WriteString(w, "not ready\n")
			return
		}
		_, _ = io.WriteString(w, "ok\n")
	})
	mux.Handle("GET /debug/vars", expvar.Handler())
	return mux
}

// Run starts every module's Run and returns when ctx is done and all of them
// have returned. Each Run must return promptly once ctx is cancelled.
func (a *App) Run(ctx context.Context) {
	var wg sync.WaitGroup
	for _, m := range a.modules {
		if r, ok := m.(interface{ Run(context.Context) }); ok {
			wg.Go(func() { r.Run(ctx) })
		}
	}
	wg.Wait()
}

// Command runs the admin subcommand named by the first two words of args (or
// the first word, for a one-word command), giving it the remaining words. It
// returns "unknown command: <args>" if no module provides one.
func (a *App) Command(ctx context.Context, args []string, out io.Writer) error {
	for n := min(2, len(args)); n >= 1; n-- {
		if cmd, ok := a.commands[strings.Join(args[:n], " ")]; ok {
			return cmd(ctx, args[n:], out)
		}
	}
	return fmt.Errorf("unknown command: %s", strings.Join(args, " "))
}
