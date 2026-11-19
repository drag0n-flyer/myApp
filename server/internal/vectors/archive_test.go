package vectors

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

type archiveVector struct {
	Version        int    `json:"version"`
	Magic          string `json:"magic"`
	ChunkSize      int    `json:"chunkSize"`
	KeyHex         string `json:"keyHex"`
	NoncePrefixHex string `json:"noncePrefixHex"`
	Cases          []struct {
		Name                string `json:"name"`
		PlaintextLength     int    `json:"plaintextLength"`
		CiphertextLength    int    `json:"ciphertextLength"`
		CiphertextSha256Hex string `json:"ciphertextSha256Hex"`
		CiphertextHex       string `json:"ciphertextHex"`
	} `json:"cases"`
	Negative []struct {
		Name   string `json:"name"`
		Case   string `json:"case"`
		Op     string `json:"op"`
		Hex    string `json:"hex"`
		KeyHex string `json:"keyHex"`
		A      int    `json:"a"`
		B      int    `json:"b"`
	} `json:"negative"`
}

// archiveOpen is written from docs/protocol/archive.md and shares nothing with the generator.
func archiveOpen(key, data []byte) ([]byte, error) {
	bad := errors.New("archive: invalid")
	if len(data) < 16 || string(data[:8]) != "APPARCH1" || data[8] != 1 {
		return nil, bad
	}
	header, prefix := data[:16], data[9:16]
	blk, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	g, _ := cipher.NewGCM(blk)
	var out []byte
	pos := 16
	if pos == len(data) {
		return nil, bad // no final chunk
	}
	for idx := uint32(0); pos < len(data); idx++ {
		if len(data)-pos < 4 {
			return nil, bad
		}
		n := int(binary.BigEndian.Uint32(data[pos:]))
		pos += 4
		if n < 16 || n > (1<<20)+16 || len(data)-pos < n {
			return nil, bad
		}
		rec := data[pos : pos+n]
		pos += n
		flag := byte(0)
		if pos == len(data) {
			flag = 1
		}
		tail := append(binary.BigEndian.AppendUint32(nil, idx), flag)
		pt, err := g.Open(nil, append(append([]byte{}, prefix...), tail...), rec, append(append([]byte{}, header...), tail...))
		if err != nil {
			return nil, err
		}
		out = append(out, pt...)
	}
	return out, nil
}

func archiveSeal(key, prefix, plain []byte) []byte {
	blk, _ := aes.NewCipher(key)
	g, _ := cipher.NewGCM(blk)
	header := append(append([]byte("APPARCH1"), 1), prefix...)
	out := append([]byte{}, header...)
	for idx, off := uint32(0), 0; ; idx++ {
		end := min(off+(1<<20), len(plain))
		flag := byte(0)
		if end == len(plain) {
			flag = 1
		}
		tail := append(binary.BigEndian.AppendUint32(nil, idx), flag)
		ct := g.Seal(nil, append(append([]byte{}, prefix...), tail...), plain[off:end], append(append([]byte{}, header...), tail...))
		out = binary.BigEndian.AppendUint32(out, uint32(len(ct)))
		out = append(out, ct...)
		if flag == 1 {
			return out
		}
		off = end
	}
}

func TestArchiveVector(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join(Dir, "archive-v1.json"))
	if err != nil {
		t.Fatal(err)
	}
	var v archiveVector
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	if v.Version != 1 || v.Magic != "APPARCH1" || v.ChunkSize != 1<<20 || len(v.Cases) != 4 {
		t.Fatalf("header: %+v", v)
	}
	key := unhex(t, v.KeyHex)
	prefix := unhex(t, v.NoncePrefixHex)
	cts := map[string][]byte{}
	for _, c := range v.Cases {
		plain := make([]byte, c.PlaintextLength)
		for i := range plain {
			plain[i] = byte(i % 251)
		}
		ct := archiveSeal(key, prefix, plain)
		sum := sha256.Sum256(ct)
		if hex.EncodeToString(sum[:]) != c.CiphertextSha256Hex || len(ct) != c.CiphertextLength {
			t.Errorf("%s: ciphertext differs", c.Name)
		}
		if c.CiphertextHex != "" && hex.EncodeToString(ct) != c.CiphertextHex {
			t.Errorf("%s: ciphertextHex differs", c.Name)
		}
		got, err := archiveOpen(key, ct)
		if err != nil || !bytes.Equal(got, plain) {
			t.Errorf("%s: open: %v", c.Name, err)
		}
		cts[c.Name] = ct
	}
	if len(v.Negative) < 10 {
		t.Fatalf("%d negative cases", len(v.Negative))
	}
	for _, n := range v.Negative {
		data := append([]byte{}, cts[n.Case]...)
		k := key
		switch n.Op {
		case "none":
		case "dropLastRecord":
			pos, last := 16, 16
			for pos < len(data) {
				last = pos
				pos += 4 + int(binary.BigEndian.Uint32(data[pos:]))
			}
			data = data[:last]
		case "swapRecords":
			var recs [][]byte
			for pos := 16; pos < len(data); {
				e := pos + 4 + int(binary.BigEndian.Uint32(data[pos:]))
				recs = append(recs, data[pos:e])
				pos = e
			}
			recs[n.A], recs[n.B] = recs[n.B], recs[n.A]
			data = append([]byte{}, data[:16]...)
			for _, r := range recs {
				data = append(data, r...)
			}
		case "flipBit":
			data[n.A] ^= 1 << n.B
		case "appendHex":
			data = append(data, unhex(t, n.Hex)...)
		case "emptyInput":
			data = nil
		default:
			t.Fatalf("%s: unknown op %s", n.Name, n.Op)
		}
		if n.KeyHex != "" {
			k = unhex(t, n.KeyHex)
		}
		if _, err := archiveOpen(k, data); err == nil {
			t.Errorf("%s: opened", n.Name)
		}
	}
}
