package delivery

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/velixio/myApp/server/internal/httpx"
)

const (
	maxClientMsgIDLen = 128
	clientMsgTTL      = 7 * 24 * time.Hour
)

func (m *Module) messageRoutes(r *httpx.Router) {
	r.Authed("POST /v1/conversations/{id}/messages", httpx.Limited(m.msgLimit, func(req *http.Request) string {
		return httpx.PrincipalFrom(req.Context()).DeviceID
	}, m.postMessage))
}

func (m *Module) postMessage(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	id := strings.ToLower(r.PathValue("id"))
	var in struct {
		Epoch       *int64  `json:"epoch"`
		Ciphertext  string  `json:"ciphertext"`
		Escrow      *string `json:"escrow"`
		ClientMsgID string  `json:"clientMsgID"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	notFound := httpx.Err("not_found", "no such conversation")
	if !uuidRE.MatchString(id) {
		return notFound
	}
	ciphertext, err := b64.DecodeString(in.Ciphertext)
	if err != nil || len(ciphertext) == 0 || in.Epoch == nil || *in.Epoch < 0 || in.ClientMsgID == "" || len(in.ClientMsgID) > maxClientMsgIDLen {
		return httpx.Err("bad_request", "epoch, ciphertext and clientMsgID are required")
	}
	var escrow []byte
	if in.Escrow != nil {
		if escrow, err = b64.DecodeString(*in.Escrow); err != nil || len(escrow) == 0 {
			return httpx.Err("bad_request", "escrow must be non-empty base64url")
		}
	}

	var seq int64
	err = m.inTx(r.Context(), func(ctx context.Context, tx pgx.Tx) error {
		// The conversation row lock orders messages and commits, so seq
		// order is commit order.
		var mode string
		var epoch int64
		err := tx.QueryRow(ctx, `select mode, epoch from conversations where id = $1 for update`, id).Scan(&mode, &epoch)
		if err == pgx.ErrNoRows {
			return notFound
		}
		if err != nil {
			return err
		}
		var member bool
		if err := tx.QueryRow(ctx,
			`select exists (select 1 from conversation_devices where conversation_id = $1 and device_id = $2)`,
			id, p.DeviceID).Scan(&member); err != nil {
			return err
		}
		if !member {
			return notFound
		}

		// A repeated clientMsgID gets the first answer back, for 7 days.
		now := m.d.Now()
		if _, err := tx.Exec(ctx, `delete from client_messages where device_id = $1 and conversation_id = $2 and client_msg_id = $3 and created_at < $4`,
			p.DeviceID, id, in.ClientMsgID, now.Add(-clientMsgTTL)); err != nil {
			return err
		}
		err = tx.QueryRow(ctx, `select seq from client_messages where device_id = $1 and conversation_id = $2 and client_msg_id = $3`,
			p.DeviceID, id, in.ClientMsgID).Scan(&seq)
		if err == nil {
			return nil
		}
		if err != pgx.ErrNoRows {
			return err
		}

		if *in.Epoch > epoch {
			return httpx.Err("bad_request", "epoch is ahead of the conversation")
		}
		// The mode is the conversation's own; a later policy change does not touch it.
		if mode == "e2ee" && escrow != nil {
			return httpx.Err("escrow_forbidden", "this conversation does not take escrow copies")
		}
		if mode == "server_readable" && escrow == nil {
			return httpx.Err("escrow_required", "this conversation needs an escrow copy")
		}

		if err := tx.QueryRow(ctx,
			`update conversations set next_seq = next_seq + 1 where id = $1 returning next_seq - 1`, id).Scan(&seq); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `insert into client_messages (device_id, conversation_id, client_msg_id, seq, created_at) values ($1, $2, $3, $4, $5)`,
			p.DeviceID, id, in.ClientMsgID, seq, now); err != nil {
			return err
		}
		if escrow != nil {
			if _, err := tx.Exec(ctx, `insert into escrow (conversation_id, seq, sender_account, data, created_at) values ($1, $2, $3, $4, $5)`,
				id, seq, p.AccountID, escrow, now); err != nil {
				return err
			}
		}

		// Every member device but the sender's, except devices of accounts
		// that blocked the sender: those are skipped silently.
		members, err := queryPairs(ctx, tx,
			`select cd.device_id::text, d.account_id::text from conversation_devices cd
			 join devices d on d.id = cd.device_id
			 where cd.conversation_id = $1 and cd.device_id <> $2 order by cd.device_id`, id, p.DeviceID)
		if err != nil {
			return err
		}
		blocked, err := m.blockers(ctx, members, p.AccountID)
		if err != nil {
			return err
		}
		var to []string
		for _, dv := range members {
			if !blocked[dv.account] {
				to = append(to, dv.device)
			}
		}
		s := seq
		return m.deliver(ctx, tx, to, InboxItem{
			Kind: "message", ConversationID: id, Seq: &s, Epoch: *in.Epoch,
			SenderAccountID: p.AccountID, SenderDeviceID: p.DeviceID, Payload: ciphertext, SentAt: now,
		})
	})
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, struct {
		Seq int64 `json:"seq"`
	}{seq})
	return nil
}

// PruneOnce deletes inbox rows older than retention.inboxDays, escrow copies
// older than retention.escrowDays, and idempotency records older than 7 days.
// A failing delete does not stop the others; the errors are joined.
func (m *Module) PruneOnce(ctx context.Context) error {
	now := m.d.Now()
	day := 24 * time.Hour
	var errs []error
	del := func(sql string, cutoff time.Time) {
		if _, err := m.d.DB.Exec(ctx, sql, cutoff); err != nil {
			errs = append(errs, err)
		}
	}
	if d := m.d.Policy.Retention.InboxDays; d > 0 {
		del(`delete from inbox where sent_at < $1`, now.Add(-time.Duration(d)*day))
	}
	if d := m.d.Policy.Retention.EscrowDays; d > 0 {
		del(`delete from escrow where created_at < $1`, now.Add(-time.Duration(d)*day))
	}
	del(`delete from client_messages where created_at < $1`, now.Add(-clientMsgTTL))
	return errors.Join(errs...)
}
