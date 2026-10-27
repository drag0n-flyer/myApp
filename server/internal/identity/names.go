package identity

import (
	"regexp"
	"strings"
)

var usernameRE = regexp.MustCompile(`^[a-z][a-z0-9_]{2,31}$`)

var reserved = map[string]bool{
	"admin": true, "root": true, "support": true, "security": true,
	"abuse": true, "system": true, "help": true,
}

// ValidUsername reports whether s matches the username rule. It does not
// check reserved names.
func ValidUsername(s string) bool { return usernameRE.MatchString(s) }

// Reserved reports whether s is a name nobody may register.
func Reserved(s string) bool { return reserved[s] }

// NormalizeEmail trims and lowercases s. It reports false unless the result
// is at most 254 characters with exactly one "@" and a dot in the domain.
func NormalizeEmail(s string) (string, bool) {
	e := strings.ToLower(strings.TrimSpace(s))
	if len(e) > 254 || strings.ContainsAny(e, " \t\r\n") || strings.Count(e, "@") != 1 {
		return "", false
	}
	local, domain, _ := strings.Cut(e, "@")
	if local == "" || !strings.Contains(domain, ".") ||
		strings.HasPrefix(domain, ".") || strings.HasSuffix(domain, ".") {
		return "", false
	}
	return e, true
}
