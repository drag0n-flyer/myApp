package serverinfo

import (
	"context"
	"encoding/json"

	"github.com/drag0n-flyer/myApp/server/internal/push"
)

// Document returns the exact bytes GET /v1/server sends: the JSON object and
// a final newline, as httpx.JSON writes it. The dashboard shows the same bytes.
func (m *Module) Document(ctx context.Context) ([]byte, error) {
	p, c := m.d.Policy, m.d.Config
	var escrow, gateway *string
	if c.EscrowKeyFile != "" {
		k, err := m.escrowPublicKey()
		if err != nil {
			return nil, err
		}
		escrow = &k
	}
	if c.PushGatewayURL != "" {
		gateway = &c.PushGatewayURL
	}
	var vapidKey *string
	if c.VapidKeyFile != "" {
		m.mu.Lock()
		if m.vapidKey == "" {
			k, err := push.VAPIDPublicKey(c.VapidKeyFile)
			if err != nil {
				m.mu.Unlock()
				return nil, err
			}
			m.vapidKey = k
		}
		k := m.vapidKey
		m.mu.Unlock()
		vapidKey = &k
	}
	features := []string{"linking"}
	if c.TURNSecret != "" {
		features = []string{"calls", "video", "linking"}
	}
	type obj = map[string]any
	b, err := json.Marshal(obj{
		"protocol":       1,
		"host":           c.Host,
		"requiresInvite": p.RequiresInvite,
		"policy": obj{
			"defaultMode":     p.DefaultMode,
			"maxMode":         p.MaxMode,
			"defaultIPHiding": p.DefaultIPHiding,
		},
		"encryption": obj{"newConversations": p.Encryption.NewConversations, "escrowKey": escrow},
		"push":       obj{"gateway": gateway, "vapidKey": vapidKey},
		"features":   features,
		"federation": false,
		"limits":     obj{"attachmentBytes": p.Limits.AttachmentBytes},
	})
	if err != nil {
		return nil, err
	}
	return append(b, '\n'), nil
}
