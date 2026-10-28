// Command link writes the link-v1 test vector (the sealed device-link snapshot) to stdout.
// Standard library only.
//
//	cd tools/vectors && go run ./link > ../../docs/protocol/test-vectors/link-v1.json
//
// Suite: HPKE base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305, info = "link-v1",
// empty AAD. The sealed bytes are enc (32) || ciphertext, base64url without padding.
//
// The plaintext is the exact output of the Swift app's DeviceSync.exportSnapshot encoding (sorted keys,
// standard base64 for Data), taken from LinkVectorTests in the Messaging package; that test fails if the
// two ever differ. Go's crypto/hpke has no deterministic sender, so every run draws a new ephemeral key and
// writes a different sealed value: regenerate only on purpose.
package main

import (
	"crypto/ecdh"
	"crypto/hpke"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
)

const plaintext = `{"blocked":["a0000000-0000-4000-8000-000000000009"],"chats":[{"id":"c0000000-0000-4000-8000-000000000001","isRequest":false,"muted":true},{"id":"c0000000-0000-4000-8000-000000000002","isRequest":true,"muted":false}],"contacts":[{"accountID":"a0000000-0000-4000-8000-000000000002","addedAt":1793093400000,"displayName":"Bob Ünal","isContact":true,"username":"bob"},{"accountID":"a0000000-0000-4000-8000-000000000003","addedAt":1793093465000,"displayName":"Carol","isContact":false,"username":"carol"}],"folders":[{"chatIDs":["c0000000-0000-4000-8000-000000000001"],"id":"f0000000-0000-4000-8000-000000000001","name":"Family","position":0}],"settings":{"setting.readReceipts":"ZmFsc2U="},"v":1}`

type sealedCase struct {
	PlaintextJSON string `json:"plaintextJSON"`
	SealedB64     string `json:"sealedB64"`
}

type file struct {
	Version                int          `json:"version"`
	Note                   string       `json:"note"`
	Info                   string       `json:"info"`
	RecipientPrivateKeyHex string       `json:"recipientPrivateKeyHex"`
	RecipientPublicKeyB64  string       `json:"recipientPublicKeyB64"`
	Cases                  []sealedCase `json:"cases"`
}

func main() {
	priv := make([]byte, 32)
	for i := range priv {
		priv[i] = byte(0x60 + i)
	}
	sk, err := ecdh.X25519().NewPrivateKey(priv)
	must(err)
	pub, err := hpke.NewDHKEMPublicKey(sk.PublicKey())
	must(err)
	sealed, err := hpke.Seal(pub, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), []byte("link-v1"), []byte(plaintext))
	must(err)
	b64 := base64.RawURLEncoding
	out, err := json.MarshalIndent(file{
		Version:                1,
		Note:                   "TEST ONLY — published keys",
		Info:                   "link-v1",
		RecipientPrivateKeyHex: hex.EncodeToString(priv),
		RecipientPublicKeyB64:  b64.EncodeToString(sk.PublicKey().Bytes()),
		Cases:                  []sealedCase{{PlaintextJSON: plaintext, SealedB64: b64.EncodeToString(sealed)}},
	}, "", "  ")
	must(err)
	fmt.Println(string(out))
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
