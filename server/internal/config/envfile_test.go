package config

import (
	"errors"
	"io/fs"
	"reflect"
	"strings"
	"testing"
)

func TestParseEnvFile(t *testing.T) {
	data := []byte(`# comment
APP_HOST=chat.test

export APP_LISTEN=0.0.0.0:8080
APP_SMTP_URL=smtp://user:p%40ss@mail.test:587   # trailing comment
APP_MAIL_FROM="Chat <no-reply@chat.test>"
APP_TURN_SECRET='a#b $c'
APP_RELAYS=turn1.test#eu
APP_EMPTY=
APP_ESCAPED="line\"quote\\"
`)
	got, err := ParseEnvFile(data)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]string{
		"APP_HOST":        "chat.test",
		"APP_LISTEN":      "0.0.0.0:8080",
		"APP_SMTP_URL":    "smtp://user:p%40ss@mail.test:587",
		"APP_MAIL_FROM":   "Chat <no-reply@chat.test>",
		"APP_TURN_SECRET": "a#b $c",
		"APP_RELAYS":      "turn1.test#eu",
		"APP_EMPTY":       "",
		"APP_ESCAPED":     `line"quote\`,
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got  %q\nwant %q", got, want)
	}
}

func TestParseEnvFileErrors(t *testing.T) {
	for _, bad := range []string{
		"NOEQUALS",
		"1BAD=x",
		"BAD KEY=x",
		`OPEN="unterminated`,
		`OPEN='unterminated`,
		`TRAIL="x" junk`,
	} {
		if _, err := ParseEnvFile([]byte("OK=1\n" + bad + "\n")); err == nil || !strings.Contains(err.Error(), "line 2") {
			t.Errorf("%q: err = %v, want a line 2 error", bad, err)
		}
	}
}

func lookupMap(m map[string]string) func(string) (string, bool) {
	return func(k string) (string, bool) { v, ok := m[k]; return v, ok }
}

func TestEnvWithFileRealEnvironmentWins(t *testing.T) {
	read := fileReader("APP_HOST=file.test\nAPP_LISTEN=0.0.0.0:8080\n")
	getenv, err := EnvWithFile(lookupMap(map[string]string{"APP_HOST": "env.test", "APP_LISTEN": ""}), read, ".env", false)
	if err != nil {
		t.Fatal(err)
	}
	if got := getenv("APP_HOST"); got != "env.test" {
		t.Errorf("APP_HOST = %q, want the real environment's value", got)
	}
	// Set but empty in the real environment still wins over the file.
	if got := getenv("APP_LISTEN"); got != "" {
		t.Errorf("APP_LISTEN = %q, want empty", got)
	}
}

func TestEnvWithFileFillsFromFile(t *testing.T) {
	getenv, err := EnvWithFile(lookupMap(nil), fileReader("APP_HOST=file.test\n"), ".env", false)
	if err != nil {
		t.Fatal(err)
	}
	if got := getenv("APP_HOST"); got != "file.test" {
		t.Errorf("APP_HOST = %q", got)
	}
	if got := getenv("APP_OTHER"); got != "" {
		t.Errorf("APP_OTHER = %q", got)
	}
}

func TestEnvWithFileMissing(t *testing.T) {
	missing := func(string) ([]byte, error) { return nil, fs.ErrNotExist }
	// The default .env is optional.
	getenv, err := EnvWithFile(lookupMap(map[string]string{"APP_HOST": "env.test"}), missing, ".env", false)
	if err != nil {
		t.Fatalf("optional file: %v", err)
	}
	if getenv("APP_HOST") != "env.test" {
		t.Error("real environment lost")
	}
	// A file named in APP_ENV_FILE must exist.
	if _, err := EnvWithFile(lookupMap(nil), missing, "/etc/app.env", true); !errors.Is(err, fs.ErrNotExist) {
		t.Errorf("required file: err = %v", err)
	}
}

func TestEnvWithFileBadSyntax(t *testing.T) {
	if _, err := EnvWithFile(lookupMap(nil), fileReader("oops\n"), ".env", false); err == nil || !strings.Contains(err.Error(), ".env") {
		t.Errorf("err = %v, want one naming the file", err)
	}
}
