// Command safety writes the safety-number test vector (Design 20). Standard library only.
//
//	cd tools/vectors && go run ./safety > ../../apps/apple/Packages/Messaging/Tests/MessagingTests/Resources/safety-v1.json
//
// side(acct) = SHA-256("safety-v1" || 0x00 || acct || 0x00 || for each device of acct sorted by identity
// bytes: u16be(len id) || id || u16be(len key) || key). The sides are ordered by account ID bytes; each
// gives 6 groups (big-endian integer of bytes 5i..<5i+5, mod 100000, 5 digits): 12 groups in all.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strings"
)

type member struct {
	Identity     string `json:"identity"`
	SignatureKey string `json:"signatureKeyHex"`
}

type testCase struct {
	Name     string   `json:"name"`
	AccountA string   `json:"accountA"`
	AccountB string   `json:"accountB"`
	Members  []member `json:"members"`
	Expected []string `json:"expected"`
}

func key(seed byte) string {
	k := make([]byte, 32)
	for i := range k {
		k[i] = seed + byte(i)
	}
	return hex.EncodeToString(k)
}

func side(acct string, members []member) []byte {
	var mine []member
	for _, m := range members {
		if a, _, _ := strings.Cut(m.Identity, "/"); a == acct {
			mine = append(mine, m)
		}
	}
	sort.Slice(mine, func(i, j int) bool { return mine[i].Identity < mine[j].Identity })
	var b bytes.Buffer
	b.WriteString("safety-v1")
	b.WriteByte(0)
	b.WriteString(acct)
	b.WriteByte(0)
	for _, m := range mine {
		k, err := hex.DecodeString(m.SignatureKey)
		if err != nil {
			panic(err)
		}
		b.Write(binary.BigEndian.AppendUint16(nil, uint16(len(m.Identity))))
		b.WriteString(m.Identity)
		b.Write(binary.BigEndian.AppendUint16(nil, uint16(len(k))))
		b.Write(k)
	}
	sum := sha256.Sum256(b.Bytes())
	return sum[:]
}

func groups(h []byte) []string {
	out := make([]string, 6)
	for i := range out {
		var n uint64
		for _, c := range h[5*i : 5*i+5] {
			n = n<<8 | uint64(c)
		}
		out[i] = fmt.Sprintf("%05d", n%100000)
	}
	return out
}

func compute(a, b string, members []member) []string {
	if a > b {
		a, b = b, a
	}
	return append(groups(side(a, members)), groups(side(b, members))...)
}

func main() {
	two := []member{
		{"acc-ana/dev-1", key(1)},
		{"acc-ana/dev-2", key(40)},
		{"acc-bob/dev-3", key(80)},
	}
	three := append(append([]member{}, two...), member{"acc-ana/dev-4", key(120)})
	withThird := append(append([]member{}, two...), member{"acc-carol/dev-5", key(160)})
	// Same members as `two`, listed in another order: must give the same number.
	reordered := []member{two[2], two[1], two[0]}
	// Identity bytes order differs from locale order ("acc-ana/dev-10" sorts before "acc-ana/dev-2").
	tenth := []member{{"acc-ana/dev-2", key(40)}, {"acc-ana/dev-10", key(200)}, {"acc-bob/dev-3", key(80)}}

	cases := []testCase{
		{Name: "two accounts, three devices", AccountA: "acc-ana", AccountB: "acc-bob", Members: two},
		{Name: "same, accounts swapped", AccountA: "acc-bob", AccountB: "acc-ana", Members: two},
		{Name: "same, members reordered", AccountA: "acc-ana", AccountB: "acc-bob", Members: reordered},
		{Name: "a device added", AccountA: "acc-ana", AccountB: "acc-bob", Members: three},
		{Name: "a third account is ignored", AccountA: "acc-ana", AccountB: "acc-bob", Members: withThird},
		{Name: "identity byte order", AccountA: "acc-ana", AccountB: "acc-bob", Members: tenth},
	}
	for i := range cases {
		cases[i].Expected = compute(cases[i].AccountA, cases[i].AccountB, cases[i].Members)
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(map[string]any{"version": "safety-v1", "cases": cases}); err != nil {
		panic(err)
	}
}
