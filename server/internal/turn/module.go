// Package turn is lane C: TURN credentials and relay health checks.
package turn

import (
	"context"
	"net"
	"net/http"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// Module is the turn module.
type Module struct {
	d      core.Deps
	prober *Prober
}

// New returns the turn module.
func New(d core.Deps) *Module {
	return &Module{d: d, prober: NewProber(d.Config.Relays)}
}

// Routes registers the TURN endpoints.
func (m *Module) Routes(r *httpx.Router) { r.Authed("GET /v1/turn", m.credentials) }

// Run probes relay health until ctx is cancelled.
func (m *Module) Run(ctx context.Context) { m.prober.Run(ctx) }

type iceServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
}

func (m *Module) credentials(w http.ResponseWriter, r *http.Request) error {
	relays := m.prober.Healthy()
	if m.d.Config.TURNSecret == "" || len(relays) == 0 {
		return httpx.Err("calls_disabled", "calls are not available")
	}
	user := newUsername(m.d.Now())
	cred := Credential(m.d.Config.TURNSecret, user)
	servers := make([]iceServer, 0, len(relays))
	for _, rl := range relays {
		h := rl.Host
		if ip := net.ParseIP(h); ip != nil && ip.To4() == nil {
			h = "[" + h + "]"
		}
		servers = append(servers, iceServer{
			URLs: []string{
				"stun:" + h + ":3478",
				"turn:" + h + ":3478?transport=udp",
				"turn:" + h + ":3478?transport=tcp",
				"turns:" + h + ":443?transport=tcp",
			},
			Username: user, Credential: cred,
		})
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"iceServers": servers, "ttl": int(credentialTTL.Seconds())})
	return nil
}
