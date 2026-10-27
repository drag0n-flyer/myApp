// Package softauth is a software WebAuthn authenticator for tests. It makes
// P-256 credentials with "none" attestation and answers registration and
// assertion options the way a platform authenticator would, with user
// presence and user verification set.
package softauth

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/asn1"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"math/big"
)

var b64 = base64.RawURLEncoding

// Authenticator holds one credential. Create it with New, then call Create
// and Get with the options JSON a server sent.
type Authenticator struct {
	rpID, origin string
	key          *ecdsa.PrivateKey
	credID       []byte
	userHandle   []byte
	signCount    uint32
	tampered     bool
}

// New returns an authenticator for the relying party rpID, which reports
// origin as the page that asked.
func New(rpID, origin string) *Authenticator {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		panic(err)
	}
	id := make([]byte, 16)
	_, _ = rand.Read(id)
	return &Authenticator{rpID: rpID, origin: origin, key: key, credID: id}
}

// Tamper makes the next signature invalid.
func (a *Authenticator) Tamper() { a.tampered = true }

// options is the part of the creation or request options that matters here.
// The options may be wrapped in {"publicKey": ...}.
type options struct {
	PublicKey *options `json:"publicKey"`
	Challenge string   `json:"challenge"`
	User      struct {
		ID string `json:"id"`
	} `json:"user"`
}

func parseOptions(raw []byte) options {
	var o options
	if err := json.Unmarshal(raw, &o); err != nil {
		panic("softauth: bad options JSON: " + err.Error())
	}
	if o.PublicKey != nil {
		return *o.PublicKey
	}
	return o
}

func (a *Authenticator) clientData(typ, challenge string) []byte {
	b, _ := json.Marshal(map[string]any{
		"type": typ, "challenge": challenge, "origin": a.origin, "crossOrigin": false,
	})
	return b
}

func (a *Authenticator) authData(flags byte, withKey bool) []byte {
	h := sha256.Sum256([]byte(a.rpID))
	d := append([]byte{}, h[:]...)
	d = append(d, flags)
	d = binary.BigEndian.AppendUint32(d, a.signCount)
	if withKey {
		d = append(d, make([]byte, 16)...) // zero AAGUID
		d = binary.BigEndian.AppendUint16(d, uint16(len(a.credID)))
		d = append(d, a.credID...)
		x := a.key.X.FillBytes(make([]byte, 32))
		y := a.key.Y.FillBytes(make([]byte, 32))
		d = append(d, encodeCBOR(cborMap{{1, 2}, {3, -7}, {-1, 1}, {-2, x}, {-3, y}})...)
	}
	return d
}

// Create answers registration options and returns the registration response
// JSON (flags 0x45: user present, user verified, attested credential data).
func (a *Authenticator) Create(optionsJSON []byte) []byte {
	o := parseOptions(optionsJSON)
	uid, err := b64.DecodeString(o.User.ID)
	if err != nil {
		panic("softauth: bad user id")
	}
	a.userHandle = uid
	a.signCount = 0
	att := encodeCBOR(cborMap{
		{"fmt", "none"},
		{"attStmt", cborMap{}},
		{"authData", a.authData(0x45, true)},
	})
	out, _ := json.Marshal(map[string]any{
		"id": b64.EncodeToString(a.credID), "rawId": b64.EncodeToString(a.credID), "type": "public-key",
		"response": map[string]string{
			"clientDataJSON":    b64.EncodeToString(a.clientData("webauthn.create", o.Challenge)),
			"attestationObject": b64.EncodeToString(att),
		},
	})
	return out
}

// Get answers assertion options and returns the assertion response JSON
// (flags 0x05: user present, user verified). Each call raises the sign count.
func (a *Authenticator) Get(optionsJSON []byte) []byte {
	o := parseOptions(optionsJSON)
	a.signCount++
	ad := a.authData(0x05, false)
	cd := a.clientData("webauthn.get", o.Challenge)
	cdh := sha256.Sum256(cd)
	digest := sha256.Sum256(append(append([]byte{}, ad...), cdh[:]...))
	if a.tampered {
		digest[0] ^= 0xff // sign the wrong bytes
		a.tampered = false
	}
	r, s, err := ecdsa.Sign(rand.Reader, a.key, digest[:])
	if err != nil {
		panic(err)
	}
	sig, _ := asn1.Marshal(struct{ R, S *big.Int }{r, s})
	out, _ := json.Marshal(map[string]any{
		"id": b64.EncodeToString(a.credID), "rawId": b64.EncodeToString(a.credID), "type": "public-key",
		"response": map[string]string{
			"clientDataJSON":    b64.EncodeToString(cd),
			"authenticatorData": b64.EncodeToString(ad),
			"signature":         b64.EncodeToString(sig),
			"userHandle":        b64.EncodeToString(a.userHandle),
		},
	})
	return out
}

// cborPair is one entry of a cborMap; entries are written in the given order.
type cborPair struct{ k, v any }

type cborMap []cborPair

// encodeCBOR encodes int, []byte, string and cborMap values, the only types
// a WebAuthn attestation needs.
func encodeCBOR(v any) []byte {
	switch x := v.(type) {
	case int:
		if x >= 0 {
			return head(0, uint64(x))
		}
		return head(1, uint64(-1-x))
	case []byte:
		return append(head(2, uint64(len(x))), x...)
	case string:
		return append(head(3, uint64(len(x))), x...)
	case cborMap:
		out := head(5, uint64(len(x)))
		for _, p := range x {
			out = append(out, encodeCBOR(p.k)...)
			out = append(out, encodeCBOR(p.v)...)
		}
		return out
	}
	panic(fmt.Sprintf("softauth: cannot encode %T", v))
}

// head writes a CBOR initial byte and argument in the shortest form.
func head(major byte, n uint64) []byte {
	m := major << 5
	switch {
	case n < 24:
		return []byte{m | byte(n)}
	case n < 1<<8:
		return []byte{m | 24, byte(n)}
	case n < 1<<16:
		return binary.BigEndian.AppendUint16([]byte{m | 25}, uint16(n))
	case n < 1<<32:
		return binary.BigEndian.AppendUint32([]byte{m | 26}, uint32(n))
	}
	return binary.BigEndian.AppendUint64([]byte{m | 27}, n)
}
