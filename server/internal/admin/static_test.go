package admin

import (
	"io/fs"
	"net/http"
	"net/http/httptest"
	"path"
	"regexp"
	"strings"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

// pages lists every HTML page the dashboard ships (without ".html").
var pages = []string{"index", "login", "invites", "server", "audit", "accounts", "account", "reports", "report"}

// designPages are the pages of the design's section 7.
var designPages = map[string]bool{
	"index.html": true, "login.html": true, "invites.html": true, "accounts.html": true,
	"account.html": true, "reports.html": true, "report.html": true, "server.html": true,
	"audit.html": true,
}

func embeddedFiles(t *testing.T) map[string]string {
	t.Helper()
	files := map[string]string{}
	err := fs.WalkDir(webFS, "web", func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		b, err := webFS.ReadFile(p)
		if err != nil {
			return err
		}
		files[strings.TrimPrefix(p, "web/")] = string(b)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return files
}

var (
	reScriptTag  = regexp.MustCompile(`(?is)<script\b[^>]*>`)
	reOnAttr     = regexp.MustCompile(`(?is)<[a-z][^>]*\s(on[a-z]+)\s*=`)
	reURLAttr    = regexp.MustCompile(`(?is)\b(?:src|href)\s*=\s*"([^"]*)"`)
	reImport     = regexp.MustCompile(`(?m)(?:^|[;\s])(?:import\s+(?:[^'"\n]*?\s+from\s+)?|export\s+[^'"\n]*?\s+from\s+)['"]([^'"]+)['"]`)
	reDynImport  = regexp.MustCompile(`import\(\s*['"]?([^'")]*)`)
	reInputTag   = regexp.MustCompile(`(?is)<input\b[^>]*>`)
	reIDAttr     = regexp.MustCompile(`(?is)\bid\s*=\s*"([^"]*)"`)
	reLabelFor   = regexp.MustCompile(`(?is)<label\b[^>]*\bfor\s*=\s*"([^"]*)"`)
	reMainTag    = regexp.MustCompile(`(?is)<main\b[^>]*\bid="main"`)
	reSkipLink   = regexp.MustCompile(`(?is)<a\b[^>]*href="#main"`)
	reTitle      = regexp.MustCompile(`(?is)<title>[^<]+</title>`)
	reHTMLLang   = regexp.MustCompile(`(?is)<html\b[^>]*\blang="en"`)
	reJSPage     = regexp.MustCompile("[\"'`]([a-z]+\\.html)")
	reHiddenType = regexp.MustCompile(`(?is)\btype\s*=\s*"(hidden|submit|button)"`)
)

func TestStaticFilesAreClean(t *testing.T) {
	for name, body := range embeddedFiles(t) {
		switch path.Ext(name) {
		case ".html":
			for _, tag := range reScriptTag.FindAllString(body, -1) {
				if !strings.Contains(tag, "src=") {
					t.Errorf("%s: inline script %q", name, tag)
				}
			}
			if m := reOnAttr.FindStringSubmatch(body); m != nil {
				t.Errorf("%s: event attribute %q", name, m[1])
			}
			for _, bad := range []string{"style=", "<style", "javascript:"} {
				if strings.Contains(strings.ToLower(body), bad) {
					t.Errorf("%s: contains %q", name, bad)
				}
			}
			for _, m := range reURLAttr.FindAllStringSubmatch(body, -1) {
				if strings.Contains(m[1], "http://") || strings.Contains(m[1], "https://") {
					t.Errorf("%s: external URL %q", name, m[1])
				}
			}
		case ".js":
			for _, bad := range []string{"innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function", "http://", "https://"} {
				if strings.Contains(body, bad) {
					t.Errorf("%s: contains %q", name, bad)
				}
			}
			for _, m := range reDynImport.FindAllStringSubmatch(body, -1) {
				if !strings.HasPrefix(m[1], "./") && !strings.HasPrefix(m[1], "../") {
					t.Errorf("%s: dynamic import of %q", name, m[1])
				}
			}
			for _, m := range reImport.FindAllStringSubmatch(body, -1) {
				if !strings.HasPrefix(m[1], "./") {
					t.Errorf("%s: non-relative import %q", name, m[1])
				}
			}
		case ".css":
			if strings.Contains(body, "@import") || strings.Contains(body, "url(http") {
				t.Errorf("%s: external CSS reference", name)
			}
		default:
			t.Errorf("%s: unexpected file type", name)
		}
	}
}

func TestEveryAssetEmbedded(t *testing.T) {
	files := embeddedFiles(t)
	referenced := map[string]bool{}
	ref := func(from, target string) {
		target = strings.SplitN(strings.SplitN(target, "#", 2)[0], "?", 2)[0]
		if target == "" {
			return
		}
		p := path.Join(path.Dir(from), target)
		if _, ok := files[p]; !ok {
			t.Errorf("%s references %q, which is not embedded", from, target)
			return
		}
		referenced[p] = true
	}
	for name, body := range files {
		switch path.Ext(name) {
		case ".html":
			for _, m := range reURLAttr.FindAllStringSubmatch(body, -1) {
				if strings.HasPrefix(m[1], "#") {
					continue
				}
				ref(name, m[1])
			}
		case ".js":
			for _, m := range reImport.FindAllStringSubmatch(body, -1) {
				ref(name, m[1])
			}
			// Pages the scripts link to (the nav, redirects, row links).
			for _, m := range reJSPage.FindAllStringSubmatch(body, -1) {
				if !designPages[m[1]] {
					t.Errorf("%s links to %q, which is not a page of the design", name, m[1])
				}
				referenced[m[1]] = true
			}
		}
	}
	for name := range files {
		if !referenced[name] && name != "index.html" {
			t.Errorf("%s is embedded but no page references it", name)
		}
	}
}

func TestStaticServing(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := New(d, Sources{Started: d.Now()})
	h := m.Handler()
	get := func(p string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("GET", p, nil)
		r.Host = "localhost"
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	types := map[string]string{
		".html": "text/html; charset=utf-8",
		".css":  "text/css; charset=utf-8",
		".js":   "text/javascript; charset=utf-8",
	}
	for name := range embeddedFiles(t) {
		url := "/admin/" + name
		w := get(url)
		if w.Code != 200 || w.Header().Get("Content-Type") != types[path.Ext(name)] {
			t.Errorf("GET %s = %d %q", url, w.Code, w.Header().Get("Content-Type"))
		}
	}
	if w := get("/admin/"); w.Code != 200 || w.Header().Get("Content-Type") != types[".html"] || !strings.Contains(w.Body.String(), "<main") {
		t.Errorf("GET /admin/ = %d %q", w.Code, w.Header().Get("Content-Type"))
	}
	// ServeMux cleans a path with ".." and redirects before the handler runs;
	// that must never serve a file.
	for _, p := range []string{"/admin/../go.mod", "/admin/static/../index.html"} {
		if w := get(p); w.Code == 200 || strings.Contains(w.Body.String(), "module ") {
			t.Errorf("GET %s = %d, must not serve a file", p, w.Code)
		}
	}
	for _, p := range []string{"/admin/static/", "/admin/static", "/admin/nope.html", "/admin/static/nope.js", "/admin/web/index.html", "/admin/%2e%2e/go.mod", "/admin/api/nope"} {
		if w := get(p); w.Code != 404 {
			t.Errorf("GET %s = %d, want 404", p, w.Code)
		}
	}
	r := httptest.NewRequest("POST", "/admin/index.html", nil)
	r.Host = "localhost"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code == http.StatusOK {
		t.Errorf("POST on a page = 200")
	}
}

func TestPagesAccessible(t *testing.T) {
	files := embeddedFiles(t)
	for _, p := range pages {
		name := p + ".html"
		body, ok := files[name]
		if !ok {
			t.Errorf("%s is missing", name)
			continue
		}
		if !reHTMLLang.MatchString(body) {
			t.Errorf("%s: no lang=\"en\"", name)
		}
		if n := len(reMainTag.FindAllString(body, -1)); n != 1 || strings.Count(body, "<main") != 1 {
			t.Errorf("%s: want exactly one <main id=\"main\">", name)
		}
		if !reSkipLink.MatchString(body) {
			t.Errorf("%s: no skip link to #main", name)
		}
		if !reTitle.MatchString(body) {
			t.Errorf("%s: no <title>", name)
		}
		if !strings.Contains(body, `name="color-scheme" content="light dark"`) {
			t.Errorf("%s: no color-scheme meta", name)
		}
		if !strings.Contains(body, `href="static/admin.css"`) || !strings.Contains(body, "<noscript>This page needs JavaScript.</noscript>") {
			t.Errorf("%s: missing stylesheet or noscript line", name)
		}
		labels := map[string]bool{}
		for _, m := range reLabelFor.FindAllStringSubmatch(body, -1) {
			labels[m[1]] = true
		}
		for _, in := range reInputTag.FindAllString(body, -1) {
			if reHiddenType.MatchString(in) {
				continue
			}
			id := reIDAttr.FindStringSubmatch(in)
			if id == nil || !labels[id[1]] {
				t.Errorf("%s: input without a label: %s", name, in)
			}
		}
	}
}
