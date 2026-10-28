// Command vectors writes cross-language test vectors. Standard library only.
//
//	go run . ../../docs/protocol/vectors/push-handle-v1.json
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
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: vectors <output file>")
		os.Exit(2)
	}
	priv := make([]byte, 32)
	for i := range priv {
		priv[i] = byte(i + 1)
	}
	sk, err := ecdh.X25519().NewPrivateKey(priv)
	must(err)
	pub := sk.PublicKey()
	pt := `{"t":"00ff","k":"alert","e":"sandbox","b":"org.example.app"}`

	hpkePub, err := hpke.NewDHKEMPublicKey(pub)
	must(err)
	sealed, err := hpke.Seal(hpkePub, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), []byte("push-handle-v1"), []byte(pt))
	must(err)

	out, err := json.MarshalIndent(map[string]string{
		"privateKey": hex.EncodeToString(priv),
		"publicKey":  hex.EncodeToString(pub.Bytes()),
		"plaintext":  pt,
		"sealed":     base64.RawURLEncoding.EncodeToString(sealed),
	}, "", "  ")
	must(err)
	must(os.WriteFile(os.Args[1], append(out, '\n'), 0o644))
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
