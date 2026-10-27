package admin

import (
	"embed"
	"net/http"
	"path"
	"strings"
)

//go:embed web
var webFS embed.FS

var contentTypes = map[string]string{
	".html": "text/html; charset=utf-8",
	".css":  "text/css; charset=utf-8",
	".js":   "text/javascript; charset=utf-8",
}

// static mounts the dashboard's pages and assets under /admin/. More specific
// API patterns win over this subtree, so an unknown /admin/api path is a 404.
func (m *Module) static(mux *http.ServeMux) {
	mux.Handle("GET /admin/", m.staticHandler())
}

// staticHandler serves GET /admin/ as web/index.html, GET /admin/{page}.html
// and GET /admin/static/{file} from the embedded files only. Anything else,
// including directories, is a 404.
func (m *Module) staticHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rest := strings.TrimPrefix(r.URL.Path, "/admin/")
		name := ""
		switch {
		case rest == "":
			name = "index.html"
		case strings.HasPrefix(rest, "static/"):
			name = rest
		case !strings.Contains(rest, "/"):
			name = rest
		}
		ext := path.Ext(name)
		ctype, ok := contentTypes[ext]
		if name == "" || !ok || strings.Contains(name, "..") ||
			(ext == ".html") != !strings.HasPrefix(name, "static/") {
			http.NotFound(w, r)
			return
		}
		b, err := webFS.ReadFile("web/" + name)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", ctype)
		_, _ = w.Write(b)
	})
}
