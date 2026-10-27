package admin

import "testing"

func TestMaskEmail(t *testing.T) {
	for in, want := range map[string]string{
		"ana@example.org":   "a•••@example.org",
		"a@x.org":           "a•••@x.org",
		"x":                 "•••",
		"":                  "•••",
		"@x.org":            "•••@x.org",
		"édith@example.org": "é•••@example.org",
		"a@b@c.org":         "a•••@c.org",
	} {
		if got := maskEmail(in); got != want {
			t.Errorf("maskEmail(%q) = %q, want %q", in, got, want)
		}
	}
}
