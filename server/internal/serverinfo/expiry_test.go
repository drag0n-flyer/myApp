package serverinfo_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/serverinfo"
)

func TestSecurityTxtWarning(t *testing.T) {
	now := time.Date(2026, 10, 28, 0, 0, 0, 0, time.UTC)
	cases := []struct {
		name, content, want string // want: substring of the warning, "" for none
	}{
		{"past", "Contact: mailto:a@b.c\nExpires: 2026-01-01T00:00:00Z\n", "expired"},
		{"within 30 days", "Contact: mailto:a@b.c\nExpires: 2026-11-20T00:00:00Z\n", "expires soon"},
		{"far future", "Contact: mailto:a@b.c\nExpires: 2027-09-30T00:00:00Z\n", ""},
		{"missing field", "Contact: mailto:a@b.c\n", "no Expires"},
		{"unparseable", "Expires: next year\n", "not a valid"},
		{"comment ignored", "# Expires: 2020-01-01T00:00:00Z\nExpires: 2027-09-30T00:00:00Z\n", ""},
	}
	for _, tc := range cases {
		dir := t.TempDir()
		if err := os.WriteFile(filepath.Join(dir, "security.txt"), []byte(tc.content), 0o600); err != nil {
			t.Fatal(err)
		}
		got := serverinfo.SecurityTxtWarning(dir, now)
		if tc.want == "" && got != "" || tc.want != "" && !strings.Contains(got, tc.want) {
			t.Errorf("%s: warning %q, want %q", tc.name, got, tc.want)
		}
	}
	if got := serverinfo.SecurityTxtWarning(t.TempDir(), now); got != "" {
		t.Errorf("no file: %q", got)
	}
	if got := serverinfo.SecurityTxtWarning("", now); got != "" {
		t.Errorf("no dir: %q", got)
	}
}
