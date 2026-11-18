package delivery_test

import (
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

// groupWorld adds a roster of accounts for group tests.
type groupWorld struct {
	world
	dan, eve core.Principal
}

func newGroupWorld(t *testing.T) groupWorld {
	t.Helper()
	w := newWorld(t)
	return groupWorld{w, testkit.Account(t, w.d, "dan"), testkit.Account(t, w.d, "eve")}
}

func (w world) createGroup(t *testing.T, p core.Principal, accounts ...string) *testkit.Response {
	t.Helper()
	if accounts == nil {
		accounts = []string{}
	}
	return w.c.Do(&p, "POST", "/v1/conversations", map[string]any{"kind": "group", "accounts": accounts})
}

type member struct {
	Account string `json:"account"`
	Role    string `json:"role"`
}

type groupState struct {
	ConversationID string   `json:"conversationID"`
	Mode           string   `json:"mode"`
	Epoch          int64    `json:"epoch"`
	Kind           string   `json:"kind"`
	Members        []member `json:"members"`
	MembersVersion int64    `json:"membersVersion"`
}

func (w world) state(t *testing.T, p core.Principal, id string) (groupState, *testkit.Response) {
	t.Helper()
	r := w.c.Do(&p, "GET", "/v1/conversations/"+id, nil)
	var s groupState
	if r.Status == 200 {
		r.Decode(t, &s)
	}
	return s, r
}

func (s groupState) role(account string) string {
	for _, m := range s.Members {
		if m.Account == account {
			return m.Role
		}
	}
	return ""
}

func TestCreateGroup(t *testing.T) {
	w := newGroupWorld(t)
	r := w.createGroup(t, w.ana1, w.bob1.AccountID, w.carol.AccountID)
	if r.Status != 200 {
		t.Fatalf("create: %s", r)
	}
	var c struct {
		convo
		Kind string `json:"kind"`
	}
	r.Decode(t, &c)
	if c.Kind != "group" || c.Mode != "e2ee" || c.Epoch != 0 || c.ConversationID == "" {
		t.Fatalf("create: %s", r)
	}
	s, gr := w.state(t, w.ana1, c.ConversationID)
	if gr.Status != 200 || s.Kind != "group" || len(s.Members) != 3 || s.MembersVersion != 0 {
		t.Fatalf("state: %s", gr)
	}
	if s.role(w.ana1.AccountID) != "owner" || s.role(w.bob1.AccountID) != "member" || s.role(w.carol.AccountID) != "member" {
		t.Fatalf("roles: %+v", s.Members)
	}
	var devs int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversation_devices where conversation_id = $1`, c.ConversationID).Scan(&devs)
	if devs != 1 {
		t.Fatalf("member devices = %d", devs)
	}
}

func TestCreateGroupRefusals(t *testing.T) {
	w := newGroupWorld(t)
	many := []string{}
	for i := 0; i < 100; i++ {
		many = append(many, core.NewID())
	}
	for name, accts := range map[string][]string{
		"empty": {}, "self": {w.ana1.AccountID, w.bob1.AccountID},
		"twice":          {w.bob1.AccountID, w.bob1.AccountID},
		"twiceMixedCase": {w.bob1.AccountID, strings.ToUpper(w.bob1.AccountID)},
		"hundred":        many,
	} {
		if r := w.createGroup(t, w.ana1, accts...); r.Status != 400 || r.Code() != "bad_request" {
			t.Fatalf("%s: %s", name, r)
		}
	}
	unknown := core.NewID()
	r := w.createGroup(t, w.ana1, w.bob1.AccountID, unknown)
	if r.Status != 404 || r.Code() != "not_found" || strings.Contains(string(r.Body), unknown) {
		t.Fatalf("unknown: %s", r)
	}
	if r := w.createGroup(t, w.ana1, w.bob1.AccountID, "nope"); r.Status != 404 {
		t.Fatalf("malformed: %s", r)
	}
	if r := w.c.Do(&w.ana1, "POST", "/v1/conversations", map[string]any{"kind": "channel", "accounts": []string{w.bob1.AccountID}}); r.Status != 400 {
		t.Fatalf("bad kind: %s", r)
	}
	var n int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversations`).Scan(&n)
	if n != 0 {
		t.Fatalf("refused creates left %d conversations", n)
	}
	// 99 others is the maximum.
	ok := []string{}
	for i := 0; i < 99; i++ {
		ok = append(ok, testkit.Account(t, w.d, fmt.Sprintf("user%d", i)).AccountID)
	}
	if r := w.createGroup(t, w.ana1, ok...); r.Status != 200 {
		t.Fatalf("99 others: %s", r)
	}
}

func TestDirectCreateStillDirect(t *testing.T) {
	w := newGroupWorld(t)
	for _, body := range []map[string]any{
		{"members": []string{w.bob1.AccountID}},
		{"kind": "direct", "members": []string{w.bob1.AccountID}},
	} {
		r := w.c.Do(&w.ana1, "POST", "/v1/conversations", body)
		var c struct{ ConversationID, Kind string }
		r.Decode(t, &c)
		if r.Status != 200 || c.Kind != "direct" {
			t.Fatalf("%v: %s", body, r)
		}
		s, _ := w.state(t, w.ana1, c.ConversationID)
		if s.Kind != "direct" || len(s.Members) != 2 || s.role(w.ana1.AccountID) != "member" || s.role(w.bob1.AccountID) != "member" {
			t.Fatalf("direct state: %+v", s)
		}
	}
}

func TestGetGroupMembersOnly(t *testing.T) {
	w := newGroupWorld(t)
	var c convo
	w.createGroup(t, w.ana1, w.bob1.AccountID).Decode(t, &c)
	if _, r := w.state(t, w.bob1, c.ConversationID); r.Status != 200 {
		t.Fatalf("member account read: %s", r)
	}
	if _, r := w.state(t, w.carol, c.ConversationID); r.Status != 404 || r.Code() != "not_found" {
		t.Fatalf("outsider: %s", r)
	}
}
