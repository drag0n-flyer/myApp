package main

import (
	"strings"
	"testing"
)

func TestPushgwMainConfig(t *testing.T) {
	env := map[string]string{
		"PUSHGW_LISTEN":        ":0",
		"PUSHGW_DATABASE_URL":  "postgres:///x",
		"PUSHGW_HPKE_KEY_FILE": "/nonexistent/hpke.pem",
		"PUSHGW_APNS_KEY_ID":   "K",
		"PUSHGW_APNS_TEAM_ID":  "T",
		"PUSHGW_BUNDLE_IDS":    "com.example.app",
	}
	err := run(func(k string) string { return env[k] })
	if err == nil || !strings.Contains(err.Error(), "PUSHGW_APNS_KEY_FILE") {
		t.Fatalf("got %v", err)
	}
	// nothing set at all: every missing variable is named
	err = run(func(string) string { return "" })
	if err == nil || !strings.Contains(err.Error(), "PUSHGW_APNS_KEY_FILE") || !strings.Contains(err.Error(), "PUSHGW_DATABASE_URL") {
		t.Fatalf("got %v", err)
	}
}
