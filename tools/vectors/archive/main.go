// Command archive writes the archive-v1 test vector to stdout. Standard library only.
//
//	cd tools/vectors && go run ./archive > ../../docs/protocol/test-vectors/archive-v1.json
//
// Format: docs/protocol/archive.md. Streaming AES-256-GCM over 1 MiB plaintext chunks. Header = "APPARCH1" ‖ 0x01 ‖
// 7-byte nonce prefix. Chunk i: nonce = prefix ‖ u32be(i) ‖ final-flag; AAD = header ‖ u32be(i) ‖ final-flag;
// record = u32be(len) ‖ GCM(ct ‖ tag). Plaintext byte i is i mod 251. AES-GCM is deterministic, so a rerun
// gives the same file. Negative cases are mutations of a positive case's ciphertext; each must fail to open.
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
)

const chunkSize = 1 << 20

type posCase struct {
	Name                string `json:"name"`
	PlaintextLength     int    `json:"plaintextLength"`
	CiphertextLength    int    `json:"ciphertextLength"`
	CiphertextSha256Hex string `json:"ciphertextSha256Hex"`
	CiphertextHex       string `json:"ciphertextHex,omitempty"`
}

type negCase struct {
	Name   string `json:"name"`
	Case   string `json:"case"`
	Op     string `json:"op"`
	A      int    `json:"a,omitempty"`
	B      int    `json:"b,omitempty"`
	Hex    string `json:"hex,omitempty"`
	KeyHex string `json:"keyHex,omitempty"`
}

type file struct {
	Version        int       `json:"version"`
	Magic          string    `json:"magic"`
	ChunkSize      int       `json:"chunkSize"`
	KeyHex         string    `json:"keyHex"`
	NoncePrefixHex string    `json:"noncePrefixHex"`
	PlaintextSeed  string    `json:"plaintextSeed"`
	Cases          []posCase `json:"cases"`
	Negative       []negCase `json:"negative"`
}

func seal(key, prefix, plain []byte) []byte {
	blk, err := aes.NewCipher(key)
	must(err)
	g, err := cipher.NewGCM(blk)
	must(err)
	header := append(append([]byte("APPARCH1"), 1), prefix...)
	out := append([]byte{}, header...)
	for idx, off := uint32(0), 0; ; idx++ {
		end := min(off+chunkSize, len(plain))
		last := end == len(plain)
		flag := byte(0)
		if last {
			flag = 1
		}
		tail := binary.BigEndian.AppendUint32(nil, idx)
		tail = append(tail, flag)
		nonce := append(append([]byte{}, prefix...), tail...)
		aad := append(append([]byte{}, header...), tail...)
		ct := g.Seal(nil, nonce, plain[off:end], aad)
		out = binary.BigEndian.AppendUint32(out, uint32(len(ct)))
		out = append(out, ct...)
		if last {
			return out
		}
		off = end
	}
}

func main() {
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(0x20 + i)
	}
	prefix := []byte{0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6}
	f := file{Version: 1, Magic: "APPARCH1", ChunkSize: chunkSize, KeyHex: hex.EncodeToString(key),
		NoncePrefixHex: hex.EncodeToString(prefix), PlaintextSeed: "i mod 251"}
	for _, c := range []struct {
		n    int
		name string
	}{{0, "empty"}, {1, "one byte"}, {chunkSize, "exactly one chunk"}, {chunkSize + 1, "one chunk and one byte"}} {
		plain := make([]byte, c.n)
		for j := range plain {
			plain[j] = byte(j % 251)
		}
		ct := seal(key, prefix, plain)
		sum := sha256.Sum256(ct)
		pc := posCase{Name: c.name, PlaintextLength: c.n, CiphertextLength: len(ct), CiphertextSha256Hex: hex.EncodeToString(sum[:])}
		if len(ct) <= 128 {
			pc.CiphertextHex = hex.EncodeToString(ct)
		}
		f.Cases = append(f.Cases, pc)
	}
	wrong := make([]byte, 32)
	copy(wrong, key)
	wrong[31] ^= 1
	two, one, empty := "one chunk and one byte", "one byte", "empty"
	f.Negative = []negCase{
		{Name: "missing final chunk", Case: two, Op: "dropLastRecord"},
		{Name: "reordered chunks", Case: two, Op: "swapRecords", A: 0, B: 1},
		{Name: "flipped bit in first chunk", Case: two, Op: "flipBit", A: 100, B: 3},
		{Name: "flipped bit in final chunk tag", Case: one, Op: "flipBit", A: 16 + 4 + 1, B: 0},
		{Name: "flipped bit in header", Case: one, Op: "flipBit", A: 9, B: 0},
		{Name: "extra byte after final chunk", Case: one, Op: "appendHex", Hex: "00"},
		{Name: "extra record after final chunk", Case: one, Op: "appendHex", Hex: "00000010" + "00000000000000000000000000000000"},
		{Name: "no final chunk (header only)", Case: empty, Op: "dropLastRecord"},
		{Name: "no bytes at all", Case: empty, Op: "emptyInput"},
		{Name: "wrong key", Case: two, Op: "none", KeyHex: hex.EncodeToString(wrong)},
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
