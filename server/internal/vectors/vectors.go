// Package vectors holds the types and the loader of the shared protocol test
// vectors in docs/protocol/test-vectors. The files are written only by
// TestGenerate (go test ./internal/vectors/ -update), never by hand.
package vectors

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// Dir is the vector directory, relative to this package.
const Dir = "../../../docs/protocol/test-vectors"

// TestOnly is the note every file carries.
const TestOnly = "TEST ONLY — published keys"

// Header is the pair of fields every vector file starts with.
type Header struct {
	Version int    `json:"version"`
	Note    string `json:"note"`
}

// PaddingCase is one push-padding case.
type PaddingCase struct {
	Name       string `json:"name"`
	PayloadHex string `json:"payloadHex"`
	PaddedHex  string `json:"paddedHex"`
}

// PaddingNegative is padded data that Unpad must reject.
type PaddingNegative struct {
	Name      string `json:"name"`
	PaddedHex string `json:"paddedHex"`
}

// PushPadding is push-padding.json.
type PushPadding struct {
	Header
	Cases    []PaddingCase     `json:"cases"`
	Negative []PaddingNegative `json:"negative"`
}

// SignatureCase is one request-signature case.
type SignatureCase struct {
	Method    string `json:"method"`
	Path      string `json:"path"`
	Timestamp string `json:"timestamp"`
	BodyB64   string `json:"bodyB64"`
	// PushRequest, when set, is the structure the body is the JSON encoding of.
	PushRequest   *PushRequestFields `json:"pushRequest,omitempty"`
	SigningString string             `json:"signingString"`
	SignatureB64  string             `json:"signatureB64"`
}

// PushRequestFields are the fields of a POST /v1/push body. HandleB64 and
// DataB64 are standard base64 with padding, as the Go encoding of []byte.
type PushRequestFields struct {
	HandleB64 string `json:"handleB64"`
	Kind      string `json:"kind"`
	DataB64   string `json:"dataB64"`
	Nonce     string `json:"nonce"`
}

// RequestSignature is request-signature.json.
type RequestSignature struct {
	Header
	SeedHex      string          `json:"seedHex"`
	PublicKeyB64 string          `json:"publicKeyB64"`
	Cases        []SignatureCase `json:"cases"`
}

// Suite names the HPKE suite.
type Suite struct {
	KEM  string `json:"kem"`
	KDF  string `json:"kdf"`
	AEAD string `json:"aead"`
}

// SealedCase is a sealed message that must open to PlaintextJSON.
type SealedCase struct {
	PlaintextJSON string `json:"plaintextJSON"`
	SealedB64     string `json:"sealedB64"`
}

// NegativeCase is a sealed message that must fail to open under Info.
type NegativeCase struct {
	Name      string `json:"name"`
	SealedB64 string `json:"sealedB64"`
	Info      string `json:"info"`
}

// Sealed is sealed-handle.json and escrow.json.
type Sealed struct {
	Header
	Suite                  Suite          `json:"suite"`
	Info                   string         `json:"info"`
	RecipientPrivateKeyHex string         `json:"recipientPrivateKeyHex"`
	RecipientPublicKeyB64  string         `json:"recipientPublicKeyB64"`
	Cases                  []SealedCase   `json:"cases"`
	Negative               []NegativeCase `json:"negative"`
}

// TURNCase is one turn-credential case.
type TURNCase struct {
	Secret     string `json:"secret"`
	Username   string `json:"username"`
	Credential string `json:"credential"`
}

// TURNCredential is turn-credential.json.
type TURNCredential struct {
	Header
	Cases []TURNCase `json:"cases"`
}

// Load reads name from Dir into v.
func Load(name string, v any) error {
	raw, err := os.ReadFile(filepath.Join(Dir, name))
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, v)
}

// Write stores v as indented JSON with a trailing newline.
func Write(name string, v any) error {
	if err := os.MkdirAll(Dir, 0o755); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(Dir, name), append(raw, '\n'), 0o644)
}
