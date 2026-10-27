package admin

import (
	"embed"
	"net/http"
)

//go:embed web
var webFS embed.FS

// static serves the dashboard's pages. Until Lane C (C1) it serves only
// web/index.html at /admin/.
func (m *Module) static(mux *http.ServeMux) {
	mux.HandleFunc("GET /admin/{$}", func(w http.ResponseWriter, r *http.Request) {
		b, err := webFS.ReadFile("web/index.html")
		if err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(b)
	})
}
