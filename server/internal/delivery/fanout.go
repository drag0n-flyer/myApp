package delivery

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/velixio/myApp/server/internal/core"
)

// InboxItem is one entry of a device's inbox, as sent to the client.
type InboxItem struct {
	Cursor          int64     `json:"cursor"`
	Kind            string    `json:"kind"` // "message", "commit" or "welcome"
	ConversationID  string    `json:"conversationID"`
	Seq             *int64    `json:"seq,omitempty"`
	Epoch           int64     `json:"epoch"`
	SenderAccountID string    `json:"senderAccountID"`
	SenderDeviceID  string    `json:"senderDeviceID"`
	Payload         []byte    `json:"payload"`
	SentAt          time.Time `json:"sentAt"`
}

// MarshalJSON writes Payload as base64url without padding.
func (i InboxItem) MarshalJSON() ([]byte, error) {
	type plain InboxItem
	return json.Marshal(struct {
		plain
		Payload string `json:"payload"`
	}{plain(i), b64.EncodeToString(i.Payload)})
}

// pending collects the rows deliver inserted in one transaction, so that
// notifyAll can announce them once the transaction has committed.
type pending struct {
	rows []pendingRow
}

type pendingRow struct {
	deviceID string
	item     InboxItem
	push     core.PushKind
}

type pendingKey struct{}

// inTx runs fn in a transaction. Devices are notified of everything fn
// delivered, but only after the transaction has committed; if fn fails,
// nothing is stored and nobody is notified.
func (m *Module) inTx(ctx context.Context, fn func(ctx context.Context, tx pgx.Tx) error) error {
	p := &pending{}
	ctx = context.WithValue(ctx, pendingKey{}, p)
	if err := pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error { return fn(ctx, tx) }); err != nil {
		return err
	}
	// The request may end now; the announcements must still go out.
	m.notifyAll(context.WithoutCancel(ctx), p)
	return nil
}

// deliver inserts one inbox row per device, in deviceIDs order, copying item
// (its Cursor is ignored). It queues the announcements for notifyAll. It must
// run inside inTx; otherwise it returns an error and stores nothing.
func (m *Module) deliver(ctx context.Context, tx pgx.Tx, deviceIDs []string, item InboxItem) error {
	return m.deliverPush(ctx, tx, deviceIDs, item, core.PushMessage)
}

// deliverPush is deliver with the kind of push to send to offline devices;
// core.PushNone delivers the row without any notification.
func (m *Module) deliverPush(ctx context.Context, tx pgx.Tx, deviceIDs []string, item InboxItem, push core.PushKind) error {
	p, ok := ctx.Value(pendingKey{}).(*pending)
	if !ok {
		return errors.New("delivery: deliver called outside inTx")
	}
	if len(deviceIDs) == 0 {
		return nil
	}
	if err := lockDevices(ctx, tx, deviceIDs); err != nil {
		return err
	}
	for _, dev := range deviceIDs {
		it := item
		err := tx.QueryRow(ctx,
			`insert into inbox (device_id, kind, conversation_id, seq, epoch, sender_account, sender_device, payload, sent_at)
			 values ($1, $2, $3, $4, $5, $6, $7, $8, $9) returning cursor`,
			dev, it.Kind, it.ConversationID, it.Seq, it.Epoch, it.SenderAccountID, it.SenderDeviceID, it.Payload, it.SentAt,
		).Scan(&it.Cursor)
		if err != nil {
			return err
		}
		p.rows = append(p.rows, pendingRow{dev, it, push})
	}
	return nil
}

// lockDevices locks the device rows in sorted ID order until the transaction
// ends. Inbox cursors come from a sequence at insert time, so without this a
// lower cursor could commit after a higher one for the same device, and a
// client acking the higher one would lose the other row. Holding the lock
// from before the insert to the commit makes cursor order equal commit order
// for each device; the fixed order avoids deadlocks.
func lockDevices(ctx context.Context, tx pgx.Tx, deviceIDs []string) error {
	_, err := tx.Exec(ctx, `select 1 from devices where id = any($1::uuid[]) order by id for update`, deviceIDs)
	return err
}

// notifyAll tells each recipient device about its new inbox row.
func (m *Module) notifyAll(ctx context.Context, p *pending) {
	for _, r := range p.rows {
		ev := core.Event{Type: "inbox", Data: r.item, Push: r.push}
		if r.push != core.PushNone {
			ev.PushPayload = r.item.Payload
		}
		m.d.Notify.Notify(ctx, r.deviceID, ev)
	}
}
