package config

import (
	"errors"
	"fmt"
	"io/fs"
	"regexp"
	"strings"
)

var envKeyRE = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

// ParseEnvFile parses a .env file: KEY=VALUE lines, blank lines and # comments,
// an optional "export " prefix. Values may be unquoted (a " #" starts a
// comment, surrounding space is trimmed), 'single quoted' (taken as is), or
// "double quoted" (with \" and \\ escapes). Nothing is expanded.
func ParseEnvFile(data []byte) (map[string]string, error) {
	out := map[string]string{}
	for i, line := range strings.Split(string(data), "\n") {
		n := i + 1
		line = strings.TrimSpace(strings.TrimSuffix(line, "\r"))
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		line = strings.TrimPrefix(line, "export ")
		key, raw, ok := strings.Cut(line, "=")
		key = strings.TrimSpace(key)
		if !ok || !envKeyRE.MatchString(key) {
			return nil, fmt.Errorf("line %d: want KEY=VALUE", n)
		}
		val, err := parseEnvValue(strings.TrimSpace(raw))
		if err != nil {
			return nil, fmt.Errorf("line %d: %s: %w", n, key, err)
		}
		out[key] = val
	}
	return out, nil
}

func parseEnvValue(raw string) (string, error) {
	if raw == "" {
		return "", nil
	}
	var val, rest string
	switch raw[0] {
	case '\'':
		end := strings.IndexByte(raw[1:], '\'')
		if end < 0 {
			return "", errors.New("missing closing quote")
		}
		val, rest = raw[1:1+end], raw[2+end:]
	case '"':
		var b strings.Builder
		i := 1
		for ; i < len(raw) && raw[i] != '"'; i++ {
			if raw[i] == '\\' && i+1 < len(raw) && (raw[i+1] == '"' || raw[i+1] == '\\') {
				i++
			}
			b.WriteByte(raw[i])
		}
		if i >= len(raw) {
			return "", errors.New("missing closing quote")
		}
		val, rest = b.String(), raw[i+1:]
	default:
		if j := strings.Index(raw, " #"); j >= 0 {
			raw = raw[:j]
		}
		return strings.TrimSpace(raw), nil
	}
	if rest = strings.TrimSpace(rest); rest != "" && !strings.HasPrefix(rest, "#") {
		return "", errors.New("text after the closing quote")
	}
	return val, nil
}

// EnvWithFile returns a getenv that reads the real environment first and the
// .env file at path second, so a variable set in the environment (even to "")
// overrides the file. A missing file is an error only when required.
func EnvWithFile(lookup func(string) (string, bool), readFile func(string) ([]byte, error), path string, required bool) (func(string) string, error) {
	file := map[string]string{}
	data, err := readFile(path)
	switch {
	case err == nil:
		if file, err = ParseEnvFile(data); err != nil {
			return nil, fmt.Errorf("config: %s: %w", path, err)
		}
	case required || !errors.Is(err, fs.ErrNotExist):
		return nil, fmt.Errorf("config: env file: %w", err)
	}
	return func(k string) string {
		if v, ok := lookup(k); ok {
			return v
		}
		return file[k]
	}, nil
}
