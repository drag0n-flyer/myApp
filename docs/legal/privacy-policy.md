# [App] Privacy Policy

> **Draft — needs lawyer review before use.**

Last updated: DRAFT (not yet in force). This policy describes what the invite-only beta server does today. A lawyer must review it before the server opens.

## 1. Who runs this server

> **Draft — needs lawyer review before use.**

This server is run by the operator named below. [App] is software that anyone can run; this policy covers the server run by the project owner for the invite-only beta.

- Operator name: TO BE FILLED IN by the owner and lawyer.
- Contact for privacy questions: TO BE FILLED IN (this is the `abuseContact` address in the server policy, or a separate privacy address).
- Representative in the European Union, if the lawyer says one is needed: TO BE FILLED IN.

If you use a server run by someone else, that operator is responsible for that server, and this policy does not apply to it.

## 2. What we collect

> **Draft — needs lawyer review before use.**

The server keeps only what it needs to deliver your messages and calls. Each item below matches the server design, section 9, and the server's database tables.

- **Account data:** your username, your email address, and your display name. The display name is stored as plain text.
- **Phone number, optional:** only if you add one to your account. Other people can find you by it only if you turn on "Find me by phone", which is off by default.
- **Device data:** a device ID the server assigns to each of your devices, its name and type (for example iPhone or Mac), and when it was last seen. The device ID is linked to your account. It is not an advertising identifier.
- **Crash reports:** when the app crashes, a report of what the app was doing (no message content, contacts or other personal content) is sent to the operator so the problem can be fixed. Crash reports are not linked to your account. How they are sent and how long they are kept: TO BE FILLED IN.
- **Conversation membership:** which accounts are in a conversation together, and which of their devices are in it.
- **Message timing and size:** when a message was sent and how large it is. Not what it says in end-to-end encrypted chats.
- **Call timing:** when a call was set up between two accounts. Not what was said or shown.
- **Block lists and reports:** the accounts you have blocked, and reports you send about other accounts or messages. A report holds up to 50 message texts you chose to share, plus any note you add. It is sent by email to the operator's abuse contact through the email provider (see section 5), so the operator and the provider can read it.
- **Attachments:** photos and files are encrypted on your device before upload. They are deleted after the number of days set by `retention.attachmentDays` (30 days by default). The server also sees attachment sizes and timing.
- **Escrow copies:** only in chats marked as server-readable, the server keeps a copy it can read. See section 3.
- **IP addresses:** the server writes IP addresses to no logs. The server, the call relay servers and the push gateway still see your IP address for a moment while you connect.

For reference, this is the full list of what the server operator can see, as written in the server design:

- accounts, usernames, emails, display names
- device names, platforms, last-seen times
- which accounts share a conversation, and which devices are in it
- message timing and sizes
- call timing between accounts
- block lists and reports
- attachment sizes and timing

The push gateway can see which server sends to which opaque handle, and when. Apple can see the push token, and the timing and size of pushes. Because the gateway has to open each handle to reach Apple, it also sees your raw push token at that moment.

## 3. What we cannot see

> **Draft — needs lawyer review before use.**

In an end-to-end encrypted chat, messages, photos, files and voice or video are locked on your device and can only be unlocked on the devices of the people in the chat. The server operator cannot read them and holds no key that can. Call audio and video go between devices and are not stored.

Some chats on a server can be set up by the server administrator as server-readable. In those chats the server keeps a copy it can read, so the operator could read them. Server-readable chats are marked on the chat's info page. A chat that is end-to-end encrypted stays that way even if other chats on the same server are server-readable.

Even in encrypted chats, the operator can see the information listed in section 2 (who talks to whom, and when).

## 4. Why we use your data and the legal basis

> **Draft — needs lawyer review before use.**

The legal basis follows Article 6 of the GDPR. The lawyer must confirm each row.

| Purpose | Data | Legal basis (GDPR Art. 6) |
|---|---|---|
| Create and run your account | Username, email, display name | Contract: Art. 6(1)(b) |
| Sign in and link devices | Email, device data | Contract: Art. 6(1)(b) |
| Deliver messages and attachments | Conversation membership, message timing and size, attachments | Contract: Art. 6(1)(b) |
| Set up calls | Call timing, IP addresses (transient) | Contract: Art. 6(1)(b) |
| Send notifications | Push token, push timing | Contract: Art. 6(1)(b) |
| Stop abuse: blocks, reports, rate limits | Block lists, reports | Legitimate interests: Art. 6(1)(f) |
| Keep server-readable copies where the administrator chose that mode | Escrow copies | To be decided by the lawyer (legitimate interests or legal obligation) |
| Keep the service secure | Rate-limit counters held in memory | Legitimate interests: Art. 6(1)(f) |

## 5. Who else sees data

> **Draft — needs lawyer review before use.**

- **Apple:** to deliver notifications, Apple sees the push token and the timing and size of each push.
- **The project push gateway:** it passes notifications from the server to Apple. It sees which server sends to which handle and when, and, because it must open the handle to contact Apple, the raw push token at that moment. It does not see message content or account names.
- **Call relay operators:** when a call cannot go directly between devices, a relay server carries it. The relay sees both IP addresses, when the call starts and ends, and how much data flows. It does not see the call content.
- **Cloudflare, optional:** if the server administrator turns on Cloudflare as a fallback call relay, Cloudflare also sees your IP address and call volume. This server's administrator will state here whether it is on: TO BE FILLED IN.
- **Email delivery provider:** the provider the operator uses to send sign-in codes, and to forward reports to the abuse contact, sees your email address and when a code was sent. It also sees the content of each report (up to 50 message texts you chose to share, plus any note). Provider name: TO BE FILLED IN.

We do not sell your data and do not use it for advertising. [owner to confirm]

## 6. How long we keep data

> **Draft — needs lawyer review before use.**

These are the default values of the server's policy file.

- Undelivered messages in the inbox: 30 days (`retention.inboxDays`).
- Attachments: 30 days (`retention.attachmentDays`).
- Escrow copies in server-readable chats: 365 days (`retention.escrowDays`).
- Accounts and their data: until you delete them.
- Reports: kept until the operator has handled them. TO BE CONFIRMED by the lawyer.

When you remove a device, its record is deleted. When you delete your account, your account record is deleted, and with it your devices, sessions, KeyPackages (the keys others use to start encrypted chats with you), undelivered inbox items, conversation membership, push tokens and blocks.

## 7. Your rights

> **Draft — needs lawyer review before use.**

- **Access:** you can ask the operator for a copy of the data held about you.
- **Correction:** you can change your display name in the app, and ask the operator to correct other data.
- **Deletion:** you can delete your account inside the app. It takes effect immediately. The server operator cannot turn this off.
- **Objection and restriction:** you can ask the operator to stop or limit uses based on legitimate interests.
- **Complaints:** you can complain to your local data protection authority. TO BE FILLED IN by the lawyer, per country.

Write to the contact in section 1 to use these rights.

## 8. Children

> **Draft — needs lawyer review before use.**

The minimum age to use [App] is TO BE DECIDED by the lawyer (13 or 16, depending on the country). People under that age must not create an account.

## 9. Changes and contact

> **Draft — needs lawyer review before use.**

If we change this policy, we will post the new version on the server's privacy page and tell you in the app before the change takes effect. Questions go to the contact in section 1.
