package identity

import (
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// refusedRanges are format and direction characters refused by code point, so
// the result does not depend on the Unicode table version of a platform.
var refusedRanges = [][2]rune{
	{0x00AD, 0x00AD}, {0x0600, 0x0605}, {0x061C, 0x061C}, {0x06DD, 0x06DD},
	{0x070F, 0x070F}, {0x0890, 0x0891}, {0x08E2, 0x08E2}, {0x180E, 0x180E},
	{0x200B, 0x200B}, {0x200E, 0x200F}, {0x202A, 0x202E}, {0x2060, 0x2064},
	{0x2066, 0x206F}, {0xFEFF, 0xFEFF}, {0xFFF9, 0xFFFB}, {0x110BD, 0x110BD},
	{0x110CD, 0x110CD}, {0x13430, 0x1343F}, {0x1BCA0, 0x1BCA3},
	{0x1D173, 0x1D17A}, {0xE0001, 0xE0001},
}

func isRefused(r rune) bool {
	for _, rg := range refusedRanges {
		if r >= rg[0] && r <= rg[1] {
			return true
		}
	}
	if unicode.In(r, unicode.Cc, unicode.Zl, unicode.Zp) {
		return true
	}
	// Any other format character, except the joiners and the tag characters
	// that emoji sequences need.
	if unicode.Is(unicode.Cf, r) {
		return !(r == 0x200C || r == 0x200D || (r >= 0xE0020 && r <= 0xE007F))
	}
	return false
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
	if unicode.In(r, unicode.L, unicode.N, unicode.P, unicode.S) {
		return true
	}
	// Unassigned code points (Cn) count as visible, so a newer Unicode table
	// on one platform does not change the result. Go's C table holds Cn too,
	// so Cn is what is in C but in none of Cc, Cf, Co and Cs.
	return unicode.Is(unicode.C, r) && !unicode.In(r, unicode.Cc, unicode.Cf, unicode.Co, unicode.Cs)
}

// ValidDisplayName is the display-name rule shared with the clients
// (DisplayNameRule.clean): 1 to 40 code points after trimming, at least one
// visible character, no control, line or paragraph separator characters, no
// direction controls and no format characters except U+200C, U+200D and the
// tag characters. Unassigned code points count as visible. It returns the trimmed name.
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
		if isRefused(r) {
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
