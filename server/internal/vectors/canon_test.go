package vectors

import (
	"bytes"
	"encoding/json"
	"testing"

	"github.com/velixio/myApp/server/internal/pushproto"
)

// canon makes JSON comparable regardless of key order and white space.
func canon(t testing.TB, b []byte) []byte {
	t.Helper()
	var v any
	if err := json.Unmarshal(b, &v); err != nil {
		t.Fatal(err)
	}
	out, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return bytes.TrimSpace(out)
}

func marshalHandle(h pushproto.Handle) []byte {
	b, _ := json.Marshal(h)
	return b
}

func unmarshalHandle(t testing.TB, b []byte) pushproto.Handle {
	t.Helper()
	var h pushproto.Handle
	if err := json.Unmarshal(b, &h); err != nil {
		t.Fatal(err)
	}
	return h
}
