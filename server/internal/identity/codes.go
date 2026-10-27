package identity

import (
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"math/big"
)

// newCode returns a uniformly random 6-digit email code.
func newCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		panic("identity: no randomness: " + err.Error())
	}
	return fmt.Sprintf("%06d", n.Int64())
}

// hashCode returns the SHA-256 of an email code, the only form that is stored.
func hashCode(code string) []byte {
	h := sha256.Sum256([]byte(code))
	return h[:]
}

const (
	codeTTL         = 10 // minutes
	codeMaxAttempts = 5
)

func codeEmail(code string) (subject, body string) {
	return "Your [App] code",
		"Your [App] code is " + code + ". It expires in 10 minutes. If you didn't ask for it, ignore this email."
}
