package push

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/asn1"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/pushproto"
)

const (
	maxEndpointChars = 1024
	recordSize       = 4096 // RFC 8030 push services accept at least 4096 bytes
	vapidLifetime    = 12 * time.Hour
)

// upRegistration is what the server stores for a UnifiedPush device: the
// distributor endpoint and the receiver's Web Push keys. It is stored as JSON
// in push_tokens.sealed (it is not sealed: the server must read it).
type upRegistration struct {
	Endpoint string      `json:"endpoint"`
	P256dh   httpx.Bytes `json:"p256dh"`
	Auth     httpx.Bytes `json:"auth"`
}

// validateEndpoint checks an endpoint URL at upload time.
func validateEndpoint(s string) (*url.URL, error) {
	if len(s) == 0 || len(s) > maxEndpointChars {
		return nil, errors.New("endpoint must be 1 to 1024 characters")
	}
	u, err := url.Parse(s)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
		return nil, errors.New("endpoint must be an https URL")
	}
	return u, nil
}

// validateUploadEndpoint adds the port rule. At send time the dialer enforces
// the port on the connection itself.
func validateUploadEndpoint(s string) (*url.URL, error) {
	u, err := validateEndpoint(s)
	if err != nil {
		return nil, err
	}
	if p := u.Port(); p != "" && p != "443" {
		return nil, errors.New("endpoint must use port 443")
	}
	return u, nil
}

func validateKeys(p256dh, auth []byte) error {
	if _, err := ecdh.P256().NewPublicKey(p256dh); err != nil {
		return errors.New("p256dh must be an uncompressed P-256 public key")
	}
	if len(auth) != 16 {
		return errors.New("auth must be 16 bytes")
	}
	return nil
}

// encryptAES128GCM encrypts data for a Web Push receiver (RFC 8291, with the
// RFC 8188 aes128gcm content coding) in a single record.
func encryptAES128GCM(uaPub, auth, data []byte, asPriv *ecdh.PrivateKey, salt []byte) ([]byte, error) {
	if len(data)+1+16 > recordSize-86 {
		return nil, errors.New("push: payload too large")
	}
	ua, err := ecdh.P256().NewPublicKey(uaPub)
	if err != nil {
		return nil, err
	}
	secret, err := asPriv.ECDH(ua)
	if err != nil {
		return nil, err
	}
	asPub := asPriv.PublicKey().Bytes()
	prkKey, err := hkdf.Extract(sha256.New, secret, auth)
	if err != nil {
		return nil, err
	}
	info := append(append([]byte("WebPush: info\x00"), uaPub...), asPub...)
	ikm, err := hkdf.Expand(sha256.New, prkKey, string(info), 32)
	if err != nil {
		return nil, err
	}
	prk, err := hkdf.Extract(sha256.New, ikm, salt)
	if err != nil {
		return nil, err
	}
	cek, err := hkdf.Expand(sha256.New, prk, "Content-Encoding: aes128gcm\x00", 16)
	if err != nil {
		return nil, err
	}
	nonce, err := hkdf.Expand(sha256.New, prk, "Content-Encoding: nonce\x00", 12)
	if err != nil {
		return nil, err
	}
	blk, err := aes.NewCipher(cek)
	if err != nil {
		return nil, err
	}
	g, err := cipher.NewGCM(blk)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 0, 86+len(data)+1+16)
	out = append(out, salt...)
	out = binary.BigEndian.AppendUint32(out, recordSize)
	out = append(out, byte(len(asPub)))
	out = append(out, asPub...)
	plain := append(append([]byte(nil), data...), 0x02) // last-record delimiter, no padding
	return g.Seal(out, nonce, plain, nil), nil
}

func encrypt(uaPub, auth, data []byte) ([]byte, error) {
	asPriv, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return nil, err
	}
	return encryptAES128GCM(uaPub, auth, data, asPriv, salt)
}

// ---- VAPID (RFC 8292) ----

// WriteVAPIDKey writes a P-256 private key as PKCS#8 PEM.
func WriteVAPIDKey(w io.Writer, k *ecdsa.PrivateKey) error {
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		return err
	}
	return pem.Encode(w, &pem.Block{Type: "PRIVATE KEY", Bytes: der})
}

func readVAPIDKey(path string) (*ecdsa.PrivateKey, error) {
	if path == "" {
		return nil, errors.New("push: no VAPID key file configured")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("push: read VAPID key: %w", err)
	}
	blk, _ := pem.Decode(b)
	if blk == nil {
		return nil, errors.New("push: VAPID key is not PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if err != nil {
		return nil, errors.New("push: VAPID key is not PKCS#8")
	}
	ek, ok := k.(*ecdsa.PrivateKey)
	if !ok || ek.Curve != elliptic.P256() {
		return nil, errors.New("push: VAPID key is not P-256")
	}
	return ek, nil
}

func vapidPublic(k *ecdsa.PrivateKey) ([]byte, error) {
	pub, err := k.PublicKey.ECDH()
	if err != nil {
		return nil, err
	}
	return pub.Bytes(), nil
}

// VAPIDPublicKey returns the base64url (unpadded) uncompressed public key of
// the VAPID key file, for the server info document.
func VAPIDPublicKey(path string) (string, error) {
	k, err := readVAPIDKey(path)
	if err != nil {
		return "", err
	}
	pub, err := vapidPublic(k)
	if err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(pub), nil
}

func (m *Module) loadVAPID() (*ecdsa.PrivateKey, error) {
	m.keyMu.Lock()
	defer m.keyMu.Unlock()
	if m.vapid != nil {
		return m.vapid, nil
	}
	k, err := readVAPIDKey(m.d.Config.VapidKeyFile)
	if err != nil {
		return nil, err
	}
	m.vapid = k
	return k, nil
}

// vapidHeader returns the Authorization header value for an endpoint.
func (m *Module) vapidHeader(endpoint *url.URL) (string, error) {
	k, err := m.loadVAPID()
	if err != nil {
		return "", err
	}
	pub, err := vapidPublic(k)
	if err != nil {
		return "", err
	}
	enc := base64.RawURLEncoding
	hdr := enc.EncodeToString([]byte(`{"typ":"JWT","alg":"ES256"}`))
	claims, err := json.Marshal(map[string]any{
		"aud": endpoint.Scheme + "://" + endpoint.Host,
		"exp": m.d.Now().Add(vapidLifetime).Unix(),
		"sub": "https://" + m.d.Config.Host,
	})
	if err != nil {
		return "", err
	}
	signing := hdr + "." + enc.EncodeToString(claims)
	h := sha256.Sum256([]byte(signing))
	der, err := ecdsa.SignASN1(rand.Reader, k, h[:])
	if err != nil {
		return "", err
	}
	var rs struct{ R, S *big.Int }
	if _, err := asn1.Unmarshal(der, &rs); err != nil {
		return "", err
	}
	sig := make([]byte, 64)
	rs.R.FillBytes(sig[:32])
	rs.S.FillBytes(sig[32:])
	return "vapid t=" + signing + "." + enc.EncodeToString(sig) + ", k=" + enc.EncodeToString(pub), nil
}

// sendUnifiedPush delivers one push to a UnifiedPush distributor endpoint.
// Errors never contain the endpoint, keys or payload.
func (m *Module) sendUnifiedPush(ctx context.Context, deviceID string, kind core.PushKind, stored []byte, payload []byte) error {
	var reg upRegistration
	if err := json.Unmarshal(stored, &reg); err != nil {
		return errors.New("push: bad unifiedpush registration")
	}
	ep, err := validateEndpoint(reg.Endpoint)
	if err != nil {
		return errors.New("push: bad unifiedpush registration")
	}
	kindByte, ttl := byte(0x01), 86400
	if kind == core.PushCall {
		kindByte, ttl = 0x02, 45
	}
	plain := append([]byte{kindByte}, pushproto.Pad(payload)...)
	body, err := encrypt(reg.P256dh, reg.Auth, plain)
	if err != nil {
		return errors.New("push: unifiedpush encryption failed")
	}
	auth, err := m.vapidHeader(ep)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, ep.String(), bytes.NewReader(body))
	if err != nil {
		return errors.New("push: bad unifiedpush registration")
	}
	req.Header.Set("Content-Encoding", "aes128gcm")
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("TTL", strconv.Itoa(ttl))
	req.Header.Set("Urgency", "high")
	req.Header.Set("Authorization", auth)
	resp, err := m.up.Do(req)
	if err != nil {
		return errors.New("push: unifiedpush request failed") // the error text would carry the URL
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<14))
	switch {
	case resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone:
		_, err := m.d.DB.Exec(ctx,
			`delete from push_tokens where device_id = $1 and kind = 'unifiedpush' and sealed = $2`, deviceID, stored)
		return err
	case resp.StatusCode/100 != 2:
		return fmt.Errorf("push: unifiedpush endpoint answered %d", resp.StatusCode)
	}
	return nil
}
