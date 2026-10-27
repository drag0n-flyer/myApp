package identity_test

import (
	"encoding/hex"
	"strings"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/config"
	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/identity/softauth"
)

const androidFP = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"

func androidApp(t *testing.T, fp string) config.AndroidApp {
	t.Helper()
	b, err := hex.DecodeString(fp)
	if err != nil {
		t.Fatal(err)
	}
	return config.AndroidApp{Package: "org.example.app", Fingerprint: b}
}

func androidOrigin(t *testing.T, fp string) string {
	return androidApp(t, fp).Origin()
}

func TestAcceptsAndroidPlatform(t *testing.T) {
	e := newSignupEnv(t)
	id := e.verifiedSignup(t, "ana")
	body := map[string]any{"signupID": id, "deviceName": "Pixel", "platform": "android"}
	if r := e.c.Do(nil, "POST", "/v1/signup/finish", body); r.Status != 200 {
		t.Fatalf("android: %s", r)
	}
	id = e.verifiedSignup(t, "bob")
	body["signupID"], body["platform"] = id, "windows"
	r := e.c.Do(nil, "POST", "/v1/signup/finish", body)
	if r.Status != 400 || r.Code() != "bad_request" || !strings.Contains(string(r.Body), "platform must be iphone, mac or android") {
		t.Fatalf("windows: %s", r)
	}
}

func TestPasskeyAcceptsAndroidOrigin(t *testing.T) {
	withApp := func(d *core.Deps) { d.Config.AndroidApps = []config.AndroidApp{androidApp(t, androidFP)} }
	e := newSignupEnv(t, withApp)
	id := e.verifiedSignup(t, "ana")
	opts := e.c.Do(nil, "POST", "/v1/signup/passkey/options", map[string]string{"signupID": id})
	good := softauth.New(testRPID, androidOrigin(t, androidFP))
	if _, status, code := e.finish(t, id, good.Create(opts.Body)); status != 200 {
		t.Fatalf("android origin: %d %s", status, code)
	}
	id = e.verifiedSignup(t, "bob")
	opts = e.c.Do(nil, "POST", "/v1/signup/passkey/options", map[string]string{"signupID": id})
	other := softauth.New(testRPID, androidOrigin(t, strings.Repeat("ab", 32)))
	if _, status, _ := e.finish(t, id, other.Create(opts.Body)); status != 400 {
		t.Fatalf("other hash: %d", status)
	}
}

func TestPasskeyAndroidOriginRejectedWhenUnconfigured(t *testing.T) {
	e := newSignupEnv(t)
	id := e.verifiedSignup(t, "ana")
	opts := e.c.Do(nil, "POST", "/v1/signup/passkey/options", map[string]string{"signupID": id})
	a := softauth.New(testRPID, androidOrigin(t, androidFP))
	if _, status, _ := e.finish(t, id, a.Create(opts.Body)); status != 400 {
		t.Fatalf("unconfigured: %d", status)
	}
}

func TestAndroidOriginFormat(t *testing.T) {
	const want = "android:apk-key-hash:AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
	if got := androidOrigin(t, androidFP); got != want {
		t.Fatalf("got %s", got)
	}
}

func TestLinkRequestAcceptsAndroid(t *testing.T) {
	e := newSignupEnv(t)
	r := e.c.Do(nil, "POST", "/v1/link", map[string]string{"deviceName": "Pixel", "platform": "android"})
	if r.Status != 200 {
		t.Fatalf("link android: %s", r)
	}
}
