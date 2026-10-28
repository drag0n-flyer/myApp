package admin

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
)

// randomString returns n random bytes from crypto/rand as unpadded base64url.
func randomString(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// hashString is the SHA-256 the database stores for a session ID or CSRF
// token.
func hashString(s string) []byte {
	h := sha256.Sum256([]byte(s))
	return h[:]
}
