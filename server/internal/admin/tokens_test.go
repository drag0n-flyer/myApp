package admin

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func newTestModule(t *testing.T, opts ...func(*core.Deps)) (*Module, *testkit.Fakes) {
	t.Helper()
	d, f := testkit.NewDeps(t, opts...)
	return New(d, Sources{Started: d.Now()}), f
}

func runCmd(t *testing.T, m *Module, name string, args ...string) (string, error) {
	t.Helper()
	var out bytes.Buffer
	err := m.Commands()[name](context.Background(), args, &out)
	return out.String(), err
}

func TestTokenCreatePrintsOnce(t *testing.T) {
	m, _ := newTestModule(t)
	out, err := runCmd(t, m, "admin token create")
	if err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(`^adm_[A-Za-z0-9_-]{43}\n$`).MatchString(out) {
		t.Errorf("output = %q", out)
	}
}

func TestTokenStoredHashed(t *testing.T) {
	m, _ := newTestModule(t)
	out, err := runCmd(t, m, "admin token create", "--label", "laptop")
	if err != nil {
		t.Fatal(err)
	}
	token := strings.TrimSpace(out)
	var hash []byte
	if err := m.d.DB.QueryRow(context.Background(), `select token_hash from admin_tokens`).Scan(&hash); err != nil {
		t.Fatal(err)
	}
	want := sha256.Sum256([]byte(token))
	if !bytes.Equal(hash, want[:]) {
		t.Error("token_hash is not sha256(token)")
	}
	var n int
	err = m.d.DB.QueryRow(context.Background(),
		`select count(*) from admin_tokens t where t::text like '%' || $1 || '%'`, token).Scan(&n)
	if err != nil || n != 0 {
		t.Errorf("token text found in a row: n=%d err=%v", n, err)
	}
}

func TestTokenFlags(t *testing.T) {
	m, f := newTestModule(t)
	for _, args := range [][]string{
		{"--minutes", "0"},
		{"--minutes", "61"},
		{"--label", strings.Repeat("a", 65)},
		{"--label", "bad\nlabel"},
		{"--bogus"},
		{"extra"},
	} {
		if out, err := runCmd(t, m, "admin token create", args...); err == nil || out != "" {
			t.Errorf("%v: out %q err %v, want error and no output", args, out, err)
		}
	}
	var n int
	_ = m.d.DB.QueryRow(context.Background(), `select count(*) from admin_tokens`).Scan(&n)
	if n != 0 {
		t.Errorf("%d tokens created by failing commands", n)
	}
	if _, err := runCmd(t, m, "admin token create", "--minutes", "60", "--label", strings.Repeat("a", 64)); err != nil {
		t.Fatal(err)
	}
	var exp time.Time
	if err := m.d.DB.QueryRow(context.Background(), `select expires_at from admin_tokens`).Scan(&exp); err != nil {
		t.Fatal(err)
	}
	if !exp.Equal(f.Clock.Now().Add(60 * time.Minute)) {
		t.Errorf("expires_at = %v", exp)
	}
}

func TestTokenDefaultMinutes(t *testing.T) {
	m, f := newTestModule(t)
	if _, err := runCmd(t, m, "admin token create"); err != nil {
		t.Fatal(err)
	}
	var exp time.Time
	_ = m.d.DB.QueryRow(context.Background(), `select expires_at from admin_tokens`).Scan(&exp)
	if !exp.Equal(f.Clock.Now().Add(15 * time.Minute)) {
		t.Errorf("expires_at = %v", exp)
	}
}

func redeem(t *testing.T, m *Module, token string) (id, label string, err error) {
	t.Helper()
	err = pgx.BeginFunc(context.Background(), m.d.DB, func(tx pgx.Tx) error {
		var e error
		id, label, e = m.redeemToken(context.Background(), tx, token)
		return e
	})
	return
}

func TestRedeemToken(t *testing.T) {
	m, f := newTestModule(t)
	ctx := context.Background()
	id, token, err := m.createToken(ctx, "laptop", 15*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	gotID, label, err := redeem(t, m, token)
	if err != nil || gotID != id || label != "laptop" {
		t.Fatalf("first redeem = %q %q %v", gotID, label, err)
	}
	if _, _, err := redeem(t, m, token); !errors.Is(err, core.ErrNotFound) {
		t.Errorf("second redeem err = %v", err)
	}
	_, tok2, _ := m.createToken(ctx, "", 15*time.Minute)
	f.Clock.Advance(16 * time.Minute)
	if _, _, err := redeem(t, m, tok2); !errors.Is(err, core.ErrNotFound) {
		t.Errorf("expired redeem err = %v", err)
	}
	if _, _, err := redeem(t, m, "adm_unknown"); !errors.Is(err, core.ErrNotFound) {
		t.Errorf("unknown redeem err = %v", err)
	}
}

func TestSessionsEnd(t *testing.T) {
	m, f := newTestModule(t)
	ctx := context.Background()
	id, _, err := m.createToken(ctx, "x", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	for _, h := range []string{"a", "b"} {
		_, err := m.d.DB.Exec(ctx,
			`insert into admin_sessions (id_hash, token_id, csrf_hash, created_at, last_seen_at, expires_at) values ($1, $2, $1, $3, $3, $4)`,
			[]byte(h), id, f.Clock.Now(), f.Clock.Now().Add(time.Hour))
		if err != nil {
			t.Fatal(err)
		}
	}
	out, err := runCmd(t, m, "admin sessions end")
	if err != nil || out != "ended 2 sessions\n" {
		t.Fatalf("out %q err %v", out, err)
	}
	var n int
	_ = m.d.DB.QueryRow(ctx, `select count(*) from admin_sessions`).Scan(&n)
	if n != 0 {
		t.Errorf("%d sessions left", n)
	}
	var actor string
	var detail []byte
	var target string
	err = m.d.DB.QueryRow(ctx, `select actor, target, detail from admin_audit where action = 'sessions.end'`).Scan(&actor, &target, &detail)
	if err != nil {
		t.Fatal(err)
	}
	var det map[string]any
	_ = json.Unmarshal(detail, &det)
	if actor != "cli" || target != "" || det["count"] != float64(2) {
		t.Errorf("audit = %q %q %v", actor, target, det)
	}
}

func TestTokenCreateAudited(t *testing.T) {
	m, _ := newTestModule(t)
	ctx := context.Background()
	id, token, err := m.createToken(ctx, "laptop", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	var n int
	var actor, target string
	var detail []byte
	if err := m.d.DB.QueryRow(ctx, `select count(*) from admin_audit where action = 'token.create'`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("rows = %d %v", n, err)
	}
	_ = m.d.DB.QueryRow(ctx, `select actor, target, detail from admin_audit`).Scan(&actor, &target, &detail)
	if actor != "cli" || target != id || string(detail) != `{"label": "laptop"}` {
		t.Errorf("audit = %q %q %s", actor, target, detail)
	}
	if strings.Contains(string(detail), token) || strings.Contains(target, token) {
		t.Error("token in audit")
	}
}
