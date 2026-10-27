package admin

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/url"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

type relaySetting struct {
	Host   string `json:"host"`
	Region string `json:"region"`
}

// serverSettings are the non-secret settings the Server page shows (design
// 6.5). Secrets and key files appear only as booleans.
type serverSettings struct {
	Host             string         `json:"host"`
	Listen           string         `json:"listen"`
	AdminListen      string         `json:"adminListen"`
	AdminHosts       []string       `json:"adminHosts"`
	Relays           []relaySetting `json:"relays"`
	PushGateway      string         `json:"pushGateway"`
	PushConfigured   bool           `json:"pushConfigured"`
	EscrowConfigured bool           `json:"escrowConfigured"`
	SMTPConfigured   bool           `json:"smtpConfigured"`
	DocsConfigured   bool           `json:"docsConfigured"`
	TrustProxy       bool           `json:"trustProxy"`
	LabMode          bool           `json:"labMode"`
}

// gatewayDisplay is the gateway URL without credentials, query or fragment.
func gatewayDisplay(raw string) string {
	if raw == "" {
		return ""
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return ""
	}
	return (&url.URL{Scheme: u.Scheme, Host: u.Host, Path: u.Path}).String()
}

func (m *Module) settings() serverSettings {
	c := m.d.Config
	st := serverSettings{
		Host:             c.Host,
		Listen:           c.Listen,
		AdminListen:      c.AdminListen,
		AdminHosts:       append([]string{}, c.AdminHosts...),
		Relays:           []relaySetting{},
		PushGateway:      gatewayDisplay(c.PushGatewayURL),
		PushConfigured:   c.PushGatewayURL != "" && c.PushServerID != "" && c.PushKeyFile != "",
		EscrowConfigured: c.EscrowKeyFile != "",
		SMTPConfigured:   c.SMTPURL != "",
		DocsConfigured:   c.DocsDir != "",
		TrustProxy:       c.TrustProxy,
		LabMode:          c.LabMode,
	}
	for _, r := range c.Relays {
		st.Relays = append(st.Relays, relaySetting{Host: r.Host, Region: r.Region})
	}
	return st
}

// serverInfo answers GET /admin/api/server: the loaded policy, the exact
// GET /v1/server document, and the non-secret settings. Read-only.
func (m *Module) serverInfo(w http.ResponseWriter, r *http.Request, s Session) error {
	var info json.RawMessage = []byte("null")
	if m.src.Info != nil {
		doc, err := m.src.Info.Document(r.Context())
		if err != nil {
			m.d.Log.Debug("admin: server info document unavailable")
		} else {
			info = publicInfo(doc)
		}
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"policy":   m.d.Policy,
		"info":     info,
		"settings": m.settings(),
	})
	return nil
}

// publicInfo is the GET /v1/server document with the push gateway shown the way
// settings.pushGateway shows it: no user-info, query or fragment. The public
// endpoint itself is unchanged. A document that does not parse is dropped.
func publicInfo(doc []byte) json.RawMessage {
	dec := json.NewDecoder(bytes.NewReader(doc))
	dec.UseNumber()
	var obj map[string]any
	if err := dec.Decode(&obj); err != nil || obj == nil {
		return json.RawMessage("null")
	}
	if p, ok := obj["push"].(map[string]any); ok {
		if gw, ok := p["gateway"].(string); ok {
			p["gateway"] = gatewayDisplay(gw)
		}
	}
	b, err := json.Marshal(obj)
	if err != nil {
		return json.RawMessage("null")
	}
	return b
}
