package realtime

import (
	"encoding/json"
	"errors"
)

// maxFrameBytes bounds one inbound frame.
const maxFrameBytes = 64 << 10

// frame is the wire shape of every WebSocket message (design §7.3).
type frame struct {
	Type string          `json:"type"`
	ID   string          `json:"id,omitempty"`
	Data json.RawMessage `json:"data,omitempty"`
}

var errBadFrame = errors.New("realtime: malformed frame")

// decodeFrame parses an inbound frame. The type is required; id and data are
// optional.
func decodeFrame(b []byte) (typ, id string, data json.RawMessage, err error) {
	var f frame
	if err := json.Unmarshal(b, &f); err != nil {
		return "", "", nil, errBadFrame
	}
	if f.Type == "" {
		return "", "", nil, errBadFrame
	}
	return f.Type, f.ID, f.Data, nil
}

// encodeFrame marshals an outbound frame.
func encodeFrame(typ, id string, data any) ([]byte, error) {
	out := struct {
		Type string `json:"type"`
		ID   string `json:"id,omitempty"`
		Data any    `json:"data,omitempty"`
	}{typ, id, data}
	return json.Marshal(out)
}
