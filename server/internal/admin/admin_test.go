package admin

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func TestAuditWrites(t *testing.T) {
	d, f := testkit.NewDeps(t)
	m := New(d, Sources{Started: d.Now()})
	ctx := context.Background()
	id := "11111111-1111-1111-1111-111111111111"
	if err := m.audit(ctx, d.DB, Entry{Actor: "cli", Action: "token.create", Target: id, Detail: map[string]any{"label": "x"}}); err != nil {
		t.Fatal(err)
	}
	var (
		n             int
		actor, a, tgt string
		detail        []byte
		at            = f.Clock.Now()
		gotAt         = at
		tokenIDIsNull bool
	)
	if err := d.DB.QueryRow(ctx, `select count(*) from admin_audit`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("rows = %d, %v", n, err)
	}
	if err := d.DB.QueryRow(ctx, `select actor, action, target, detail, at, token_id is null from admin_audit`).
		Scan(&actor, &a, &tgt, &detail, &gotAt, &tokenIDIsNull); err != nil {
		t.Fatal(err)
	}
	var det map[string]any
	_ = json.Unmarshal(detail, &det)
	if actor != "cli" || a != "token.create" || tgt != id || det["label"] != "x" || !gotAt.Equal(at) || !tokenIDIsNull {
		t.Errorf("row = %q %q %q %v %v null=%v", actor, a, tgt, det, gotAt, tokenIDIsNull)
	}
}

func TestMigrationTables(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	want := map[string][]string{
		"admin_tokens":   {"id", "token_hash", "label", "created_at", "expires_at", "used_at"},
		"admin_sessions": {"id_hash", "token_id", "csrf_hash", "created_at", "last_seen_at", "expires_at"},
		"admin_audit":    {"id", "at", "actor", "token_id", "action", "target", "detail"},
	}
	for table, cols := range want {
		rows, err := d.DB.Query(context.Background(),
			`select column_name from information_schema.columns where table_name = $1`, table)
		if err != nil {
			t.Fatal(err)
		}
		got := map[string]bool{}
		for rows.Next() {
			var c string
			if err := rows.Scan(&c); err != nil {
				t.Fatal(err)
			}
			got[c] = true
		}
		rows.Close()
		if len(got) != len(cols) {
			t.Errorf("%s has %d columns, want %d", table, len(got), len(cols))
		}
		for _, c := range cols {
			if !got[c] {
				t.Errorf("%s lacks column %s", table, c)
			}
		}
	}
}
