package pushgw

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/pushproto"
)

const (
	apnsProduction = "https://api.push.apple.com"
	apnsSandbox    = "https://api.sandbox.push.apple.com"
	jwtLifetime    = 50 * time.Minute
	minTokenAge    = 20 * time.Minute
	apnsTimeout    = 10 * time.Second
)

type apnsClient struct {
	key    *ecdsa.PrivateKey
	keyID  string
	teamID string
	base   string
	hc     *http.Client
	now    func() time.Time

	mu       sync.Mutex
	jwt      string
	jwtIssue time.Time
}

// NewAPNsClient returns an APNs sender that authenticates with the .p8 key in
// keyPEM (PKCS#8, ECDSA P-256). With an empty baseURL it uses Apple's
// production or sandbox host, chosen by each handle's Env. A nil httpClient
// uses one with a 10 s timeout, and a nil now uses the system clock.
func NewAPNsClient(keyPEM []byte, keyID, teamID, baseURL string, httpClient *http.Client, now func() time.Time) (APNs, error) {
	blk, _ := pem.Decode(keyPEM)
	if blk == nil {
		return nil, errors.New("pushgw: APNs key is not PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if err != nil {
		return nil, errors.New("pushgw: APNs key is not PKCS#8")
	}
	ek, ok := k.(*ecdsa.PrivateKey)
	if !ok || ek.Curve != elliptic.P256() {
		return nil, errors.New("pushgw: APNs key must be ECDSA P-256")
	}
	if httpClient == nil {
		// A redirect must not carry the provider token to another host.
		httpClient = &http.Client{
			Timeout:       apnsTimeout,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		}
	}
	if now == nil {
		now = time.Now
	}
	return &apnsClient{key: ek, keyID: keyID, teamID: teamID, base: baseURL, hc: httpClient, now: now}, nil
}

func b64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// token returns the cached provider JWT, making a new one after 50 minutes.
func (c *apnsClient) token() (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if c.jwt != "" && now.Sub(c.jwtIssue) < jwtLifetime && !now.Before(c.jwtIssue) {
		return c.jwt, nil
	}
	hdr, _ := json.Marshal(struct {
		Alg string `json:"alg"`
		Kid string `json:"kid"`
	}{"ES256", c.keyID})
	claims, _ := json.Marshal(struct {
		Iss string `json:"iss"`
		Iat int64  `json:"iat"`
	}{c.teamID, now.Unix()})
	signing := b64url(hdr) + "." + b64url(claims)
	sum := sha256.Sum256([]byte(signing))
	r, s, err := ecdsa.Sign(rand.Reader, c.key, sum[:])
	if err != nil {
		return "", err
	}
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	c.jwt, c.jwtIssue = signing+"."+b64url(sig), now
	return c.jwt, nil
}

// forgetToken drops the cached provider token so the next send makes a new
// one, but at most once per minTokenAge: APNs refuses a new token more often
// than every 20 minutes, so re-minting sooner only makes things worse.
func (c *apnsClient) forgetToken() {
	c.mu.Lock()
	if c.now().Sub(c.jwtIssue) >= minTokenAge {
		c.jwt = ""
	}
	c.mu.Unlock()
}

func isHex(s string) bool {
	if s == "" {
		return false
	}
	for i := 0; i < len(s); i++ {
		ch := s[i]
		if !(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f' || ch >= 'A' && ch <= 'F') {
			return false
		}
	}
	return true
}

type alertBody struct {
	APS struct {
		Alert struct {
			Title string `json:"title"`
			Body  string `json:"body"`
		} `json:"alert"`
		MutableContent int    `json:"mutable-content"`
		Sound          string `json:"sound"`
	} `json:"aps"`
	D string `json:"d"`
}

// Send delivers one push. The handle's own Kind decides the push type: a
// voip handle gets a VoIP push, an alert handle an alert push. gone is true
// for 410 and for 400 BadDeviceToken. Errors never include the token.
func (c *apnsClient) Send(ctx context.Context, h pushproto.Handle, _ string, data []byte) (bool, error) {
	// The token goes into the URL path, so it must be plain hex.
	if !isHex(h.Token) {
		return true, nil
	}
	base := c.base
	if base == "" {
		base = apnsProduction
		if h.Env == "sandbox" {
			base = apnsSandbox
		}
	}
	d := base64.StdEncoding.EncodeToString(data)
	var body []byte
	hdr := http.Header{}
	hdr.Set("apns-priority", "10")
	switch h.Kind {
	case "voip":
		body, _ = json.Marshal(struct {
			D string `json:"d"`
		}{d})
		hdr.Set("apns-push-type", "voip")
		hdr.Set("apns-expiration", "0")
		hdr.Set("apns-topic", h.Bundle+".voip")
	case "alert":
		var b alertBody
		b.APS.Alert.Title, b.APS.Alert.Body = "[App]", "New message"
		b.APS.MutableContent, b.APS.Sound, b.D = 1, "default", d
		body, _ = json.Marshal(b)
		hdr.Set("apns-push-type", "alert")
		hdr.Set("apns-topic", h.Bundle)
	default:
		return false, errors.New("pushgw: unknown handle kind")
	}
	jwt, err := c.token()
	if err != nil {
		return false, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, base+"/3/device/"+h.Token, bytes.NewReader(body))
	if err != nil {
		return false, errors.New("pushgw: bad APNs request")
	}
	req.Header = hdr
	req.Header.Set("authorization", "bearer "+jwt)
	req.Header.Set("content-type", "application/json")
	resp, err := c.hc.Do(req)
	if err != nil {
		return false, errors.New("pushgw: APNs request failed")
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	var reason struct {
		Reason string `json:"reason"`
	}
	_ = json.Unmarshal(raw, &reason)
	switch {
	case resp.StatusCode == http.StatusOK:
		return false, nil
	case resp.StatusCode == http.StatusGone,
		resp.StatusCode == http.StatusBadRequest && reason.Reason == "BadDeviceToken":
		return true, nil
	}
	if resp.StatusCode == http.StatusForbidden && reason.Reason == "ExpiredProviderToken" {
		c.forgetToken()
	}
	return false, fmt.Errorf("pushgw: APNs answered %s %q", strconv.Itoa(resp.StatusCode), reason.Reason)
}
