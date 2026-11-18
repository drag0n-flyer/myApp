package delivery

import (
	"context"
	"errors"
	"github.com/drag0n-flyer/myApp/server/internal/ratelimit"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/jackc/pgx/v5"
)

// parseAccountList lowercases a list of account IDs and refuses a malformed
// ID (not_found, like an unknown one: the answer never says which), a
// duplicate, or the caller's own ID (bad_request).
func parseAccountList(in []string, self string) ([]string, error) {
	self = strings.ToLower(self)
	seen := map[string]bool{}
	out := make([]string, 0, len(in))
	for _, s := range in {
		if !uuidRE.MatchString(s) {
			return nil, httpx.Err("not_found", "no such account")
		}
		s = strings.ToLower(s)
		if s == self || seen[s] {
			return nil, httpx.Err("bad_request", "accounts must be distinct and not include yourself")
		}
		seen[s] = true
		out = append(out, s)
	}
	return out, nil
}

// groupCtx is what a membership endpoint learns, under the conversation row
// lock, before it decides anything.
type groupCtx struct {
	id      string
	epoch   int64
	version int64
	role    string // the caller's role
}

// lockGroup locks the conversation row and reads the caller's role. A missing
// conversation and a caller account that is not a member are both not_found.
// A direct conversation is refused with bad_request. Because the row lock is
// held to the end of the transaction, every decision made from the returned
// values is made on state no other membership change or commit can alter.
func lockGroup(ctx context.Context, tx pgx.Tx, id, callerAccount string) (groupCtx, error) {
	notFound := httpx.Err("not_found", "no such conversation")
	g := groupCtx{id: id}
	var kind string
	err := tx.QueryRow(ctx, `select kind, epoch, members_version from conversations where id = $1 for update`, id).Scan(&kind, &g.epoch, &g.version)
	if errors.Is(err, pgx.ErrNoRows) {
		return g, notFound
	}
	if err != nil {
		return g, err
	}
	err = tx.QueryRow(ctx, `select role from conversation_accounts where conversation_id = $1 and account_id = $2`, id, callerAccount).Scan(&g.role)
	if errors.Is(err, pgx.ErrNoRows) {
		return g, notFound
	}
	if err != nil {
		return g, err
	}
	if kind != "group" {
		return g, httpx.Err("bad_request", "this conversation is not a group")
	}
	return g, nil
}

// bumpAndNotify raises members_version and delivers a stored, pushed "members"
// item to recipient (device IDs, chosen by the caller before it changed
// conversation_devices). The caller's own device gets the item without a push.
func (m *Module) bumpAndNotify(ctx context.Context, tx pgx.Tx, g groupCtx, callerAccount, callerDevice string, recipients []string) (int64, error) {
	// Every device this change delivers to, locked once, sorted, before any
	// insert or later lock: the global order that commits and messages use.
	if _, err := lockDevices(ctx, tx, recipients); err != nil {
		return 0, err
	}
	var v int64
	if err := tx.QueryRow(ctx, `update conversations set members_version = members_version + 1 where id = $1 returning members_version`, g.id).Scan(&v); err != nil {
		return 0, err
	}
	var loud, quiet []string
	for _, d := range recipients {
		if d == callerDevice {
			quiet = append(quiet, d)
		} else {
			loud = append(loud, d)
		}
	}
	item := InboxItem{
		Kind: "members", ConversationID: g.id, Epoch: g.epoch, MembersVersion: &v,
		SenderAccountID: callerAccount, SenderDeviceID: callerDevice, SentAt: m.d.Now(),
	}
	if err := m.deliver(ctx, tx, loud, item); err != nil {
		return 0, err
	}
	if err := m.deliverPush(ctx, tx, quiet, item, core.PushNone); err != nil {
		return 0, err
	}
	return v, nil
}

// blockersIn is blockers for a direct conversation. A group ignores blocks:
// nobody is skipped, so a blocker still receives and is pushed everything.
func (m *Module) blockersIn(ctx context.Context, kind string, devs []devAcct, sender string) (map[string]bool, error) {
	if kind == "group" {
		return map[string]bool{}, nil
	}
	return m.blockers(ctx, devs, sender)
}

// lockAccounts takes a key-share lock on the named account rows, sorted, and
// returns how many exist. A transaction that deletes one of them (FOR UPDATE)
// serialises with it.
func lockAccounts(ctx context.Context, tx pgx.Tx, ids []string) (int, error) {
	got, err := queryStrings(ctx, tx, `select id::text from accounts where id = any($1::uuid[]) order by id for key share`, ids)
	return len(got), err
}

// takeTokens charges n tokens to key and refuses with rate_limited (and a
// Retry-After header) when the bucket cannot cover them.
func takeTokens(w http.ResponseWriter, l *ratelimit.Limiter, key string, n int) error {
	if ok, retry := l.AllowN(key, n); !ok {
		secs := max(1, int((retry+time.Second-1)/time.Second))
		w.Header().Set("Retry-After", strconv.Itoa(secs))
		return httpx.Err("rate_limited", "too many requests")
	}
	return nil
}

// dropBlockers removes the accounts that have blocked caller. It says nothing
// about it: the caller sees the same success either way.
func (m *Module) dropBlockers(ctx context.Context, accts []string, caller string) ([]string, error) {
	out := make([]string, 0, len(accts))
	for _, a := range accts {
		b, err := m.d.Blocks.IsBlocked(ctx, a, caller)
		if err != nil {
			return nil, err
		}
		if !b {
			out = append(out, a)
		}
	}
	return out, nil
}

// LeaveAllGroups implements core.GroupLeaver. For every group the account is
// in it does what a leave does: the account's devices leave delivery, the
// members version rises by one, the remaining member devices get a "members"
// item. A departing owner hands ownership to the longest-standing admin, else
// the longest-standing member (conversation_accounts.joined_at, then account
// ID); the last member leaving deletes the group. Direct conversations are
// left to the row cascade. All conversation rows are locked first, in ID
// order, then every device involved once in sorted order, matching the lock
// order of commits and messages.
func (m *Module) LeaveAllGroups(ctx context.Context, tx pgx.Tx, accountID string) (func(context.Context), error) {
	p := &pending{}
	ctx = context.WithValue(ctx, pendingKey{}, p)
	announce := func(c context.Context) { m.notifyAll(context.WithoutCancel(c), p) }

	ids, err := queryStrings(ctx, tx,
		`select ca.conversation_id::text from conversation_accounts ca join conversations c on c.id = ca.conversation_id
		 where ca.account_id = $1 and c.kind = 'group' order by ca.conversation_id`, accountID)
	if err != nil || len(ids) == 0 {
		return announce, err
	}
	rows, err := tx.Query(ctx, `select id::text, epoch from conversations where id = any($1::uuid[]) order by id for update`, ids)
	if err != nil {
		return announce, err
	}
	type grp struct {
		id         string
		epoch      int64
		role       string
		others     int
		recipients []string
	}
	var groups []*grp
	for rows.Next() {
		g := &grp{}
		if err := rows.Scan(&g.id, &g.epoch); err != nil {
			rows.Close()
			return announce, err
		}
		groups = append(groups, g)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return announce, err
	}
	// Re-read membership under the locks.
	lock, err := queryStrings(ctx, tx, `select id::text from devices where account_id = $1 order by id`, accountID)
	if err != nil {
		return announce, err
	}
	// senderDeviceID of the items is the all-zero UUID when the account has
	// no device (the column is a non-null uuid). Otherwise it is the lowest
	// device ID, so the choice is stable.
	ownDevice := "00000000-0000-0000-0000-000000000000"
	if len(lock) > 0 {
		ownDevice = lock[0]
	}
	live := groups[:0]
	for _, g := range groups {
		err := tx.QueryRow(ctx, `select role from conversation_accounts where conversation_id = $1 and account_id = $2`, g.id, accountID).Scan(&g.role)
		if errors.Is(err, pgx.ErrNoRows) {
			continue
		}
		if err != nil {
			return announce, err
		}
		if err := tx.QueryRow(ctx, `select count(*) from conversation_accounts where conversation_id = $1 and account_id <> $2`, g.id, accountID).Scan(&g.others); err != nil {
			return announce, err
		}
		if g.others > 0 {
			g.recipients, err = queryStrings(ctx, tx,
				`select cd.device_id::text from conversation_devices cd join devices d on d.id = cd.device_id
				 where cd.conversation_id = $1 and d.account_id <> $2 order by cd.device_id`, g.id, accountID)
			if err != nil {
				return announce, err
			}
			lock = append(lock, g.recipients...)
		}
		live = append(live, g)
	}
	if _, err := lockDevices(ctx, tx, lock); err != nil {
		return announce, err
	}
	for _, g := range live {
		if g.others == 0 {
			if _, err := tx.Exec(ctx, `delete from conversations where id = $1`, g.id); err != nil {
				return announce, err
			}
			continue
		}
		if _, err := tx.Exec(ctx, `delete from conversation_devices cd using devices d where cd.conversation_id = $1 and d.id = cd.device_id and d.account_id = $2`, g.id, accountID); err != nil {
			return announce, err
		}
		if _, err := tx.Exec(ctx, `delete from conversation_accounts where conversation_id = $1 and account_id = $2`, g.id, accountID); err != nil {
			return announce, err
		}
		if g.role == "owner" {
			if _, err := tx.Exec(ctx,
				`update conversation_accounts set role = 'owner' where conversation_id = $1 and account_id = (
				   select account_id from conversation_accounts where conversation_id = $1
				   order by (role = 'admin') desc, joined_at, account_id limit 1)`, g.id); err != nil {
				return announce, err
			}
		}
		var v int64
		if err := tx.QueryRow(ctx, `update conversations set members_version = members_version + 1 where id = $1 returning members_version`, g.id).Scan(&v); err != nil {
			return announce, err
		}
		if err := m.deliver(ctx, tx, g.recipients, InboxItem{
			Kind: "members", ConversationID: g.id, Epoch: g.epoch, MembersVersion: &v,
			SenderAccountID: accountID, SenderDeviceID: ownDevice, SentAt: m.d.Now(),
		}); err != nil {
			return announce, err
		}
	}
	return announce, nil
}

func (m *Module) groupRoutes(r *httpx.Router) {
	r.Authed("POST /v1/conversations/{id}/accounts", m.addAccounts)
	r.Authed("DELETE /v1/conversations/{id}/accounts/{account}", m.removeAccount)
	r.Authed("PUT /v1/conversations/{id}/accounts/{account}/role", m.setRole)
}

type versionResponse struct {
	MembersVersion int64 `json:"membersVersion"`
	Deleted        bool  `json:"deleted"`
}

func (m *Module) addAccounts(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	id := strings.ToLower(r.PathValue("id"))
	var in struct {
		Accounts []string `json:"accounts"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if !uuidRE.MatchString(id) {
		return httpx.Err("not_found", "no such conversation")
	}
	var out versionResponse
	// Tokens spent by a request that then fails are given back.
	charged := 0
	defer func() {
		if charged > 0 {
			m.groupAddLimit.Refund(p.AccountID, charged)
		}
	}()
	listed, listErr := parseAccountList(in.Accounts, p.AccountID)
	err := m.inTx(r.Context(), func(ctx context.Context, tx pgx.Tx) error {
		// Pin the named accounts before the conversation row (lock order:
		// accounts, conversation, devices). A concurrent deletion of one of
		// them either finished first (not_found below) or waits until this
		// commits and then removes it as a proper leave.
		if listErr == nil {
			if _, err := lockAccounts(ctx, tx, listed); err != nil {
				return err
			}
		}
		g, err := lockGroup(ctx, tx, id, p.AccountID)
		if err != nil {
			return err
		}
		out.MembersVersion = g.version
		if g.role != "owner" && g.role != "admin" {
			return httpx.Err("forbidden", "only an owner or admin may add accounts")
		}
		accts, err := listed, listErr
		if err != nil {
			return err
		}
		if len(accts) < 1 || len(accts) > maxGroupMembers-1 {
			return httpx.Err("bad_request", "accounts must hold 1 to 99 accounts")
		}
		// Charged by the size of the request, so the answer never depends on
		// who was skipped.
		if err := takeTokens(w, m.groupAddLimit, p.AccountID, len(accts)); err != nil {
			return err
		}
		charged = len(accts)
		var found int
		if err := tx.QueryRow(ctx, `select count(*) from accounts where id = any($1::uuid[])`, accts).Scan(&found); err != nil {
			return err
		}
		if found != len(accts) {
			return httpx.Err("not_found", "no such account")
		}
		// An account that blocked the caller is left out without a word.
		if accts, err = m.dropBlockers(ctx, accts, p.AccountID); err != nil {
			return err
		}
		// Already-members are skipped, so a retry is harmless.
		var current int
		var fresh int
		if err := tx.QueryRow(ctx, `select count(*) from conversation_accounts where conversation_id = $1`, id).Scan(&current); err != nil {
			return err
		}
		if err := tx.QueryRow(ctx,
			`select count(*) from unnest($2::uuid[]) a where not exists (select 1 from conversation_accounts where conversation_id = $1 and account_id = a)`,
			id, accts).Scan(&fresh); err != nil {
			return err
		}
		if current+fresh > maxGroupMembers {
			return httpx.Err("group_full", "a group holds at most 100 accounts")
		}
		if fresh == 0 {
			return nil
		}
		if _, err := tx.Exec(ctx,
			`insert into conversation_accounts (conversation_id, account_id) select $1, unnest($2::uuid[]) on conflict do nothing`, id, accts); err != nil {
			return err
		}
		recipients, err := m.memberDeviceIDs(ctx, tx, id)
		if err != nil {
			return err
		}
		out.MembersVersion, err = m.bumpAndNotify(ctx, tx, g, p.AccountID, p.DeviceID, recipients)
		return err
	})
	if err != nil {
		return err
	}
	charged = 0
	httpx.JSON(w, http.StatusOK, out)
	return nil
}

// memberDeviceIDs returns the devices in the conversation, sorted.
func (m *Module) memberDeviceIDs(ctx context.Context, tx pgx.Tx, id string) ([]string, error) {
	return queryStrings(ctx, tx, `select device_id::text from conversation_devices where conversation_id = $1 order by device_id`, id)
}

func (m *Module) removeAccount(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	id := strings.ToLower(r.PathValue("id"))
	target := strings.ToLower(r.PathValue("account"))
	if !uuidRE.MatchString(id) {
		return httpx.Err("not_found", "no such conversation")
	}
	var out versionResponse
	err := m.inTx(r.Context(), func(ctx context.Context, tx pgx.Tx) error {
		g, err := lockGroup(ctx, tx, id, p.AccountID)
		if err != nil {
			return err
		}
		out.MembersVersion = g.version
		if !uuidRE.MatchString(target) {
			return httpx.Err("not_found", "no such account")
		}
		self := target == strings.ToLower(p.AccountID)
		var targetRole string
		err = tx.QueryRow(ctx, `select role from conversation_accounts where conversation_id = $1 and account_id = $2`, id, target).Scan(&targetRole)
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.Err("not_found", "no such member")
		}
		if err != nil {
			return err
		}
		if self {
			if g.role == "owner" {
				var others int
				if err := tx.QueryRow(ctx, `select count(*) from conversation_accounts where conversation_id = $1 and account_id <> $2`, id, target).Scan(&others); err != nil {
					return err
				}
				if others == 0 {
					// The last member leaving deletes the conversation (its
					// escrow copies go with it). Inbox rows already queued stay.
					if _, err := tx.Exec(ctx, `delete from conversations where id = $1`, id); err != nil {
						return err
					}
					out.Deleted = true
					return nil
				}
				return httpx.Err("owner_must_transfer", "hand ownership to another member before leaving")
			}
		} else {
			switch {
			case g.role == "owner" && targetRole != "owner":
			case g.role == "admin" && targetRole == "member":
			default:
				return httpx.Err("forbidden", "you may not remove this account")
			}
		}
		// Notify everyone who is a member device now, the removed account's
		// devices included, then take that account's devices out of delivery.
		recipients, err := m.memberDeviceIDs(ctx, tx, id)
		if err != nil {
			return err
		}
		if _, err := lockDevices(ctx, tx, recipients); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `delete from conversation_accounts where conversation_id = $1 and account_id = $2`, id, target); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx,
			`delete from conversation_devices cd using devices d
			 where cd.conversation_id = $1 and d.id = cd.device_id and d.account_id = $2`, id, target); err != nil {
			return err
		}
		out.MembersVersion, err = m.bumpAndNotify(ctx, tx, g, p.AccountID, p.DeviceID, recipients)
		return err
	})
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, out)
	return nil
}

func (m *Module) setRole(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	id := strings.ToLower(r.PathValue("id"))
	target := strings.ToLower(r.PathValue("account"))
	var in struct {
		Role string `json:"role"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if !uuidRE.MatchString(id) {
		return httpx.Err("not_found", "no such conversation")
	}
	var out versionResponse
	err := m.inTx(r.Context(), func(ctx context.Context, tx pgx.Tx) error {
		g, err := lockGroup(ctx, tx, id, p.AccountID)
		if err != nil {
			return err
		}
		out.MembersVersion = g.version
		if g.role != "owner" {
			return httpx.Err("forbidden", "only the owner may change roles")
		}
		if in.Role != "owner" && in.Role != "admin" && in.Role != "member" {
			return httpx.Err("bad_request", "role must be owner, admin or member")
		}
		if !uuidRE.MatchString(target) {
			return httpx.Err("not_found", "no such member")
		}
		if target == strings.ToLower(p.AccountID) {
			return httpx.Err("bad_request", "the owner's own role changes only by handing ownership to another member")
		}
		var targetRole string
		err = tx.QueryRow(ctx, `select role from conversation_accounts where conversation_id = $1 and account_id = $2`, id, target).Scan(&targetRole)
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.Err("not_found", "no such member")
		}
		if err != nil {
			return err
		}
		if targetRole == in.Role {
			return nil
		}
		if in.Role == "owner" {
			// Demote first: at most one owner exists at any instant.
			if _, err := tx.Exec(ctx, `update conversation_accounts set role = 'admin' where conversation_id = $1 and account_id = $2`, id, p.AccountID); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(ctx, `update conversation_accounts set role = $3 where conversation_id = $1 and account_id = $2`, id, target, in.Role); err != nil {
			return err
		}
		recipients, err := m.memberDeviceIDs(ctx, tx, id)
		if err != nil {
			return err
		}
		out.MembersVersion, err = m.bumpAndNotify(ctx, tx, g, p.AccountID, p.DeviceID, recipients)
		return err
	})
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, out)
	return nil
}
