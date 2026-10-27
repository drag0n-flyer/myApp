package admin

import (
	"strings"
	"unicode/utf8"
)

// maskEmail hides most of an address: the first character of the local part,
// "•••", then "@" and the full domain. An address without "@" becomes "•••".
// Emails never leave the server in full.
func maskEmail(email string) string {
	at := strings.LastIndex(email, "@")
	if at < 0 {
		return "•••"
	}
	local, domain := email[:at], email[at:]
	if local == "" {
		return "•••" + domain
	}
	r, _ := utf8.DecodeRuneInString(local)
	return string(r) + "•••" + domain
}
