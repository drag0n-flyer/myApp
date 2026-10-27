package softauth

import (
	"bytes"
	"testing"
)

func TestSoftauthCBOR(t *testing.T) {
	got := encodeCBOR(cborMap{{1, 2}, {3, -7}})
	if want := []byte{0xa2, 0x01, 0x02, 0x03, 0x26}; !bytes.Equal(got, want) {
		t.Fatalf("got %x, want %x", got, want)
	}
	if got := encodeCBOR("fmt"); !bytes.Equal(got, []byte{0x63, 'f', 'm', 't'}) {
		t.Fatalf("text %x", got)
	}
	if got := encodeCBOR(make([]byte, 30)); !bytes.HasPrefix(got, []byte{0x58, 30}) {
		t.Fatalf("long bytes %x", got[:3])
	}
}
