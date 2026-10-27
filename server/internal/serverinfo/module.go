// Package serverinfo is lane E: GET /v1/server, the /invite/{code} page,
// the operator documents (security.txt, legal pages) and
// apple-app-site-association and assetlinks.json.
package serverinfo

import (
	"encoding/base64"
	"html/template"
	"net/http"
	"os"
	"path/filepath"
	"sync"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/delivery"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
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
	r.Public("GET /.well-known/assetlinks.json", m.assetlinks)
	r.Public("GET /.well-known/security.txt", m.doc("security.txt", "text/plain; charset=utf-8"))
	r.Public("GET /legal/privacy", m.doc("privacy.html", "text/html; charset=utf-8"))
	r.Public("GET /legal/terms", m.doc("terms.html", "text/html; charset=utf-8"))
	r.Public("GET /legal/disclosure", m.doc("disclosure.html", "text/html; charset=utf-8"))
}

// doc serves one operator document from APP_DOCS_DIR. The file name is fixed
// at registration and never comes from the request. The file is read on each
// request and sent with no-cache, so operators can edit it live. A missing
// directory or file, or a non-regular file, is 404 not_found.
func (m *Module) doc(name, contentType string) httpx.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		dir := m.d.Config.DocsDir
		if dir == "" {
			return httpx.Err("not_found", "not found")
		}
		path := filepath.Join(dir, name)
		if fi, err := os.Stat(path); err != nil || !fi.Mode().IsRegular() {
			return httpx.Err("not_found", "not found")
		}
		w.Header().Set("Content-Type", contentType)
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		http.ServeFile(w, r, path)
		return nil
	}
}

func (m *Module) info(w http.ResponseWriter, _ *http.Request) error {
	p, c := m.d.Policy, m.d.Config
	var escrow, gateway *string
	if c.EscrowKeyFile != "" {
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

// assetlinks serves Digital Asset Links for the Android apps in
// APP_ANDROID_APPS, so Android can tie passkeys and invite links to them. With
// none configured it is an empty list. Fingerprints of one package share an
// entry.
func (m *Module) assetlinks(w http.ResponseWriter, _ *http.Request) error {
	type obj = map[string]any
	links := []obj{}
	index := map[string]int{}
	fps := [][]string{}
	for _, a := range m.d.Config.AndroidApps {
		i, ok := index[a.Package]
		if !ok {
			i = len(fps)
			index[a.Package] = i
			fps = append(fps, nil)
			links = append(links, obj{
				"relation": []string{"delegate_permission/common.get_login_creds", "delegate_permission/common.handle_all_urls"},
				"target":   obj{"namespace": "android_app", "package_name": a.Package},
			})
		}
		fps[i] = append(fps[i], a.ColonFingerprint())
	}
	for i := range links {
		links[i]["target"].(obj)["sha256_cert_fingerprints"] = fps[i]
	}
	httpx.JSON(w, http.StatusOK, links)
	return nil
}
