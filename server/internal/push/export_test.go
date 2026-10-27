package push

import (
	"crypto/ecdh"
	"crypto/tls"
	"crypto/x509"
)

// UseTestTransport makes the UnifiedPush client trust roots and allow any
// address, but keeps every other rule (no redirects, timeout).
func (m *Module) UseTestTransport(roots *x509.CertPool) {
	m.up = newUPClient(true, &tls.Config{RootCAs: roots})
}

func CheckAddr(addr string) error { return checkDialAddr("tcp", addr) }

func EncryptForTest(uaPub, auth, data []byte, asPriv *ecdh.PrivateKey, salt []byte) ([]byte, error) {
	return encryptAES128GCM(uaPub, auth, data, asPriv, salt)
}

func VapidAudForTest(endpoint string) (string, error) {
	u, err := validateEndpoint(endpoint)
	if err != nil {
		return "", err
	}
	return originOf(u), nil
}

// WaitUP waits for in-flight UnifiedPush sends.
func (m *Module) WaitUP() { m.upWG.Wait() }
