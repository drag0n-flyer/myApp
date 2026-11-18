package delivery

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/ratelimit"
	"github.com/drag0n-flyer/myApp/server/internal/realtime"
)

const (
	// maxEphemeralBytes bounds the ciphertext of one conv.ephemeral frame.
	maxEphemeralBytes = 4 << 10
	// ephemeralPerMinute is the per device, per conversation frame budget.
	ephemeralPerMinute = 30
)

// HandleFrames registers the delivery module's WebSocket frames on frames.
func (m *Module) HandleFrames(frames realtime.FrameRouter) {
	m.frames = frames
	m.ephLimit = ratelimit.New(ephemeralPerMinute, time.Minute, m.d.Now)
	frames.HandleFrame("conv.ephemeral", m.ephemeral)
}

// ephemeralRespectsBlocks says whether an ephemeral frame in the conversation
// is withheld from an account that blocked the sender. For a direct
// conversation it is (spec 6.1); every conversation is direct until group
// conversations exist, and the group change switches this on kind.
func ephemeralRespectsBlocks(conversationID string) bool { return true }

// ephemeral relays conv.ephemeral {conversationID, epoch, ciphertext} to the
// conversation's member devices that are connected right now, except the
// sender's own account. Nothing is stored, logged or pushed. Membership is
// read from conversation_devices on every frame.
func (m *Module) ephemeral(ctx context.Context, from core.Principal, data json.RawMessage) (any, error) {
	var in struct {
		ConversationID string      `json:"conversationID"`
		Epoch          *int64      `json:"epoch"`
		Ciphertext     httpx.Bytes `json:"ciphertext"`
	}
	if err := json.Unmarshal(data, &in); err != nil || in.Epoch == nil || *in.Epoch < 0 ||
		len(in.Ciphertext) == 0 || len(in.Ciphertext) > maxEphemeralBytes {
		return nil, httpx.Err("bad_request", "bad conv.ephemeral frame")
	}
	id := strings.ToLower(in.ConversationID)
	notFound := httpx.Err("not_found", "no such conversation")
	if !uuidRE.MatchString(id) {
		return nil, notFound
	}

	members, err := m.memberDevices(ctx, id)
	if err != nil {
		return nil, err
	}
	member := false
	for _, dv := range members {
		if dv.device == from.DeviceID {
			member = true
			break
		}
	}
	if !member {
		return nil, notFound
	}

	// Excess frames are dropped silently: the sender is acknowledged as usual.
	if ok, _ := m.ephLimit.Allow(from.DeviceID + "/" + id); !ok {
		return nil, nil
	}

	var blocked map[string]bool
	if ephemeralRespectsBlocks(id) {
		if blocked, err = m.blockers(ctx, members, from.AccountID); err != nil {
			return nil, err
		}
	}
	out := struct {
		ConversationID string      `json:"conversationID"`
		Epoch          int64       `json:"epoch"`
		From           ephemeralBy `json:"from"`
		Ciphertext     httpx.Bytes `json:"ciphertext"`
	}{id, *in.Epoch, ephemeralBy{from.AccountID, from.DeviceID}, in.Ciphertext}
	for _, dv := range members {
		if dv.account == from.AccountID || blocked[dv.account] {
			continue
		}
		m.frames.Send(dv.device, "conv.ephemeral", out) // offline: nothing, by design
	}
	return nil, nil
}

type ephemeralBy struct {
	Account string `json:"account"`
	Device  string `json:"device"`
}

// memberDevices lists the conversation's current member devices with their
// accounts.
func (m *Module) memberDevices(ctx context.Context, id string) ([]devAcct, error) {
	rows, err := m.d.DB.Query(ctx,
		`select cd.device_id::text, d.account_id::text from conversation_devices cd
		 join devices d on d.id = cd.device_id
		 where cd.conversation_id = $1 order by cd.device_id`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []devAcct
	for rows.Next() {
		var d devAcct
		if err := rows.Scan(&d.device, &d.account); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}
