package serverinfo_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/velixio/myApp/server/internal/core"
)

func TestDocsServed(t *testing.T) {
	dir := t.TempDir()
	cases := []struct{ route, file, ctype string }{
		{"/.well-known/security.txt", "security.txt", "text/plain; charset=utf-8"},
		{"/legal/privacy", "privacy.html", "text/html; charset=utf-8"},
		{"/legal/terms", "terms.html", "text/html; charset=utf-8"},
		{"/legal/disclosure", "disclosure.html", "text/html; charset=utf-8"},
	}
	for _, tc := range cases {
		if err := os.WriteFile(filepath.Join(dir, tc.file), []byte("body of "+tc.file), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	c := serve(t, func(d *core.Deps) { d.Config.DocsDir = dir })
	for _, tc := range cases {
		res := c.Do(nil, "GET", tc.route, nil)
		if res.Status != 200 || string(res.Body) != "body of "+tc.file {
			t.Fatalf("%s: %d %q", tc.route, res.Status, res.Body)
		}
		if got := res.Header.Get("Content-Type"); got != tc.ctype {
			t.Errorf("%s: content type %q", tc.route, got)
		}
		if got := res.Header.Get("Cache-Control"); got != "no-cache" {
			t.Errorf("%s: cache-control %q", tc.route, got)
		}
	}
	// Edits show up on the next request.
	if err := os.WriteFile(filepath.Join(dir, "terms.html"), []byte("new"), 0o600); err != nil {
		t.Fatal(err)
	}
	if res := c.Do(nil, "GET", "/legal/terms", nil); string(res.Body) != "new" {
		t.Fatalf("not live: %q", res.Body)
	}
}

func TestDocsMissing(t *testing.T) {
	routes := []string{"/.well-known/security.txt", "/legal/privacy", "/legal/terms", "/legal/disclosure"}
	empty := t.TempDir()
	dirAsFile := t.TempDir()
	if err := os.Mkdir(filepath.Join(dirAsFile, "privacy.html"), 0o700); err != nil {
		t.Fatal(err)
	}
	for name, dir := range map[string]string{
		"unset":      "",
		"no dir":     filepath.Join(empty, "nope"),
		"empty dir":  empty,
		"dir as doc": dirAsFile,
	} {
		c := serve(t, func(d *core.Deps) { d.Config.DocsDir = dir })
		for _, r := range routes {
			res := c.Do(nil, "GET", r, nil)
			if res.Status != 404 || !strings.Contains(string(res.Body), `"not_found"`) {
				t.Errorf("%s %s: %d %s", name, r, res.Status, res.Body)
			}
		}
	}
}

func TestDocsNoTraversal(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "docs")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "secret"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	c := serve(t, func(d *core.Deps) { d.Config.DocsDir = dir })
	for _, p := range []string{
		"/legal/..%2f..%2fetc%2fpasswd", "/legal/..%2fsecret", "/legal/../secret",
		"/legal/privacy.html", "/legal/security.txt", "/legal/", "/legal",
		"/.well-known/..%2fsecret", "/.well-known/passwd",
	} {
		res := c.Do(nil, "GET", p, nil)
		// The mux may first redirect an unclean path to its cleaned form, which is not a doc route.
		if res.Status == 307 && strings.HasSuffix(p, "/../secret") {
			if res = c.Do(nil, "GET", "/secret", nil); res.Status != 404 {
				t.Errorf("%s: redirect target %d", p, res.Status)
			}
			continue
		}
		if res.Status != 404 || strings.Contains(string(res.Body), "secret") {
			t.Errorf("%s: %d %s", p, res.Status, res.Body)
		}
	}
}

func TestSecurityTxtExampleValid(t *testing.T) {
	b, err := os.ReadFile("../../../deploy/docs/security.txt.example")
	if err != nil {
		t.Fatal(err)
	}
	var contact, expires []string
	for _, line := range strings.Split(string(b), "\n") {
		if k, v, ok := strings.Cut(line, ":"); ok && !strings.HasPrefix(line, "#") {
			switch strings.TrimSpace(k) {
			case "Contact":
				contact = append(contact, strings.TrimSpace(v))
			case "Expires":
				expires = append(expires, strings.TrimSpace(v))
			}
		}
	}
	if len(contact) != 1 || len(expires) != 1 {
		t.Fatalf("contact %v expires %v", contact, expires)
	}
	// Only the format is checked: a fixed example date must not make the suite rot.
	if _, err := time.Parse(time.RFC3339, expires[0]); err != nil {
		t.Fatal(err)
	}
}
