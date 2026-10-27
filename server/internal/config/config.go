// Package config loads the server's environment configuration and policy file
// (spec section 5).
package config

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
)

// Relay is one TURN relay host with an optional region.
type Relay struct{ Host, Region string }

// Config holds the environment-derived settings.
type Config struct {
	Host, Listen, AdminListen, DatabaseURL, DataDir, SMTPURL, MailFrom, TURNSecret string
	Relays                                                                         []Relay
	PushGatewayURL, PushKeyFile, PushServerID, EscrowKeyFile                       string
	AppleAppIDs                                                                    []string
	TrustProxy                                                                     bool
	LabMode                                                                        bool // APP_LAB_MODE=1: lab-only session command; never on a real server
}

// Policy is the operator policy file, with defaults from DefaultPolicy.
type Policy struct {
	RequiresInvite  bool   `json:"requiresInvite"`
	DefaultMode     string `json:"defaultMode"`
	MaxMode         string `json:"maxMode"`
	DefaultIPHiding string `json:"defaultIPHiding"`
	Encryption      struct {
		NewConversations string `json:"newConversations"`
	} `json:"encryption"`
	AbuseContact string `json:"abuseContact"`
	Retention    struct {
		InboxDays      int `json:"inboxDays"`
		AttachmentDays int `json:"attachmentDays"`
		EscrowDays     int `json:"escrowDays"`
	} `json:"retention"`
	Limits struct {
		AttachmentBytes       int64 `json:"attachmentBytes"`
		AttachmentBytesPerDay int64 `json:"attachmentBytesPerDay"`
	} `json:"limits"`
}

// DefaultPolicy returns the spec section 5 defaults.
func DefaultPolicy() Policy {
	var p Policy
	p.RequiresInvite = true
	p.DefaultMode = "auto"
	p.MaxMode = "high"
	p.DefaultIPHiding = "nonContacts"
	p.Encryption.NewConversations = "e2ee"
	p.Retention.InboxDays = 30
	p.Retention.AttachmentDays = 30
	p.Retention.EscrowDays = 365
	p.Limits.AttachmentBytes = 104857600
	p.Limits.AttachmentBytesPerDay = 1073741824
	return p
}

// ModeRank orders the concrete call quality modes. "auto" and unknown values
// have no rank.
func ModeRank(mode string) (int, bool) {
	switch mode {
	case "ultraLow":
		return 0, true
	case "dataSaver":
		return 1, true
	case "balanced":
		return 2, true
	case "high":
		return 3, true
	}
	return 0, false
}

// Load reads the configuration from getenv and, when APP_POLICY_FILE is set,
// the policy file through readFile. It fails on a missing required variable or
// an invalid policy.
func Load(getenv func(string) string, readFile func(string) ([]byte, error)) (Config, Policy, error) {
	var c Config
	c.Host = getenv("APP_HOST")
	if c.Host == "" {
		return Config{}, Policy{}, fmt.Errorf("config: APP_HOST is required")
	}
	c.DatabaseURL = getenv("APP_DATABASE_URL")
	if c.DatabaseURL == "" {
		return Config{}, Policy{}, fmt.Errorf("config: APP_DATABASE_URL is required")
	}
	c.Listen = orDefault(getenv("APP_LISTEN"), ":8080")
	c.AdminListen = orDefault(getenv("APP_ADMIN_LISTEN"), "127.0.0.1:9090")
	c.DataDir = orDefault(getenv("APP_DATA_DIR"), "/var/lib/appserver")
	c.SMTPURL = getenv("APP_SMTP_URL")
	c.MailFrom = orDefault(getenv("APP_MAIL_FROM"), "no-reply@"+c.Host)
	c.TURNSecret = getenv("APP_TURN_SECRET")
	c.Relays = parseRelays(getenv("APP_RELAYS"))
	c.PushGatewayURL = getenv("APP_PUSH_GATEWAY_URL")
	c.PushKeyFile = getenv("APP_PUSH_KEY_FILE")
	c.PushServerID = getenv("APP_PUSH_SERVER_ID")
	c.EscrowKeyFile = getenv("APP_ESCROW_KEY_FILE")
	c.AppleAppIDs = splitList(getenv("APP_APPLE_APP_IDS"))
	if v := getenv("APP_TRUST_PROXY"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			return Config{}, Policy{}, fmt.Errorf("config: APP_TRUST_PROXY must be true or false")
		}
		c.TrustProxy = b
	}

	c.LabMode = getenv("APP_LAB_MODE") == "1"

	p := DefaultPolicy()
	if path := getenv("APP_POLICY_FILE"); path != "" {
		data, err := readFile(path)
		if err != nil {
			return Config{}, Policy{}, fmt.Errorf("config: APP_POLICY_FILE: %w", err)
		}
		if err := json.Unmarshal(data, &p); err != nil {
			return Config{}, Policy{}, fmt.Errorf("config: APP_POLICY_FILE: %w", err)
		}
	}
	if err := validateConfig(c); err != nil {
		return Config{}, Policy{}, err
	}
	if err := validatePolicy(p, c); err != nil {
		return Config{}, Policy{}, err
	}
	return c, p, nil
}

func validateConfig(c Config) error {
	for _, r := range c.Relays {
		if r.Host == "" {
			return fmt.Errorf("config: APP_RELAYS has an entry with no host")
		}
	}
	if c.TURNSecret != "" && len(c.Relays) == 0 {
		return fmt.Errorf("config: APP_TURN_SECRET requires at least one host in APP_RELAYS")
	}
	if c.PushGatewayURL != "" && c.PushServerID == "" {
		return fmt.Errorf("config: APP_PUSH_GATEWAY_URL requires APP_PUSH_SERVER_ID")
	}
	if c.PushGatewayURL != "" && c.PushKeyFile == "" {
		return fmt.Errorf("config: APP_PUSH_GATEWAY_URL requires APP_PUSH_KEY_FILE")
	}
	return nil
}

func validatePolicy(p Policy, c Config) error {
	if p.DefaultMode != "auto" {
		if _, ok := ModeRank(p.DefaultMode); !ok {
			return fmt.Errorf("config: policy defaultMode %q is not a known mode", p.DefaultMode)
		}
	}
	maxRank, ok := ModeRank(p.MaxMode)
	if !ok {
		return fmt.Errorf("config: policy maxMode %q is not a known mode", p.MaxMode)
	}
	if defRank, ok := ModeRank(p.DefaultMode); ok && defRank > maxRank {
		return fmt.Errorf("config: policy defaultMode %q is above maxMode %q", p.DefaultMode, p.MaxMode)
	}
	if p.Retention.InboxDays <= 0 || p.Retention.AttachmentDays <= 0 || p.Retention.EscrowDays <= 0 {
		return fmt.Errorf("config: policy retention days must be positive")
	}
	if p.Limits.AttachmentBytes <= 0 || p.Limits.AttachmentBytesPerDay <= 0 {
		return fmt.Errorf("config: policy limits must be positive")
	}
	switch p.DefaultIPHiding {
	case "never", "nonContacts", "always":
	default:
		return fmt.Errorf("config: policy defaultIPHiding %q is not a known value", p.DefaultIPHiding)
	}
	switch p.Encryption.NewConversations {
	case "e2ee":
	case "server_readable":
		if c.EscrowKeyFile == "" {
			return fmt.Errorf("config: policy newConversations server_readable requires APP_ESCROW_KEY_FILE")
		}
	default:
		return fmt.Errorf("config: policy encryption.newConversations %q is not a known value", p.Encryption.NewConversations)
	}
	return nil
}

func orDefault(v, def string) string {
	if v == "" {
		return def
	}
	return v
}

// splitList splits a comma list, trimming spaces and dropping empty items.
func splitList(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

func parseRelays(s string) []Relay {
	var out []Relay
	for _, item := range splitList(s) {
		host, region, _ := strings.Cut(item, "@")
		out = append(out, Relay{Host: host, Region: region})
	}
	return out
}
