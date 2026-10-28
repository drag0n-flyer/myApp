package admin

import (
	"crypto/hmac"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/base32"
	"encoding/binary"
	"fmt"
	"net/url"
	"strings"
	"time"
)

const (
	totpPeriod    = 30 // seconds per step
	totpSecretLen = 20 // bytes
)

var totpBase32 = base32.StdEncoding.WithPadding(base32.NoPadding)

// totpCode is the 6-digit code of RFC 6238 (HMAC-SHA1, 30 s steps) for a
// time step.
func totpCode(secret []byte, step int64) string {
	var msg [8]byte
	binary.BigEndian.PutUint64(msg[:], uint64(step))
	mac := hmac.New(sha1.New, secret)
	mac.Write(msg[:])
	sum := mac.Sum(nil)
	off := sum[len(sum)-1] & 0x0f
	n := binary.BigEndian.Uint32(sum[off:off+4]) & 0x7fffffff
	return fmt.Sprintf("%06d", n%1000000)
}

// totpMatch checks code against the steps just before, at and just after now.
// A step that is not newer than lastStep is a replay and never matches. It
// returns the matching step. All three steps are always compared.
func totpMatch(secret []byte, code string, now time.Time, lastStep int64) (step int64, ok bool) {
	if len(code) != 6 {
		return 0, false
	}
	cur := now.Unix() / totpPeriod
	for s := cur - 1; s <= cur+1; s++ {
		if subtle.ConstantTimeCompare([]byte(code), []byte(totpCode(secret, s))) == 1 && s > lastStep && !ok {
			step, ok = s, true
		}
	}
	return step, ok
}

// otpauthURL is what an authenticator app reads from the QR code.
func otpauthURL(host, username, secretBase32 string) string {
	return "otpauth://totp/" + labelEscape(host) + ":" + labelEscape(username) +
		"?secret=" + secretBase32 + "&issuer=" + url.QueryEscape(host) + "&algorithm=SHA1&digits=6&period=30"
}

// labelEscape escapes one part of the otpauth label. PathEscape leaves ":"
// alone, but the label is "issuer:account", so a port in the host would make
// it ambiguous.
func labelEscape(s string) string {
	return strings.ReplaceAll(url.PathEscape(s), ":", "%3A")
}
