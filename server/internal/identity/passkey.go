package identity

import (
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/go-webauthn/webauthn/protocol"
	"github.com/go-webauthn/webauthn/webauthn"

	"github.com/drag0n-flyer/myApp/server/internal/config"
)

// This file is the only importer of go-webauthn. Everything it exposes is
// plain bytes and values, so the rest of the module never sees the library.

const challengeTTL = 5 * time.Minute

// newWebAuthn configures the relying party: the server's host, resident keys
// and user verification required, and no attestation.
func newWebAuthn(cfg config.Config) (*webauthn.WebAuthn, error) {
	required := true
	var opaque []string // android:apk-key-hash: origins, matched exactly
	for _, a := range cfg.AndroidApps {
		opaque = append(opaque, a.Origin())
	}
	timeout := webauthn.TimeoutConfig{Enforce: true, Timeout: challengeTTL, TimeoutUVD: challengeTTL}
	return webauthn.New(&webauthn.Config{
		RPID:                  cfg.Host,
		RPDisplayName:         "[App]",
		RPOrigins:             []string{"https://" + cfg.Host},
		RPOpaqueOrigins:       opaque,
		AttestationPreference: protocol.PreferNoAttestation,
		AuthenticatorSelection: protocol.AuthenticatorSelection{
			RequireResidentKey: &required,
			ResidentKey:        protocol.ResidentKeyRequirementRequired,
			UserVerification:   protocol.VerificationRequired,
		},
		Timeouts: webauthn.TimeoutsConfig{Login: timeout, Registration: timeout},
	})
}

// Validate checks that the WebAuthn relying party can be built from cfg, so a
// bad host stops startup instead of failing every sign-up.
func Validate(cfg config.Config) error {
	if _, err := newWebAuthn(cfg); err != nil {
		return fmt.Errorf("identity: passkey configuration: %w", err)
	}
	return nil
}

// relyingParty hides the library's type from the rest of the module.
type relyingParty struct{ w *webauthn.WebAuthn }

func newRelyingParty(cfg config.Config) (*relyingParty, error) {
	w, err := newWebAuthn(cfg)
	if err != nil {
		return nil, err
	}
	return &relyingParty{w}, nil
}

// errPasskey is returned for any credential the library rejects: malformed
// JSON, wrong origin or challenge, bad signature, unknown credential.
var errPasskey = errors.New("passkey rejected")

// waUser is an account as the library sees it. The user handle is the
// account UUID's 16 bytes.
type waUser struct {
	id    []byte
	name  string
	creds []webauthn.Credential
}

func (u *waUser) WebAuthnID() []byte                         { return u.id }
func (u *waUser) WebAuthnName() string                       { return u.name }
func (u *waUser) WebAuthnDisplayName() string                { return u.name }
func (u *waUser) WebAuthnCredentials() []webauthn.Credential { return u.creds }

// passkeyRow is a stored credential.
type passkeyRow struct {
	CredentialID []byte
	PublicKey    []byte
	SignCount    uint32
	AAGUID       []byte
	Flags        byte
}

func (p passkeyRow) credential() webauthn.Credential {
	return webauthn.Credential{
		ID:                p.CredentialID,
		PublicKey:         p.PublicKey,
		AttestationFormat: "none",
		Flags:             webauthn.NewCredentialFlags(protocol.AuthenticatorFlags(p.Flags)),
		Authenticator:     webauthn.Authenticator{AAGUID: p.AAGUID, SignCount: p.SignCount},
	}
}

func rowOf(c *webauthn.Credential) passkeyRow {
	return passkeyRow{
		CredentialID: c.ID,
		PublicKey:    c.PublicKey,
		SignCount:    c.Authenticator.SignCount,
		AAGUID:       c.Authenticator.AAGUID,
		Flags:        byte(c.Flags.ProtocolValue()),
	}
}

// beginRegistration returns the creation options to send to the client and the
// session to keep until it answers. Credentials the user already has are
// excluded.
func beginRegistration(wa *relyingParty, u *waUser) (options, session []byte, err error) {
	var exclude []protocol.CredentialDescriptor
	for _, c := range u.creds {
		exclude = append(exclude, protocol.CredentialDescriptor{Type: protocol.PublicKeyCredentialType, CredentialID: c.ID})
	}
	creation, sd, err := wa.w.BeginRegistration(u, webauthn.WithExclusions(exclude))
	if err != nil {
		return nil, nil, err
	}
	if options, err = json.Marshal(creation.Response); err != nil {
		return nil, nil, err
	}
	session, err = json.Marshal(sd)
	return options, session, err
}

// finishRegistration checks the client's registration response against the
// session and returns the new credential.
func finishRegistration(wa *relyingParty, u *waUser, session, response []byte) (passkeyRow, error) {
	var sd webauthn.SessionData
	if err := json.Unmarshal(session, &sd); err != nil {
		return passkeyRow{}, err
	}
	parsed, err := protocol.ParseCredentialCreationResponseBytes(response)
	if err != nil {
		return passkeyRow{}, errPasskey
	}
	cred, err := wa.w.CreateCredential(u, sd, parsed)
	if err != nil {
		return passkeyRow{}, errPasskey
	}
	return rowOf(cred), nil
}

// beginLogin returns assertion options for a discoverable credential (no
// account is named), with user verification required, and the session.
func beginLogin(wa *relyingParty) (options, session []byte, err error) {
	assertion, sd, err := wa.w.BeginDiscoverableLogin(webauthn.WithUserVerification(protocol.VerificationRequired))
	if err != nil {
		return nil, nil, err
	}
	if options, err = json.Marshal(assertion.Response); err != nil {
		return nil, nil, err
	}
	session, err = json.Marshal(sd)
	return options, session, err
}

// finishLogin checks the client's assertion. lookup maps the user handle in
// the response to the account and its credentials. It returns the account's
// handle and the credential with its updated counter and flags. A cloned
// authenticator (counter went backwards) is rejected.
func finishLogin(wa *relyingParty, lookup func(userHandle []byte) (*waUser, error), session, response []byte) (userHandle []byte, row passkeyRow, err error) {
	var sd webauthn.SessionData
	if err := json.Unmarshal(session, &sd); err != nil {
		return nil, passkeyRow{}, err
	}
	parsed, err := protocol.ParseCredentialRequestResponseBytes(response)
	if err != nil {
		return nil, passkeyRow{}, errPasskey
	}
	user, cred, err := wa.w.ValidatePasskeyLogin(func(_, handle []byte) (webauthn.User, error) {
		u, err := lookup(handle)
		if err != nil {
			return nil, err
		}
		return u, nil
	}, sd, parsed)
	if err != nil || cred.Authenticator.CloneWarning {
		return nil, passkeyRow{}, errPasskey
	}
	return user.WebAuthnID(), rowOf(cred), nil
}
