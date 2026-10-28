package admin

import (
	"bytes"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

func TestServeLogsUnexpectedErrorsOnly(t *testing.T) {
	var buf bytes.Buffer
	log := slog.New(slog.NewJSONHandler(&buf, nil))
	run := func(err error) int {
		h := serve(func(http.ResponseWriter, *http.Request, Session) error { return err }, Session{}, log)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest("GET", "/admin/api/overview", nil))
		return rec.Code
	}
	if code := run(errors.New("db down: secret detail")); code != http.StatusInternalServerError {
		t.Fatalf("status = %d", code)
	}
	out := buf.String()
	if !strings.Contains(out, "admin request failed") || !strings.Contains(out, "/admin/api/overview") {
		t.Errorf("unexpected error not logged: %s", out)
	}
	if strings.Contains(out, "secret detail") {
		t.Errorf("log carries the error text: %s", out)
	}
	buf.Reset()
	run(httpx.Err("not_found", "no such account"))
	if buf.Len() != 0 {
		t.Errorf("an API error was logged: %s", buf.String())
	}
}
