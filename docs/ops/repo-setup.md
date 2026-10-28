# Repository setup

For the project owner. These steps change GitHub settings, so only you run them, with your own account. Do them in order, when you move from agents pushing to `main` to pull-request review. Each step ends with a check.

Until then nothing here is applied: agents still merge to `main` directly, and the ruleset below would block that (see the ruling in [the plans README](../superpowers/plans/README.md)).

## 1. Machine user for the agents

GitHub never lets you approve a pull request that your own account opened. If agents open pull requests as you, the required code-owner review blocks every merge. So agents get their own account.

1. Sign out, or use a private window, and create a new GitHub account, for example `drag0n-flyer-bot`. Use an email address you control. Turn on two-factor authentication for it.
2. As yourself, open the repository's **Settings → Collaborators → Add people**, invite the bot account, and choose the **Write** role. Accept the invitation as the bot.
3. As the bot, open **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**:
   - **Resource owner:** `drag0n-flyer`
   - **Expiration:** 90 days
   - **Repository access:** only this repository
   - **Permissions:** Contents: Read and write; Pull requests: Read and write. Leave everything else at No access.
4. Give the token to the agent runtime as `GH_TOKEN` (for example in the environment of the machine that runs the agents). Never commit it, paste it into an issue, or put it in a workflow file.
5. Put a reminder in your calendar 80 days out to make a new token and replace the old one.

Check: as the bot, `gh api user --jq .login` prints the bot's name, and `gh api repos/drag0n-flyer/myApp --jq .permissions.push` prints `true`.

If you choose instead to let yourself bypass the rules (decision 2), skip this step and add yourself to `bypass_actors` in the script before step 2.

## 2. Branch ruleset

[`scripts/setup-branch-protection.sh`](../../scripts/setup-branch-protection.sh) creates the ruleset `main-protection` on the default branch, or updates it if it already exists, so it is safe to run again. It sets:

- a pull request is required, with 1 approval and code-owner review ([`.github/CODEOWNERS`](../../.github/CODEOWNERS))
- approvals are dismissed when new commits are pushed
- required checks: `test` (server), `app-id` (Apple apps), `check` (docs) and `gitleaks` (security), from GitHub Actions only
- no force push, no deletion, linear history
- nobody may bypass it

First look at what it will send, then apply it:

```sh
sh scripts/setup-branch-protection.sh --dry-run
sh scripts/setup-branch-protection.sh
```

Check: `gh api repos/drag0n-flyer/myApp/rulesets --jq '.[] | [.name, .enforcement] | @tsv'` prints `main-protection` and `active`, separated by a tab.

The four workflows run only when their files change (except `security`, which runs on every push and pull request). A pull request that touches, say, only documents never starts `server` or `apple`, so their checks stay "expected" and the pull request cannot merge. Before applying the ruleset, either make those workflows run on every pull request (and skip their work inside the job when nothing changed), or keep only `gitleaks` as a required check. The check names come from the job names; if a job is renamed, update the script and run it again.

## 3. Security settings

Turn on secret scanning, push protection and private vulnerability reporting:

```sh
gh api -X PATCH repos/drag0n-flyer/myApp \
  -F 'security_and_analysis[secret_scanning][status]=enabled' \
  -F 'security_and_analysis[secret_scanning_push_protection][status]=enabled'
gh api -X PUT repos/drag0n-flyer/myApp/private-vulnerability-reporting
```

You can do the same in **Settings → Code security**. Both commands are safe to run again.

Check: `gh api repos/drag0n-flyer/myApp --jq .security_and_analysis` shows both as `enabled`, and `gh api repos/drag0n-flyer/myApp/private-vulnerability-reporting --jq .enabled` prints `true`. The repository's **Security** tab now shows **Report a vulnerability**.
