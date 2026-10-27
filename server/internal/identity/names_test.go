package identity

import (
	"strings"
	"testing"
)

func TestValidUsername(t *testing.T) {
	for s, want := range map[string]bool{
		"ana": true, "ana_b2": true, "a" + strings.Repeat("b", 31): true,
		"ab": false, "Ana": false, "1ana": false, "a" + strings.Repeat("b", 32): false, "an-a": false,
	} {
		if got := ValidUsername(s); got != want {
			t.Errorf("ValidUsername(%q) = %v, want %v", s, got, want)
		}
	}
}

func TestNormalizeEmail(t *testing.T) {
	got, ok := NormalizeEmail(" Ana@Example.ORG ")
	if !ok || got != "ana@example.org" {
		t.Fatalf("got %q, %v", got, ok)
	}
	long := strings.Repeat("a", 255-len("@b.co")) + "@b.co"
	for _, s := range []string{"noat", "a@b", "a@@b.c", long} {
		if _, ok := NormalizeEmail(s); ok {
			t.Errorf("NormalizeEmail(%q) accepted", s)
		}
	}
}

func TestReserved(t *testing.T) {
	for _, s := range []string{"admin", "root", "support", "security", "abuse", "system", "help"} {
		if !Reserved(s) {
			t.Errorf("%q not reserved", s)
		}
	}
	if Reserved("ana") {
		t.Error("ana reserved")
	}
}
