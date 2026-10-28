package admin

import (
	"strings"
	"testing"
)

func TestPasswordHashRoundTrip(t *testing.T) {
	h, err := hashPassword("correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(h, "$argon2id$v=19$m=19456,t=2,p=1$") || strings.Count(h, "$") != 5 {
		t.Errorf("hash = %q", h)
	}
	if !verifyPassword(h, "correct horse battery") {
		t.Error("right password rejected")
	}
	if verifyPassword(h, "correct horse batterY") || verifyPassword(h, "") {
		t.Error("wrong password accepted")
	}
	h2, _ := hashPassword("correct horse battery")
	if h == h2 {
		t.Error("two hashes share a salt")
	}
}

func TestPasswordPHCParsing(t *testing.T) {
	h, _ := hashPassword("correct horse battery")
	p, err := parsePHC(h)
	if err != nil || p.memory != 19456 || p.time != 2 || p.threads != 1 || len(p.salt) != 16 || len(p.key) != 32 {
		t.Fatalf("parsed = %+v, %v", p, err)
	}
	for _, bad := range []string{
		"", "plain", "$argon2i$v=19$m=19456,t=2,p=1$AAAA$AAAA", "$argon2id$v=18$m=19456,t=2,p=1$AAAA$AAAA",
		"$argon2id$v=19$m=x,t=2,p=1$AAAA$AAAA", "$argon2id$v=19$m=19456,t=2$AAAA$AAAA",
		"$argon2id$v=19$m=19456,t=2,p=1$!!$AAAA", "$argon2id$v=19$m=19456,t=2,p=1$AAAA",
	} {
		if _, err := parsePHC(bad); err == nil {
			t.Errorf("parsePHC(%q) succeeded", bad)
		}
		if verifyPassword(bad, "x") {
			t.Errorf("verifyPassword accepted %q", bad)
		}
	}
}

func TestPasswordOtherParametersStillVerify(t *testing.T) {
	salt := []byte("0123456789abcdef")
	h := encodePHC(phc{memory: 8192, time: 1, threads: 2, salt: salt, key: deriveKey("a long enough one", salt, 8192, 1, 2, 24)})
	if !strings.Contains(h, "m=8192,t=1,p=2") {
		t.Fatalf("hash = %q", h)
	}
	if !verifyPassword(h, "a long enough one") || verifyPassword(h, "a long enough two") {
		t.Error("hash with other parameters does not verify")
	}
}

func TestPasswordRules(t *testing.T) {
	for pw, ok := range map[string]bool{
		"short":                            false,
		"elevenchars":                      false, // 11
		"twelve chars":                     true,
		strings.Repeat("a", 128):           true,
		strings.Repeat("a", 129):           false,
		strings.Repeat("é", 12):            true, // counted in runes
		strings.Repeat("é", 128):           true,
		strings.Repeat("é", 129):           false,
		"alice.example":                    false, // equals the username
		"\xff\xfe invalid utf8 bytes here": false,
	} {
		err := validatePassword("alice.example", pw)
		if (err == nil) != ok {
			t.Errorf("validatePassword(%q) = %v, want ok=%v", pw, err, ok)
		}
	}
}

func TestUsernameRules(t *testing.T) {
	for in, want := range map[string]string{"Alice": "alice", "a.b-c_9": "a.b-c_9", "abc": "abc", strings.Repeat("a", 32): strings.Repeat("a", 32)} {
		if got, err := normalizeUsername(in); err != nil || got != want {
			t.Errorf("normalizeUsername(%q) = %q, %v", in, got, err)
		}
	}
	for _, in := range []string{"", "ab", strings.Repeat("a", 33), "a b", "ali/ce", "alicé", "a@b.c"} {
		if _, err := normalizeUsername(in); err == nil {
			t.Errorf("normalizeUsername(%q) succeeded", in)
		}
	}
}
