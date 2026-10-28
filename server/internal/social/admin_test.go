package social_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/social"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func TestMarkReportHandled(t *testing.T) {
	d, f := testkit.NewDeps(t)
	m := social.New(d)
	ctx := context.Background()
	rep := testkit.Account(t, d, "rita")
	bad := testkit.Account(t, d, "bad")
	id := core.NewID()
	if _, err := d.DB.Exec(ctx,
		`insert into reports (id, reporter, reported_account, reason, created_at) values ($1, $2, $3, 'spam', $4)`,
		id, rep.AccountID, bad.AccountID, f.Clock.Now()); err != nil {
		t.Fatal(err)
	}
	tok1, tok2 := core.NewID(), core.NewID()
	if err := m.MarkReportHandled(ctx, id, tok1); err != nil {
		t.Fatal(err)
	}
	first := f.Clock.Now()
	f.Clock.Advance(time.Hour)
	if err := m.MarkReportHandled(ctx, id, tok2); err != nil {
		t.Fatalf("second call: %v", err)
	}
	var at time.Time
	var by string
	if err := d.DB.QueryRow(ctx, `select handled_at, handled_by::text from reports where id = $1`, id).Scan(&at, &by); err != nil {
		t.Fatal(err)
	}
	if !at.Equal(first) || by != tok1 {
		t.Fatalf("handled_at %v by %s; want %v by %s", at, by, first, tok1)
	}
	if err := m.MarkReportHandled(ctx, core.NewID(), tok1); !errors.Is(err, core.ErrNotFound) {
		t.Fatalf("unknown id: %v", err)
	}
	if err := m.MarkReportHandled(ctx, "nope", tok1); !errors.Is(err, core.ErrNotFound) {
		t.Fatalf("bad id: %v", err)
	}
}
