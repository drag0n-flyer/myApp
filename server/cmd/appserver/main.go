// Command appserver is the chat server: it serves the public API, the admin
// listener, and the admin CLI.
//
//	appserver [serve]        run the server
//	appserver migrate        apply database migrations and exit
//	appserver <command...>   run an admin command, e.g. "invite create"
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/velixio/myApp/server/internal/app"
	"github.com/velixio/myApp/server/internal/config"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/db"
	"github.com/velixio/myApp/server/internal/mail"
)

const shutdownTimeout = 10 * time.Second

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	if err := run(ctx, os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "appserver:", err)
		stop()
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string) error {
	log := slog.New(slog.NewJSONHandler(os.Stderr, nil))

	cfg, policy, err := config.Load(os.Getenv, os.ReadFile)
	if err != nil {
		return err
	}
	pool, err := db.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer pool.Close()

	if len(args) == 1 && args[0] == "migrate" {
		if err := db.Migrate(ctx, pool); err != nil {
			return fmt.Errorf("migrate: %w", err)
		}
		return nil
	}

	mailer, err := mail.New(cfg.SMTPURL, cfg.MailFrom, log)
	if err != nil {
		return err
	}
	deps := core.Deps{
		DB:        pool,
		Config:    cfg,
		Policy:    policy,
		Log:       log,
		Now:       time.Now,
		Mail:      mailer,
		Directory: db.NewDirectory(pool),
	}
	serving := len(args) == 0 || (len(args) == 1 && args[0] == "serve")
	if serving {
		// Refuse to start with a missing or invalid escrow key.
		if err := app.Validate(deps); err != nil {
			return err
		}
	}
	a := app.New(deps)

	if serving {
		return serve(ctx, a, cfg, pool, log)
	}
	return a.Command(ctx, args, os.Stdout)
}

func serve(ctx context.Context, a *app.App, cfg config.Config, pool *pgxpool.Pool, log *slog.Logger) error {
	if err := db.Migrate(ctx, pool); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}

	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	public := newServer(cfg.Listen, a.Handler())
	admin := newServer(cfg.AdminListen, a.AdminHandler())

	var wg sync.WaitGroup
	wg.Go(func() { a.Run(runCtx) })

	errs := make(chan error, 2)
	for _, s := range []*http.Server{public, admin} {
		wg.Go(func() {
			log.Info("listening", "addr", s.Addr)
			if err := s.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				errs <- fmt.Errorf("listen on %s: %w", s.Addr, err)
			}
		})
	}

	var serveErr error
	select {
	case <-ctx.Done():
		log.Info("shutting down")
	case serveErr = <-errs:
	}

	// Stop background work and drain both listeners within the deadline.
	cancel()
	sctx, scancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer scancel()
	for _, s := range []*http.Server{public, admin} {
		if err := s.Shutdown(sctx); err != nil {
			_ = s.Close()
			serveErr = errors.Join(serveErr, fmt.Errorf("shutdown %s: %w", s.Addr, err))
		}
	}
	wg.Wait()
	a.Wait(sctx) // let detached work, such as sign-in emails, finish
	return serveErr
}

func newServer(addr string, h http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           h,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
}
