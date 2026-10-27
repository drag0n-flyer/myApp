# Vulnerability disclosure policy

This policy covers the whole project. Each operator of a self-hosted server may publish their own contact in `/.well-known/security.txt`; reports about the project's code go here.

## Scope

In scope:

- the server (`server/`)
- the push gateway
- the iPhone and Mac apps
- the protocol and its specification (`docs/protocol/`)

## How to report

- Email `security@example.org` (placeholder until the owner chooses the address). Encrypt if you can; ask for a key in a first message.
- Once the repository exists on GitHub, use its private vulnerability reporting ("Security" tab, "Report a vulnerability").

Please include what you found, how to reproduce it, and which version you tested. Do not open a public issue for a vulnerability.

## Response times

- We acknowledge your report within 3 business days.
- We triage it (confirm, rate, and tell you our plan) within 10 business days.
- We aim to fix and disclose together, within 90 days of the report. If a fix needs longer, we will agree a new date with you.

## Safe harbor

If you act in good faith and follow this policy, we will not start or support legal action against you for your research. Good faith means: you test only your own accounts and servers, you do not read or change other people's data, you do not degrade the service for others, and you give us time to fix the problem before you publish.

## Out of scope

- Servers run by other people. Report those to their operator.
- Rate-limit and denial-of-service tests.
- Social engineering of maintainers, operators, or users, and physical attacks.

## Credit

We will credit you in the release notes and advisory unless you ask us not to.
