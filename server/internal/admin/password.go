package admin

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"
)

// Argon2id parameters for new hashes. A stored hash carries its own, so these
// can be raised later without breaking old hashes.
const (
	argonMemory  = 19 * 1024 // KiB, 19 MiB
	argonTime    = 2
	argonThreads = 1
	argonSaltLen = 16
	argonKeyLen  = 32

	minPasswordLen = 12
	maxPasswordLen = 128
)

// phc is a parsed argon2id PHC string.
type phc struct {
	memory, time uint32
	threads      uint8
	salt, key    []byte
}

func deriveKey(password string, salt []byte, memory, time uint32, threads uint8, keyLen uint32) []byte {
	return argon2.IDKey([]byte(password), salt, time, memory, threads, keyLen)
}

func encodePHC(p phc) string {
	b64 := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s", argon2.Version,
		p.memory, p.time, p.threads, b64.EncodeToString(p.salt), b64.EncodeToString(p.key))
}

// parsePHC reads $argon2id$v=19$m=..,t=..,p=..$salt$hash.
func parsePHC(s string) (phc, error) {
	bad := errors.New("not an argon2id hash")
	parts := strings.Split(s, "$")
	if len(parts) != 6 || parts[0] != "" || parts[1] != "argon2id" || parts[2] != "v="+strconv.Itoa(argon2.Version) {
		return phc{}, bad
	}
	var p phc
	params := strings.Split(parts[3], ",")
	if len(params) != 3 {
		return phc{}, bad
	}
	for i, name := range []string{"m=", "t=", "p="} {
		if !strings.HasPrefix(params[i], name) {
			return phc{}, bad
		}
		n, err := strconv.ParseUint(params[i][len(name):], 10, 32)
		if err != nil || n == 0 {
			return phc{}, bad
		}
		switch i {
		case 0:
			p.memory = uint32(n)
		case 1:
			p.time = uint32(n)
		case 2:
			if n > 255 {
				return phc{}, bad
			}
			p.threads = uint8(n)
		}
	}
	var err error
	if p.salt, err = base64.RawStdEncoding.DecodeString(parts[4]); err != nil || len(p.salt) == 0 {
		return phc{}, bad
	}
	if p.key, err = base64.RawStdEncoding.DecodeString(parts[5]); err != nil || len(p.key) == 0 {
		return phc{}, bad
	}
	return p, nil
}

// hashPassword returns a new PHC string with a fresh random salt.
func hashPassword(password string) (string, error) {
	salt := make([]byte, argonSaltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	return encodePHC(phc{
		memory: argonMemory, time: argonTime, threads: argonThreads, salt: salt,
		key: deriveKey(password, salt, argonMemory, argonTime, argonThreads, argonKeyLen),
	}), nil
}

// verifyPassword reports whether password matches the PHC string. An
// unparseable hash never matches.
func verifyPassword(hash, password string) bool {
	p, err := parsePHC(hash)
	if err != nil {
		return false
	}
	got := deriveKey(password, p.salt, p.memory, p.time, p.threads, uint32(len(p.key)))
	return subtle.ConstantTimeCompare(got, p.key) == 1
}

var (
	dummyOnce sync.Once
	dummyHash string
)

// verifyDummy runs one argon2 verify that always fails, so a login for an
// unknown user takes as long as one for a known user.
func verifyDummy(password string) {
	dummyOnce.Do(func() { dummyHash, _ = hashPassword("dummy password for timing") })
	verifyPassword(dummyHash, password)
}

// validatePassword applies the rules: 12 to 128 characters (runes), valid
// UTF-8, and not the username. No composition rules.
func validatePassword(username, password string) error {
	if !utf8.ValidString(password) {
		return errors.New("the password is not valid text")
	}
	n := utf8.RuneCountInString(password)
	if n < minPasswordLen || n > maxPasswordLen {
		return fmt.Errorf("the password must be %d to %d characters", minPasswordLen, maxPasswordLen)
	}
	if strings.EqualFold(password, username) {
		return errors.New("the password must not be the username")
	}
	return nil
}

var usernameRE = regexp.MustCompile(`^[a-z0-9._-]{3,32}$`)

// normalizeUsername lower-cases name and checks it: 3 to 32 characters of
// a-z, 0-9, dot, underscore and hyphen.
func normalizeUsername(name string) (string, error) {
	u := strings.ToLower(name)
	if !usernameRE.MatchString(u) {
		return "", errors.New("the username must be 3 to 32 characters: letters, digits, dot, underscore or hyphen")
	}
	return u, nil
}
