// Command pushgw is the push gateway. It holds the APNs credentials, checks
// requests from registered servers and forwards pushes to APNs.
//
// Configuration comes only from the environment:
//
//	PUSHGW_LISTEN, PUSHGW_DATABASE_URL, PUSHGW_HPKE_KEY_FILE,
//	PUSHGW_APNS_KEY_FILE, PUSHGW_APNS_KEY_ID, PUSHGW_APNS_TEAM_ID,
//	PUSHGW_BUNDLE_IDS (comma separated),
//	PUSHGW_APNS_URL (optional; overrides both APNs hosts, for tests),
//	PUSHGW_FCM_PROJECT_ID, PUSHGW_FCM_KEY_FILE, PUSHGW_ANDROID_APP_IDS (comma
//	separated; optional, but all three or none; they enable Android handles),
//	PUSHGW_FCM_URL (optional; overrides the FCM host, for tests),
//	PUSHGW_TRUST_PROXY (optional; "true" keys per-IP limits on X-Forwarded-For).
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/db"
	"github.com/drag0n-flyer/myApp/server/internal/pushgw"
)

const shutdownTimeout = 10 * time.Second

func main() {
	if err := run(os.Getenv); err != nil {
		fmt.Fprintln(os.Stderr, "pushgw:", err)
		os.Exit(1)
	}
}

func run(getenv func(string) string) error {
	var missing []string
	need := func(name string) string {
		v := getenv(name)
		if v == "" {
			missing = append(missing, name)
		}
		return v
	}
	listen := need("PUSHGW_LISTEN")
	dbURL := need("PUSHGW_DATABASE_URL")
	hpkeFile := need("PUSHGW_HPKE_KEY_FILE")
	apnsFile := need("PUSHGW_APNS_KEY_FILE")
	keyID := need("PUSHGW_APNS_KEY_ID")
	teamID := need("PUSHGW_APNS_TEAM_ID")
	bundles := splitList(need("PUSHGW_BUNDLE_IDS"))
	if len(missing) > 0 {
		return fmt.Errorf("missing environment variables: %s", strings.Join(missing, ", "))
	}
	trustProxy := false
	if v := getenv("PUSHGW_TRUST_PROXY"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			return errors.New("PUSHGW_TRUST_PROXY must be true or false")
		}
		trustProxy = b
	}

	hpkePEM, err := os.ReadFile(hpkeFile)
	if err != nil {
		return fmt.Errorf("read PUSHGW_HPKE_KEY_FILE: %w", err)
	}
	hpkeKey, hpkePub, err := pushgw.LoadHPKEKey(hpkePEM)
	if err != nil {
		return err
	}
	apnsPEM, err := os.ReadFile(apnsFile)
	if err != nil {
		return fmt.Errorf("read PUSHGW_APNS_KEY_FILE: %w", err)
	}
	apns, err := pushgw.NewAPNsClient(apnsPEM, keyID, teamID, getenv("PUSHGW_APNS_URL"), nil, time.Now)
	if err != nil {
		return err
	}

	var fcm pushgw.FCM
	fcmProject, fcmFile, androidIDs := getenv("PUSHGW_FCM_PROJECT_ID"), getenv("PUSHGW_FCM_KEY_FILE"), splitList(getenv("PUSHGW_ANDROID_APP_IDS"))
	if fcmProject != "" || fcmFile != "" || len(androidIDs) > 0 {
		if fcmProject == "" || fcmFile == "" || len(androidIDs) == 0 {
			return errors.New("PUSHGW_FCM_PROJECT_ID, PUSHGW_FCM_KEY_FILE and PUSHGW_ANDROID_APP_IDS must be set together")
		}
		fcmJSON, err := os.ReadFile(fcmFile)
		if err != nil {
			return fmt.Errorf("read PUSHGW_FCM_KEY_FILE: %w", err)
		}
		fcm, err = pushgw.NewFCMClient(fcmProject, fcmJSON, getenv("PUSHGW_FCM_URL"), nil, time.Now)
		if err != nil {
			return err
		}
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	log := slog.New(slog.NewJSONHandler(os.Stderr, nil))

	pool, err := db.Open(ctx, dbURL)
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	defer pool.Close()
	if err := pushgw.Migrate(ctx, pool); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}

	handler := pushgw.NewGateway(pool, pushgw.GatewayConfig{
		HPKEKey: hpkeKey, HPKEPublic: hpkePub, BundleIDs: bundles, FCM: fcm, AndroidAppIDs: androidIDs,
		Now: time.Now, TrustProxy: trustProxy,
	}, apns, log)
	srv := &http.Server{
		Addr:              listen,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	errs := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", listen)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errs <- fmt.Errorf("listen on %s: %w", listen, err)
		}
		close(errs)
	}()
	select {
	case err := <-errs:
		return err
	case <-ctx.Done():
	}
	shutCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	return srv.Shutdown(shutCtx)
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
