// Command attachment writes the attachment-stream test vector to stdout. Standard library only.
//
//	cd tools/vectors && go run ./attachment > ../../docs/protocol/test-vectors/attachment-stream.json
//
// Plan 7 Design 7 (the STREAM construction, as ChunkedAttachmentCipher in the Swift app does it):
// 65 536 plaintext bytes per chunk; AES-256-GCM, 16-byte tag per chunk; nonce = 0x00000000 || u64be(index);
// AAD = 0x01 on the last chunk and 0x00 otherwise; an empty file is one empty last chunk; the digest is
// SHA-256 over the whole ciphertext. Plaintext byte i is i mod 251. digestB64 is standard base64 with
// padding, as in the `dg` field of a MessageBody. AES-GCM is deterministic, so a rerun gives the same file.
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
)

const chunkSize = 65536

type testCase struct {
	Name                string `json:"name"`
	KeyHex              string `json:"keyHex"`
	PlaintextLength     int    `json:"plaintextLength"`
	PlaintextSeed       string `json:"plaintextSeed"`
	CiphertextSha256Hex string `json:"ciphertextSha256Hex"`
	DigestB64           string `json:"digestB64"`
}

type file struct {
	Version   int        `json:"version"`
	ChunkSize int        `json:"chunkSize"`
	Cases     []testCase `json:"cases"`
}

func seal(key, plain []byte) []byte {
	blk, err := aes.NewCipher(key)
	must(err)
	g, err := cipher.NewGCM(blk)
	must(err)
	var out []byte
	for idx, off := uint64(0), 0; ; idx++ {
		end := min(off+chunkSize, len(plain))
		last := end == len(plain)
		nonce := make([]byte, 12)
		binary.BigEndian.PutUint64(nonce[4:], idx)
		aad := []byte{0}
		if last {
			aad[0] = 1
		}
		out = g.Seal(out, nonce, plain[off:end], aad)
		if last {
			return out
		}
		off = end
	}
}

func main() {
	f := file{Version: 1, ChunkSize: chunkSize}
	for i, c := range []struct {
		n    int
		name string
	}{{0, "empty file"}, {1, "one byte"}, {65536, "exactly one full chunk"}, {65537, "one full chunk and one byte"}} {
		key := make([]byte, 32)
		for j := range key {
			key[j] = byte(0x10*(i+1) + j)
		}
		plain := make([]byte, c.n)
		for j := range plain {
			plain[j] = byte(j % 251)
		}
		sum := sha256.Sum256(seal(key, plain))
		f.Cases = append(f.Cases, testCase{
			Name: c.name, KeyHex: hex.EncodeToString(key), PlaintextLength: c.n, PlaintextSeed: "i mod 251",
			CiphertextSha256Hex: hex.EncodeToString(sum[:]), DigestB64: base64.StdEncoding.EncodeToString(sum[:]),
		})
	}
	out, err := json.MarshalIndent(f, "", "  ")
	must(err)
	fmt.Println(string(out))
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
