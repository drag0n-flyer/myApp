package delivery_test

import (
	"context"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/delivery"
)

func TestPruneRemovesExpiredContactLinks(t *testing.T) {
	w := newWorld(t)
	ctx := context.Background()
	now := w.d.Now()
	acct := w.ana1.AccountID
	for i, exp := range []time.Time{now.Add(-time.Hour), now.Add(-time.Minute), now.Add(time.Hour)} {
		if _, err := w.d.DB.Exec(ctx,
			`insert into contact_links (token_hash, account_id, expires_at) values ($1, $2, $3)`,
			[]byte{byte(i + 1)}, acct, exp); err != nil {
			t.Fatal(err)
		}
	}
	// A used link that is still unexpired stays until it expires.
	if _, err := w.d.DB.Exec(ctx, `update contact_links set used_at = $1, used_by = $2 where token_hash = $3`,
		now, w.bob1.AccountID, []byte{3}); err != nil {
		t.Fatal(err)
	}
	if err := delivery.New(w.d).PruneOnce(ctx); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := w.d.DB.QueryRow(ctx, `select count(*) from contact_links where account_id = $1`, acct).Scan(&n); err != nil || n != 1 {
		t.Fatalf("rows after prune = %d, %v", n, err)
	}
}
