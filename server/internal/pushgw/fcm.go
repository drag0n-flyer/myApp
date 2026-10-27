package pushgw

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/pushproto"
)

const (
	fcmDefaultBase   = "https://fcm.googleapis.com"
	fcmScope         = "https://www.googleapis.com/auth/firebase.messaging"
	fcmTimeout       = 10 * time.Second
	fcmTokenEarly    = 5 * time.Minute // use a cached access token until this long before it expires
	fcmMinMintAge    = time.Minute     // never mint twice within this time, even after a 401
	fcmMaxDataBytes  = 4096            // FCM's limit on the data of one message
	fcmMaxPaddedSize = 2048            // a larger padded payload is replaced by the empty one
	fcmCallTTL       = "45s"
	fcmMessageTTL    = "86400s"
	fcmMaxKeyFile    = 64 << 10
)

type fcmClient struct {
	project string
	email   string
	key     *rsa.PrivateKey
	tokURI  string
	base    string
	hc      *http.Client
	now     func() time.Time

	mu      sync.Mutex
	access  string
	expires time.Time // when the cached access token stops being valid
	minted  time.Time
}

// NewFCMClient returns an FCM HTTP v1 sender. keyJSON is a Google
// service-account key file (client_email, private_key as PKCS#8 RSA PEM,
// token_uri over https). With an empty baseURL it uses fcm.googleapis.com. A
// nil httpClient uses one with a 10 s timeout that follows no redirects, and a
// nil now uses the system clock. The key and the access token are never
// logged and never appear in errors.
func NewFCMClient(projectID string, keyJSON []byte, baseURL string, httpClient *http.Client, now func() time.Time) (FCM, error) {
	if projectID == "" || strings.ContainsAny(projectID, "/?#% ") {
		return nil, errors.New("pushgw: FCM project ID is not valid")
	}
	if len(keyJSON) > fcmMaxKeyFile {
		return nil, errors.New("pushgw: FCM key file is too large")
	}
	var f struct {
		Email    string `json:"client_email"`
		Key      string `json:"private_key"`
		TokenURI string `json:"token_uri"`
	}
	if json.Unmarshal(keyJSON, &f) != nil || f.Email == "" || f.Key == "" {
		return nil, errors.New("pushgw: FCM key file is not a service-account key")
	}
	u, err := url.Parse(f.TokenURI)
	if err != nil || u.Scheme != "https" || u.Host == "" {
		return nil, errors.New("pushgw: FCM token_uri must be an https URL")
	}
	blk, _ := pem.Decode([]byte(f.Key))
	if blk == nil {
		return nil, errors.New("pushgw: FCM private key is not PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if err != nil {
		return nil, errors.New("pushgw: FCM private key is not PKCS#8")
	}
	rk, ok := k.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("pushgw: FCM private key must be RSA")
	}
	// A redirect must not carry the access token or the JWT to another host, so
	// the policy is set even on a client the caller passes in (a copy of it).
	if httpClient == nil {
		httpClient = &http.Client{Timeout: fcmTimeout}
	} else {
		cp := *httpClient
		httpClient = &cp
	}
	httpClient.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	if now == nil {
		now = time.Now
	}
	if baseURL == "" {
		baseURL = fcmDefaultBase
	}
	return &fcmClient{project: projectID, email: f.Email, key: rk, tokURI: f.TokenURI,
		base: strings.TrimRight(baseURL, "/"), hc: httpClient, now: now}, nil
}

// accessToken returns the cached OAuth access token, or exchanges a new
// service-account JWT for one when the cache is within 5 minutes of expiry.
func (c *fcmClient) accessToken(ctx context.Context) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if c.access != "" && now.Before(c.expires) {
		return c.access, nil
	}
	if !c.minted.IsZero() && now.Before(c.minted.Add(fcmMinMintAge)) && !now.Before(c.minted) {
		return "", errors.New("pushgw: FCM access token was refused recently")
	}
	hdr, _ := json.Marshal(struct {
		Alg string `json:"alg"`
		Typ string `json:"typ"`
	}{"RS256", "JWT"})
	claims, _ := json.Marshal(struct {
		Iss   string `json:"iss"`
		Scope string `json:"scope"`
		Aud   string `json:"aud"`
		Iat   int64  `json:"iat"`
		Exp   int64  `json:"exp"`
	}{c.email, fcmScope, c.tokURI, now.Unix(), now.Add(time.Hour).Unix()})
	signing := b64url(hdr) + "." + b64url(claims)
	sum := sha256.Sum256([]byte(signing))
	sig, err := rsa.SignPKCS1v15(rand.Reader, c.key, crypto.SHA256, sum[:])
	if err != nil {
		return "", errors.New("pushgw: FCM JWT signing failed")
	}
	form := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {signing + "." + b64url(sig)},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.tokURI, strings.NewReader(form.Encode()))
	if err != nil {
		return "", errors.New("pushgw: bad FCM token request")
	}
	req.Header.Set("content-type", "application/x-www-form-urlencoded")
	c.minted = now
	resp, err := c.hc.Do(req)
	if err != nil {
		return "", errors.New("pushgw: FCM token request failed")
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 16<<10))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("pushgw: FCM token endpoint answered %d", resp.StatusCode)
	}
	var out struct {
		Access  string `json:"access_token"`
		Expires int64  `json:"expires_in"`
	}
	if json.Unmarshal(raw, &out) != nil || out.Access == "" || out.Expires <= 0 {
		return "", errors.New("pushgw: FCM token answer is not valid")
	}
	c.access = out.Access
	life := time.Duration(min(out.Expires, 3600)) * time.Second
	if life > fcmTokenEarly {
		life -= fcmTokenEarly
	} else {
		life /= 2 // a short-lived token is still cached for part of its life
	}
	c.expires = now.Add(life)
	return c.access, nil
}

// forgetToken drops the cached access token after a 401, but only once the
// token is at least fcmMinMintAge old, so a bad key cannot make a request storm.
func (c *fcmClient) forgetToken() {
	c.mu.Lock()
	if c.now().Sub(c.minted) >= fcmMinMintAge {
		c.access = ""
	}
	c.mu.Unlock()
}

type fcmBody struct {
	Message struct {
		Token string `json:"token"`
		Data  struct {
			K string `json:"k"`
			D string `json:"d"`
		} `json:"data"`
		And struct {
			Priority string `json:"priority"`
			TTL      string `json:"ttl"`
		} `json:"android"`
	} `json:"message"`
}

// Send delivers one data message. kind is "message" or "call". A padded
// payload over 2048 bytes would not fit FCM's 4096-byte limit once encoded, so
// it is replaced by the 256-byte empty payload (the device then fetches its
// inbox); the gateway needs no plaintext for this. gone is true only for
// UNREGISTERED. Errors never include the token or the payload.
func (c *fcmClient) Send(ctx context.Context, h pushproto.Handle, kind string, data []byte) (bool, error) {
	if h.Token == "" || len(h.Token) > 1024 {
		return true, nil
	}
	ttl := fcmMessageTTL
	switch kind {
	case "message":
	case "call":
		ttl = fcmCallTTL
	default:
		return false, errors.New("pushgw: unknown push kind")
	}
	if len(data) > fcmMaxPaddedSize {
		data = pushproto.Pad(nil)
	}
	var b fcmBody
	b.Message.Token = h.Token
	b.Message.Data.K, b.Message.Data.D = kind, base64.StdEncoding.EncodeToString(data)
	b.Message.And.Priority, b.Message.And.TTL = "HIGH", ttl
	body, _ := json.Marshal(b)
	if len(body) > fcmMaxDataBytes+1024 { // the token and the keys are small; this guards a future change
		return false, errors.New("pushgw: FCM message too large")
	}
	at, err := c.accessToken(ctx)
	if err != nil {
		return false, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.base+"/v1/projects/"+c.project+"/messages:send", bytes.NewReader(body))
	if err != nil {
		return false, errors.New("pushgw: bad FCM request")
	}
	req.Header.Set("authorization", "Bearer "+at)
	req.Header.Set("content-type", "application/json")
	resp, err := c.hc.Do(req)
	if err != nil {
		return false, errors.New("pushgw: FCM request failed")
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	var e struct {
		Error struct {
			Status  string `json:"status"`
			Details []struct {
				Code string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	_ = json.Unmarshal(raw, &e)
	code := e.Error.Status
	for _, d := range e.Error.Details {
		if d.Code == "UNREGISTERED" {
			code = d.Code
		}
	}
	switch {
	case resp.StatusCode == http.StatusOK:
		return false, nil
	case code == "UNREGISTERED":
		// Only UNREGISTERED proves the token is dead. A bare 404 or NOT_FOUND
		// can come from a wrong URL, proxy or project and must not delete
		// every device's handle.
		return true, nil
	case resp.StatusCode == http.StatusUnauthorized:
		c.forgetToken()
	}
	return false, fmt.Errorf("pushgw: FCM answered %d %q", resp.StatusCode, code)
}
