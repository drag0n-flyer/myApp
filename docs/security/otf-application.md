# OTF Security Lab Application (Draft)

**Status:** draft answers for the owner. The owner submits at [apply.opentech.fund/security-lab](https://apply.opentech.fund/security-lab/) once plans 7 and 8 are feature-complete. Re-check the live form: its questions may differ from the headings below. Fields in brackets are for the owner to fill in.

## What OTF offers (sources)

- OTF's Security Lab gives free, short-term security support to internet freedom tools: software security audits, help with known security issues, and architecture and design reviews ([OTF Security Lab](https://www.opentech.fund/labs/security-lab/)).
- Tools must contribute to internet freedom anywhere in the world, and applicants must be 18 or older (same page).
- Results are published on an open-source basis (same page). OTF's own guide describes the lab and its process ([OTF guide](https://guide.opentech.fund/our-labs/red-team-lab)). The lab was formerly called the Red Team Lab.
- The lab page says OTF pays for audits of eligible projects. The roadmap assumed it audits projects it does not fund. Both fit this application, but confirm with OTF.

## Project summary

[App] is a free, open-source messenger for iPhone and Mac. Anyone can run their own server. Messages and calls are end-to-end encrypted with MLS (RFC 9420), using the mls-rs library. Voice and video calls are designed to keep working on weak and expensive networks, with low-data modes. The server is written in Go. The project is in an invite-only beta.

## Internet-freedom relevance

People who live with censorship, throttling, or surveillance need messaging they can trust and control. [App] offers:

- end-to-end encrypted chats, where the server operator cannot read messages;
- self-hosting, so a community does not depend on one company's server;
- low-data calls that keep working on poor networks;
- a design that keeps the data the server holds small.

We are open about what remains visible to a server operator (who talks to whom, and when). The [threat model](threat-model.md) lists it.

## Users and regions

Today: invited beta testers, [number and regions to be filled in by the owner]. Target users: people in regions with weak or censored networks, and the communities and organisations that host servers for them. We do not collect analytics about users.

## Ability to respond to and maintain findings

- The project owner reviews all security-sensitive code and all findings. [Names and contact to be filled in.]
- Code is written with AI coding agents, and the owner reviews code in cryptography, authentication, protocol state, the push gateway and the media build before it merges.
- A [threat model](threat-model.md) and open-risk list already exist, and we will update them with every finding.
- Findings get an owner and a fix or an accepted-risk decision. We aim to fix Critical and High findings before the public beta. [Target response times to be set by the owner.]
- A security contact and disclosure policy are published (`security.txt`).

## Requested service

A software security audit together with an architecture review, covering the areas in the [audit scope](audit-scope.md), in priority order:

1. MLS usage in the clients
2. the Notification Service Extension and key storage
3. identity (passkeys, email codes, sessions, linking)
4. the delivery service and escrow
5. the push gateway
6. call signaling and fingerprint binding
7. the WebRTC and Opus build pipeline

Artifacts we will provide: the threat model, the protocol specification, test vectors, and a fixed commit hash.

## Disclosure acknowledgment

We understand and accept that findings and the audit report are published after a responsible disclosure period. We will fix issues within that period where we can, and we agree with OTF and the auditor on the timing before publication.
