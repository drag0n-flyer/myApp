// Command escrow writes the escrow-v1 cross-language test vector to stdout. Standard library only.
//
//	cd tools/vectors && go run ./escrow > ../../apps/apple/Packages/Messaging/Tests/MessagingTests/Resources/escrow-vector.json
//
// Suite: HPKE base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305,
// info = "escrow-v1". The sealed bytes are enc (32) || ciphertext.
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

func main() {
	priv := make([]byte, 32)
	for i := range priv {
		priv[i] = byte(0xa0 + i)
	}
	sk, err := ecdh.X25519().NewPrivateKey(priv)
	must(err)
	pub, err := hpke.NewDHKEMPublicKey(sk.PublicKey())
	must(err)
	pt := `{"body":{"at":1700000000000,"id":"0b7e1c52-3c43-4c1b-9d7e-5f5a2f0c9a11","k":"text","t":"hello escrow","v":1},"conversationID":"conv-1","senderAccountID":"acc-ana","sentAt":1700000000000}`
	sealed, err := hpke.Seal(pub, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), []byte("escrow-v1"), []byte(pt))
	must(err)
	out, err := json.MarshalIndent(map[string]string{
		"privateKey": hex.EncodeToString(priv),
		"publicKey":  hex.EncodeToString(sk.PublicKey().Bytes()),
		"plaintext":  pt,
		"sealed":     base64.RawURLEncoding.EncodeToString(sealed),
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
