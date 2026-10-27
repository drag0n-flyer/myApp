package delivery

import (
	"context"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
	"github.com/jackc/pgx/v5"
)

func TestDeliverOutsideInTxFails(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := New(d)
	p := testkit.Account(t, d, "ana")
	ctx := context.Background()
	tx, err := d.DB.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	item := InboxItem{Kind: "message", ConversationID: core.NewID(), SenderAccountID: p.AccountID, SenderDeviceID: p.DeviceID, SentAt: d.Now()}
	if err := m.deliver(ctx, tx, []string{p.DeviceID}, item); err == nil {
		t.Fatal("deliver outside inTx must fail")
	}
}

// Two transactions delivering to the same device must run one after the
// other, so that cursor order equals commit order.
func TestDeliverSerialisesPerDevice(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := New(d)
	p := testkit.Account(t, d, "ana")
	ctx := context.Background()
	item := func(tag byte) InboxItem {
		return InboxItem{Kind: "message", ConversationID: core.NewID(), Epoch: 0, Payload: []byte{tag},
			SenderAccountID: p.AccountID, SenderDeviceID: p.DeviceID, SentAt: d.Now()}
	}

	delivered, release := make(chan struct{}), make(chan struct{})
	first := make(chan error, 1)
	go func() {
		first <- m.inTx(ctx, func(ctx context.Context, tx pgx.Tx) error {
			if err := m.deliver(ctx, tx, []string{p.DeviceID}, item(1)); err != nil {
				return err
			}
			close(delivered)
			<-release
			return nil
		})
	}()
	<-delivered

	second := make(chan error, 1)
	go func() {
		second <- m.inTx(ctx, func(ctx context.Context, tx pgx.Tx) error {
			return m.deliver(ctx, tx, []string{p.DeviceID}, item(2))
		})
	}()
	select {
	case err := <-second:
		t.Fatalf("second delivery finished while the first was uncommitted (err=%v)", err)
	case <-time.After(300 * time.Millisecond):
	}
	close(release)
	if err := <-first; err != nil {
		t.Fatal(err)
	}
	if err := <-second; err != nil {
		t.Fatal(err)
	}
	rows, err := d.DB.Query(ctx, `select payload from inbox where device_id = $1 order by cursor`, p.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var order []byte
	for rows.Next() {
		var b []byte
		if err := rows.Scan(&b); err != nil {
			t.Fatal(err)
		}
		order = append(order, b...)
	}
	if string(order) != "\x01\x02" {
		t.Fatalf("cursor order = %v, want commit order [1 2]", order)
	}
}
