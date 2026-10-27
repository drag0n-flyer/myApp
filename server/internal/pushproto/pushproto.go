// Package pushproto holds the wire format shared by the server and the push
// gateway: sealed push handles, body padding and signed requests (spec §7.4).
package pushproto

import (
	"crypto/ed25519"
	"crypto/hpke"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"
)

// handleInfo is the HPKE info string for sealed push handles.
var handleInfo = []byte("push-handle-v1")

// Handle is the plaintext a device seals to the gateway's public key.
type Handle struct {
	Token  string `json:"t"`
	Kind   string `json:"k"`
	Env    string `json:"e"`
	Bundle string `json:"b"`
}

// PushRequest is the body of POST /v1/push.
type PushRequest struct {
	Handle []byte `json:"handle"`
	Kind   string `json:"kind"`
	Data   []byte `json:"data"`
	// Nonce makes every request body, and so every signature, unique even
	// within one second. The gateway ignores it.
	Nonce string `json:"nonce,omitempty"`
}

// SealHandle seals h to pub: DHKEM(X25519), HKDF-SHA256, ChaCha20-Poly1305.
func SealHandle(pub hpke.PublicKey, h Handle) ([]byte, error) {
	pt, err := json.Marshal(h)
	if err != nil {
		return nil, err
	}
	return hpke.Seal(pub, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), handleInfo, pt)
}

// OpenHandle opens a handle sealed by SealHandle.
func OpenHandle(priv hpke.PrivateKey, sealed []byte) (Handle, error) {
	var h Handle
	pt, err := hpke.Open(priv, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), handleInfo, sealed)
	if err != nil {
		return h, err
	}
	if err := json.Unmarshal(pt, &h); err != nil {
		return Handle{}, err
	}
	return h, nil
}

var buckets = [...]int{256, 512, 1024, 2048, 3072}

const maxPayload = 3070 // largest bucket minus the 2-byte length

// Pad returns a 2-byte big-endian length, the payload, then zeros up to the
// smallest bucket. A payload over 3070 bytes is replaced by an empty one.
func Pad(payload []byte) []byte {
	if len(payload) > maxPayload {
		payload = nil
	}
	size := buckets[len(buckets)-1]
	for _, b := range buckets {
		if len(payload)+2 <= b {
			size = b
			break
		}
	}
	out := make([]byte, size)
	binary.BigEndian.PutUint16(out, uint16(len(payload)))
	copy(out[2:], payload)
	return out
}

// Unpad reverses Pad. It requires a bucket-sized input and a consistent length.
func Unpad(b []byte) ([]byte, error) {
	ok := false
	for _, s := range buckets {
		if len(b) == s {
			ok = true
		}
	}
	if !ok {
		return nil, errors.New("pushproto: bad padded size")
	}
	n := int(binary.BigEndian.Uint16(b))
	if n > len(b)-2 {
		return nil, errors.New("pushproto: bad padded length")
	}
	return append([]byte(nil), b[2:2+n]...), nil
}

// SigningString is "v1\n"+method+"\n"+path+"\n"+timestamp+"\n"+hex(sha256(body)).
func SigningString(method, path, timestamp string, body []byte) []byte {
	sum := sha256.Sum256(body)
	return []byte("v1\n" + method + "\n" + path + "\n" + timestamp + "\n" + hex.EncodeToString(sum[:]))
}

// maxSkew is the largest accepted clock difference.
const maxSkew = 300 * time.Second

// Sign sets X-Server-ID, X-Timestamp and X-Signature on req.
func Sign(key ed25519.PrivateKey, req *http.Request, body []byte, serverID string, now time.Time) {
	ts := strconv.FormatInt(now.Unix(), 10)
	sig := ed25519.Sign(key, SigningString(req.Method, req.URL.Path, ts, body))
	req.Header.Set("X-Server-ID", serverID)
	req.Header.Set("X-Timestamp", ts)
	req.Header.Set("X-Signature", base64.StdEncoding.EncodeToString(sig))
}

// Verify checks the signature headers on req and rejects a skew over 300 s.
func Verify(pub ed25519.PublicKey, req *http.Request, body []byte, now time.Time) error {
	if len(pub) != ed25519.PublicKeySize {
		return errors.New("pushproto: bad public key")
	}
	ts := req.Header.Get("X-Timestamp")
	secs, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return errors.New("pushproto: bad timestamp")
	}
	skew := now.Sub(time.Unix(secs, 0))
	if skew > maxSkew || skew < -maxSkew {
		return errors.New("pushproto: timestamp skew")
	}
	sig, err := base64.StdEncoding.DecodeString(req.Header.Get("X-Signature"))
	if err != nil || len(sig) != ed25519.SignatureSize {
		return errors.New("pushproto: bad signature")
	}
	if !ed25519.Verify(pub, SigningString(req.Method, req.URL.Path, ts, body), sig) {
		return errors.New("pushproto: signature mismatch")
	}
	return nil
}
