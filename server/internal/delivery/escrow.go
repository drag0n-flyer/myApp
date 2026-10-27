package delivery

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/hpke"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/velixio/myApp/server/internal/core"
)

// escrowInfo is the HPKE info string of escrow copies (spec section 7.2).
var escrowInfo = []byte("escrow-v1")

// Escrow is HPKE base mode with DHKEM(X25519, HKDF-SHA256), HKDF-SHA256 and
// ChaCha20-Poly1305. The sealed form is the single-shot encoding of
// crypto/hpke: the encapsulated key followed by the ciphertext.

// SealEscrow seals plaintext to the escrow public key.
func SealEscrow(pub hpke.PublicKey, plaintext []byte) ([]byte, error) {
	return hpke.Seal(pub, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), escrowInfo, plaintext)
}

// OpenEscrow opens a message made by SealEscrow.
func OpenEscrow(priv hpke.PrivateKey, sealed []byte) ([]byte, error) {
	return hpke.Open(priv, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), escrowInfo, sealed)
}

// LoadEscrowKey reads an X25519 private key from a PKCS#8 PEM file. Any other
// key type is refused.
func LoadEscrowKey(path string) (hpke.PrivateKey, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("escrow key: %w", err)
	}
	blk, _ := pem.Decode(raw)
	if blk == nil || blk.Type != "PRIVATE KEY" {
		return nil, errors.New("escrow key: file is not a PKCS#8 PEM private key")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if err != nil {
		return nil, errors.New("escrow key: cannot parse PKCS#8 key")
	}
	k, ok := parsed.(*ecdh.PrivateKey)
	if !ok || k.Curve() != ecdh.X25519() {
		return nil, errors.New("escrow key: not an X25519 key")
	}
	return hpke.NewDHKEMPrivateKey(k)
}

// EscrowPublicKey returns the raw 32-byte X25519 public key for the key file.
func EscrowPublicKey(path string) ([]byte, error) {
	priv, err := LoadEscrowKey(path)
	if err != nil {
		return nil, err
	}
	return priv.PublicKey().Bytes(), nil
}

// Commands returns the admin CLI subcommands.
func (m *Module) Commands() map[string]core.Command {
	return map[string]core.Command{
		"keygen escrow": m.keygenEscrow,
		"escrow export": m.exportEscrow,
	}
}

// keygenEscrow prints a new X25519 private key as PKCS#8 PEM.
func (m *Module) keygenEscrow(_ context.Context, _ []string, out io.Writer) error {
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		return err
	}
	_, err = out.Write(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))
	return err
}

type exportLine struct {
	Seq             int64     `json:"seq"`
	SenderAccountID string    `json:"senderAccountID"`
	CreatedAt       time.Time `json:"createdAt"`
	Plaintext       string    `json:"plaintext"`
}

// exportEscrow decrypts the escrow copies of one conversation and writes one
// JSON line per copy, in seq order. Everything is decrypted first, and the
// audit row is written before any plaintext leaves, so no export goes
// unlogged and a failed export prints nothing. Plaintext is never logged.
func (m *Module) exportEscrow(ctx context.Context, args []string, out io.Writer) error {
	fs := flag.NewFlagSet("escrow export", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	conv := fs.String("conversation", "", "conversation ID")
	reason := fs.String("reason", "", "why the copies are being read (written to the access log)")
	if err := fs.Parse(args); err != nil {
		return fmt.Errorf("escrow export: %w", err)
	}
	id := strings.ToLower(*conv)
	if !uuidRE.MatchString(id) {
		return errors.New("escrow export: --conversation must be a conversation ID")
	}
	if strings.TrimSpace(*reason) == "" {
		return errors.New("escrow export: --reason is required")
	}
	if m.d.Config.EscrowKeyFile == "" {
		return errors.New("escrow export: APP_ESCROW_KEY_FILE is not set")
	}
	priv, err := LoadEscrowKey(m.d.Config.EscrowKeyFile)
	if err != nil {
		return err
	}

	var exists bool
	if err := m.d.DB.QueryRow(ctx, `select exists (select 1 from conversations where id = $1)`, id).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return errors.New("escrow export: no such conversation")
	}
	rows, err := m.d.DB.Query(ctx,
		`select seq, sender_account::text, created_at, data from escrow where conversation_id = $1 order by seq`, id)
	if err != nil {
		return err
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	for rows.Next() {
		var l exportLine
		var sealed []byte
		if err := rows.Scan(&l.Seq, &l.SenderAccountID, &l.CreatedAt, &sealed); err != nil {
			rows.Close()
			return err
		}
		plain, err := OpenEscrow(priv, sealed)
		if err != nil {
			rows.Close()
			return fmt.Errorf("escrow export: cannot decrypt the copy at seq %d (wrong key?)", l.Seq)
		}
		l.Plaintext = string(plain)
		if err := enc.Encode(l); err != nil {
			rows.Close()
			return err
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	if _, err := m.d.DB.Exec(ctx, `insert into escrow_access_log (conversation_id, reason, at) values ($1, $2, $3)`,
		id, *reason, m.d.Now()); err != nil {
		return err
	}
	_, err = out.Write(buf.Bytes())
	return err
}
