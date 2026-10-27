package serverinfo

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// SecurityTxtWarning checks dir/security.txt (RFC 9116) and returns a warning
// when its Expires field is missing, unparseable, in the past, or less than 30
// days away. It returns "" when there is nothing to warn about, including when
// dir is empty or the file does not exist. It is advisory: callers log it.
func SecurityTxtWarning(dir string, now time.Time) string {
	if dir == "" {
		return ""
	}
	b, err := os.ReadFile(filepath.Join(dir, "security.txt"))
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(b), "\n") {
		k, v, ok := strings.Cut(strings.TrimSpace(line), ":")
		if !ok || !strings.EqualFold(strings.TrimSpace(k), "Expires") {
			continue
		}
		v = strings.TrimSpace(v)
		ts, err := time.Parse(time.RFC3339, v)
		if err != nil {
			return fmt.Sprintf("security.txt Expires %q is not a valid RFC 3339 date", v)
		}
		switch {
		case !ts.After(now):
			return fmt.Sprintf("security.txt expired on %s; renew its Expires date", v)
		case ts.Before(now.AddDate(0, 0, 30)):
			return fmt.Sprintf("security.txt expires soon (%s); renew its Expires date", v)
		}
		return ""
	}
	return "security.txt has no Expires field (required by RFC 9116)"
}
