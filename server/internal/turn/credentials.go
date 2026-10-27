package turn

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1" //nolint:gosec // coturn's REST-credential scheme mandates HMAC-SHA1
	"encoding/base64"
	"encoding/hex"
	"strconv"
	"time"
)

// credentialTTL is how long TURN credentials last (design §2 item 7).
const credentialTTL = 12 * time.Hour

// Credential returns base64(HMAC-SHA1(secret, username)), the password coturn
// expects for a time-limited username.
func Credential(secret, username string) string {
	h := hmac.New(sha1.New, []byte(secret))
	h.Write([]byte(username))
	return base64.StdEncoding.EncodeToString(h.Sum(nil))
}

// newUsername returns "<unix expiry>:<8 random hex>"; it carries no account or
// device identity.
func newUsername(now time.Time) string {
	var r [4]byte
	if _, err := rand.Read(r[:]); err != nil {
		panic("turn: no randomness: " + err.Error())
	}
	return strconv.FormatInt(now.Add(credentialTTL).Unix(), 10) + ":" + hex.EncodeToString(r[:])
}
