package admin

import (
	"bytes"
	"context"
	"encoding/json"
	"testing"
	"time"

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

func TestSessionsEnd(t *testing.T) {
	m, f := newTestModule(t)
	ctx := context.Background()
	id, err := m.createAdmin(ctx, "alice", goodPassword, cliBy)
	if err != nil {
		t.Fatal(err)
	}
	for _, h := range []string{"a", "b"} {
		_, err := m.d.DB.Exec(ctx,
			`insert into admin_sessions (id_hash, admin_user_id, csrf_hash, created_at, last_seen_at, expires_at) values ($1, $2, $1, $3, $3, $4)`,
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
