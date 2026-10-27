// Package serverinfo is lane E: GET /v1/server, the /invite/{code} page and
// apple-app-site-association.
package serverinfo

import (
	"encoding/base64"
	"html/template"
	"net/http"
	"sync"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/delivery"
	"github.com/velixio/myApp/server/internal/httpx"
)

// Module is the serverinfo module.
type Module struct {
	d core.Deps

	mu        sync.Mutex
	escrowKey string // base64url public key, cached after the first good read
}

// New returns the serverinfo module.
func New(d core.Deps) *Module { return &Module{d: d} }

// Routes registers the server info endpoints.
func (m *Module) Routes(r *httpx.Router) {
	r.Public("GET /v1/server", m.info)
	r.Public("GET /invite/{code}", m.invite)
	r.Public("GET /.well-known/apple-app-site-association", m.aasa)
}

func (m *Module) info(w http.ResponseWriter, _ *http.Request) error {
	p, c := m.d.Policy, m.d.Config
	var escrow, gateway *string
	if p.Encryption.NewConversations == "server_readable" && c.EscrowKeyFile != "" {
		k, err := m.escrowPublicKey()
		if err != nil {
			return err
		}
		escrow = &k
	}
	if c.PushGatewayURL != "" {
		gateway = &c.PushGatewayURL
	}
	features := []string{"linking"}
	if c.TURNSecret != "" {
		features = []string{"calls", "video", "linking"}
	}
	type obj = map[string]any
	httpx.JSON(w, http.StatusOK, obj{
		"protocol":       1,
		"host":           c.Host,
		"requiresInvite": p.RequiresInvite,
		"policy": obj{
			"defaultMode":     p.DefaultMode,
			"maxMode":         p.MaxMode,
			"defaultIPHiding": p.DefaultIPHiding,
		},
		"encryption": obj{"newConversations": p.Encryption.NewConversations, "escrowKey": escrow},
		"push":       obj{"gateway": gateway},
		"features":   features,
		"federation": false,
		"limits":     obj{"attachmentBytes": p.Limits.AttachmentBytes},
	})
	return nil
}

// escrowPublicKey returns the escrow public key as base64url, read once from
// the key file by delivery.EscrowPublicKey and cached after the first good read.
func (m *Module) escrowPublicKey() (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.escrowKey != "" {
		return m.escrowKey, nil
	}
	pub, err := delivery.EscrowPublicKey(m.d.Config.EscrowKeyFile)
	if err != nil {
		return "", err
	}
	m.escrowKey = base64.RawURLEncoding.EncodeToString(pub)
	return m.escrowKey, nil
}

// invitePage is html/template, so the code is escaped for its HTML context.
var invitePage = template.Must(template.New("invite").Parse(`<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Invite</title>
</head>
<body>
<h1>Open this invite in [App]</h1>
<p>Invite code: <code>{{.}}</code></p>
</body>
</html>
`))

func (m *Module) invite(w http.ResponseWriter, r *http.Request) error {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Content-Security-Policy", "default-src 'none'")
	return invitePage.Execute(w, r.PathValue("code"))
}

func (m *Module) aasa(w http.ResponseWriter, _ *http.Request) error {
	ids := m.d.Config.AppleAppIDs
	if ids == nil {
		ids = []string{}
	}
	type obj = map[string]any
	// Apple fetches this file as application/json, with no redirects.
	httpx.JSON(w, http.StatusOK, obj{
		"applinks": obj{"details": []obj{{
			"appIDs":     ids,
			"components": []obj{{"/": "/invite/*"}, {"/": "/link/*"}},
		}}},
		"webcredentials": obj{"apps": ids},
	})
	return nil
}
