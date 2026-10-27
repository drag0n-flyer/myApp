package httpx

import (
	"encoding/base64"
	"encoding/json"
	"errors"
)

// Bytes is a byte string that crosses the client API as base64url without
// padding (spec section 2, decision 3). Use it for every binary JSON field.
type Bytes []byte

// MarshalJSON writes b as an unpadded base64url string.
func (b Bytes) MarshalJSON() ([]byte, error) {
	return json.Marshal(base64.RawURLEncoding.EncodeToString(b))
}

// UnmarshalJSON reads an unpadded base64url string. Padding, the standard
// alphabet and non-strings are refused.
func (b *Bytes) UnmarshalJSON(data []byte) error {
	var s string
	if err := json.Unmarshal(data, &s); err != nil {
		return errors.New("expected a base64url string")
	}
	out, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return errors.New("expected base64url without padding")
	}
	*b = out
	return nil
}
