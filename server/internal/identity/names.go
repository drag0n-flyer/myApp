package identity

import (
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// directionControls are bidirectional controls that can make a name read as
// something else.
var directionControls = map[rune]bool{
	0x202A: true, 0x202B: true, 0x202C: true, 0x202D: true, 0x202E: true,
	0x2066: true, 0x2067: true, 0x2068: true, 0x2069: true,
	0x200E: true, 0x200F: true, 0x061C: true,
}

// invisibleFillers draw nothing, so they do not count as a visible character.
var invisibleFillers = map[rune]bool{
	0x200B: true, 0x200C: true, 0x200D: true, 0x2060: true, 0xFEFF: true,
	0x3164: true, 0x115F: true, 0x1160: true, 0xFFA0: true, 0x2800: true,
	0x034F: true, 0x180E: true, 0x17B4: true, 0x17B5: true,
}

// maxDisplayNameRunes is the longest display name, in code points.
const maxDisplayNameRunes = 40

func isTrimmable(r rune) bool {
	if (r >= 0x09 && r <= 0x0D) || (r >= 0x1C && r <= 0x1F) {
		return true
	}
	return unicode.In(r, unicode.Zs, unicode.Zl, unicode.Zp)
}

func isVisible(r rune) bool {
	if invisibleFillers[r] {
		return false
	}
	return unicode.In(r, unicode.L, unicode.N, unicode.P, unicode.S)
}

// ValidDisplayName is the display-name rule shared with the clients
// (DisplayNameRule.clean): 1 to 40 code points after trimming, at least one
// visible character, no control, line or paragraph separator characters and no
// direction controls. It returns the trimmed name.
func ValidDisplayName(s string) (clean string, ok bool) {
	if !utf8.ValidString(s) {
		return "", false
	}
	name := strings.TrimFunc(s, isTrimmable)
	if name == "" || utf8.RuneCountInString(name) > maxDisplayNameRunes {
		return "", false
	}
	visible := false
	for _, r := range name {
		if directionControls[r] || unicode.In(r, unicode.Cc, unicode.Zl, unicode.Zp) {
			return "", false
		}
		if isVisible(r) {
			visible = true
		}
	}
	if !visible {
		return "", false
	}
	return name, true
}

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
