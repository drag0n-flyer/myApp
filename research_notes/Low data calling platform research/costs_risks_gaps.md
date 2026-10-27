# Commonly Overlooked Costs, Legal/Compliance Obligations, App Store Policies, and Project Risks: Self-Hostable E2EE Messaging and Calling App (iOS/macOS + Go server, AI-agent-written), as of Oct 2026

Research date: 2026-10-27. Scope excludes the technical details of noise suppression, codecs, TURN/SFU, E2EE libraries and push gateways, which other researchers cover. Items marked **[verified 2026-10]** were fetched from primary sources this session. Items marked **[not re-verified]** come from well-known canonical sources that were not re-fetched this session, and the writer should spot-check them.

---

## Q1. Fixed and recurring costs (Apple, infra, devices, AI agent subscriptions)

### Takeaway
The unavoidable cash costs are small: Apple's $99/yr plus a domain. The biggest recurring line is AI coding agents, at about $100–$200 per heavy user per month per vendor on flat plans, and much more on raw API billing. Next come SMS verification, if phone numbers are used, and the human time for review and compliance, which is easy to underestimate.

### Cited Findings
**Apple**
- Apple Developer Program: "99 USD per membership year, or in local currency where available". Eligible nonprofits, accredited educational institutions and government entities can request a fee waiver **[verified 2026-10]**. — [Apple: What's included](https://developer.apple.com/programs/whats-included/)
- Guideline 5.1.1(ix): apps in highly regulated fields "or that require sensitive user information should be submitted by a legal entity… and not by an individual developer" **[verified 2026-10]**. A messaging app holding private communications is plausibly "sensitive". Enrolling as an organization requires a legal entity and, per Apple's enrollment flow, a D-U-N-S number (free). D-U-N-S was not confirmed on the page fetched. — [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- One $99 membership covers iOS and macOS distribution, including Developer ID notarization for macOS apps distributed outside the Mac App Store. Code signing therefore costs nothing extra **[not re-verified]**. — [Apple: What's included](https://developer.apple.com/programs/whats-included/)

**AI coding agents. Anthropic (claude.com/pricing, fetched 2026-10-27) [verified 2026-10]**
- Pro: $20/mo monthly, or $17/mo billed annually ($200 up front). Claude Code is included.
- Max 5x / Max 20x: "From $100/mo". The table shows "New Monthly API credits" of $100/mo and $200/mo for the two Max tiers. Historically Max 5x = $100 and Max 20x = $200; the fetched page did not spell out the 20x price.
- Team: standard seat $20/seat/mo annual or $25 monthly; premium seat $100/seat/mo annual or $125 monthly. Enterprise: $20/seat/mo annual, 20-seat minimum, **plus usage at API rates**.
- API per million tokens: Opus 5.5 $4 in / $20 out (cache read $0.20, 5-min cache write $5); Sonnet 5.5 $2 / $10; Haiku 5.5 $0.10 / $0.50 for prompts of 100K tokens or fewer; Fable 5.1 $10 / $50. US-only inference costs 1.1x. Opus 5.5 "fast mode" costs 2x.
- Limits: rolling 5-hour session windows plus weekly limits. Web, desktop and Claude Code share one usage pool. Anthropic "may apply other limits… at its discretion". Paid plans can turn on usage credits at API rates. — [Claude pricing](https://claude.com/pricing)

**AI coding agents. OpenAI Codex (learn.chatgpt.com/docs/pricing, fetched 2026-10-27) [verified 2026-10]**
- Codex is included in ChatGPT Free, Go ($8/mo), Plus ($20/mo), Pro ("from $100/month", tiers $100/$200/$500; "Pro currently has no five-hour limit"), Business ($20/user/mo annual, $25 monthly, 2+ users) and Enterprise/Edu (contact sales).
- Plus and Business local-message estimates per 5 hours: GPT-6.1 Sol 15–160, GPT-6 Astra 5–45, GPT-6.1 Terra 35–285, GPT-6 Luna 350–3,000. Weekly limits may also apply. Cloud tasks use more allowance than local messages.
- Credit rates per 1M tokens (credits, not $): GPT-6.1 Sol 50 in / 250 out; GPT-6 Astra 250 / 1,250. Fast mode costs 2x and Ultrafast 6x. A "typical GPT-5.6 Sol task may use 2–15 credits."
- Dates: GPT-5.5 retired from Codex with ChatGPT sign-in on 2026-10-14. GPT-5.6 Sol promotional pricing runs at least through 2026-11-21. Model churn is a planning risk, because agent behavior changes under the team mid-project. — [OpenAI Codex pricing](https://learn.chatgpt.com/docs/pricing)
- The openai.com/chatgpt/pricing page returned HTTP 403 to the fetcher, and the API-key $/token prices for Codex models were not retrieved (see Gaps).

**Verification and SMS**
- Twilio Verify: **$0.05 per successful verification** plus channel fee. SMS adds $0.0083/SMS in the US; WhatsApp adds $0.0034 per auth template (US); voice and email are $0.05 per verification **[verified 2026-10]**. — [Twilio Verify pricing](https://www.twilio.com/en-us/verify/pricing)
- Twilio programmable SMS to India: **$0.0832 per segment**, "Pricing current as of October 2026". The parser flagged the table as ambiguous between columns, and carrier fees may apply. India also requires DLT template registration for A2P SMS **[DLT not re-verified]**. — [Twilio SMS pricing India](https://www.twilio.com/en-us/sms/pricing/in)

### Inferences
- **Budget sketch for a 3-person team** (inference, prices as above): Apple $99/yr. Domain plus DNS about $15–$60/yr; Let's Encrypt TLS is free. AI agents: two to three heavy users each on Claude Max 20x (about $200) plus ChatGPT Pro ($200) comes to about **$1,200–$1,800/mo**. That is far above infra costs early on. The Enterprise "seat + API usage" model can exceed this for heavy agentic loops.
- **Infra for project-run default servers** (inference; hosting prices were not fetched this session): a small Go server plus Postgres, one or two TURN relays and a push gateway on budget VPS or bare metal plausibly cost tens of USD/mo. **TURN egress bandwidth is the variable cost that blows up.** Relayed video at about 1–2 Mbps per stream adds up to hundreds of GB per heavy user per month on hyperscalers that bill egress. Choose providers with large included egress, and meter and cap relay use per account.
- **Monitoring**: self-hosted Prometheus/Grafana/Loki is free but costs ops time. Uptime pings and status-page SaaS have free tiers. Watch that monitoring does not leak metadata such as IPs and user IDs into third-party SaaS. The project's privacy policy must list that SaaS as a processor.
- **Physical test devices** (inference): test on (a) the oldest iPhone the minimum iOS supports, for thermal and CPU limits during video plus DSP; (b) a mid-range current model; (c) a current Pro model; (d) an iPhone SE-class small screen, for layout and Dynamic Type; (e) an Apple Silicon Mac and, if supported, an Intel Mac; (f) at least one iPad if iPad is in scope. Simulators cannot test CallKit, PushKit VoIP pushes, Bluetooth/AirPods route changes or real network handover, so real devices are mandatory. Apple's Network Link Conditioner, part of Additional Tools for Xcode, is free. A cheap travel router plus Linux `tc netem` covers loss and jitter emulation.
- **Hidden costs teams miss**: lawyer review of ToS and privacy policy (one-off, often several thousand USD); a bookkeeping entity if enrolling as an organization; Apple small-business program paperwork if the app is ever monetized; a security audit or pentest before claiming "E2EE" (often five figures USD for crypto-protocol review); SMS fraud ("SMS pumping" or toll fraud on verification endpoints) inflating SMS bills.

### Gaps
- OpenAI API $/MTok for the GPT-6.x Codex models was not retrieved (the pricing page linked out and the ChatGPT pricing page 403'd).
- The explicit Max 20x monthly price was not stated in the fetched text. $200 is historical.
- No hosting, TURN or bandwidth vendor prices were fetched this session, so infra figures above are inference only.
- India DLT registration cost and process for OTP SMS was not verified.

---

## Q2. App Store review rules for messaging/VoIP apps

### Takeaway
Messaging apps are UGC apps under Guideline 1.2: they need filtering, reporting, blocking and published contact information, even when E2EE. Account creation forces in-app account deletion (5.1.1(v)). Any third-party social login forces an equivalent privacy-preserving login (4.8). Encryption triggers export-compliance questions every build unless Info.plist is set, and France and the US have separate obligations. CallKit must be disabled in China.

### Cited Findings
- **1.2 UGC (verbatim, [verified 2026-10])**: apps with user-contributed content "must include: A method for filtering objectionable material from being posted… A mechanism to report offensive content and timely responses to concerns… The ability to block abusive users from the service… Published contact information so users can easily reach you." Apps "used primarily for… Chatroulette-style experiences, random or anonymous chat… do not belong on the App Store." "Developers who fail to implement appropriate safeguards or fail to remediate identified violations after notice may be considered in violation of the Developer Code of Conduct." — [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- **2.5.14 [verified 2026-10]**: apps "must request explicit user consent and provide a clear visual and/or audible indication when recording… This includes any use of the device camera, microphone, screen recordings." This is relevant if call recording or voice notes are added. — [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- **2.5.4**: background modes may be used only for intended purposes, VoIP among them. **3.1.3(f)**: free companion apps for a paid web VoIP service need no IAP if there is no in-app purchasing or call-to-action **[verified 2026-10]**. — [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- **4.8 Login Services [verified 2026-10]**: if a third-party or social login (Google, Facebook and others) is used for the primary account, the app "must also offer as an equivalent option another login service" that limits data to name and email, allows email hiding, and does no ad tracking without consent. This is Sign in with Apple in practice. It is **not required if the app "exclusively uses your company's own account setup and sign-in systems"**, so phone, email or username-and-key own-account systems avoid it. — [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- **5.1.1(v) [verified 2026-10]**: "If your app supports account creation, you must also offer account deletion within the app." Apple's FAQ: deletion must include UGC "shared with others, such as photos, videos, text posts". Complying only where GDPR or CCPA applies is "not sufficient"; everyone must be able to delete. Non-regulated apps "should not require people to make a phone call, send an email" to delete. — [Apple: Offering account deletion](https://developer.apple.com/support/offering-account-deletion-in-your-app/)
- **5.1.1(i)**: privacy policy link in App Store Connect and in-app. It must identify collected data, third parties, and retention and deletion policy **[verified 2026-10]**. — [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- **Export compliance [verified 2026-10]**: "If your app uses, accesses, contains, implements, or incorporates encryption… you need to determine your export compliance requirements in App Store Connect". Setting the Info.plist key (`ITSAppUsesNonExemptEncryption`) avoids repeated questions. Apple puts all liability on the developer: "you're responsible for all liabilities associated with misinterpretation of export regulations or claiming exemption inaccurately". It defines "non-standard cryptography" as proprietary or unpublished algorithms or protocols not adopted by IEEE, IETF, ISO, ITU, ETSI, 3GPP, TIA or GSMA. **France**: "import and export of encryption apps distributed in France are also controlled… main items of control for France are Secure Storage, Secure Communications" (refer to ANSSI). — [Apple: Export compliance overview](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance)
- **US BIS annual self-classification report [verified 2026-10]**: required for items exported under License Exception ENC §740.17(b)(1) unless a CCATS exists. It is due **Feb 1** for the prior calendar year, in **CSV only** with 12 specified columns. The authorization type may be "ENC or MMKT". If nothing changed, email that fact. — [BIS: Annual self-classification](https://www.bis.gov/learn-support/encryption-controls/annual-self-classification)
- Some law-firm summaries say mass-market items classified 5A992.c/5D992.c are excluded from the report. The BIS page fetched does not mention that exclusion, so the sources conflict and need verification. — [Davis Wright Tremaine 2024](https://www.dwt.com/insights/2024/02/bureau-of-industry-security-export-reports-due); [Thompson Hine](https://www.thompsonhinesmartrade.com/2024/01/annual-and-semi-annual-encryption-reports-due-to-bis-by-february-1-2024/)
- Mass-market encryption provisions moved from EAR §742.15 to §740.17. — [Mondaq](https://www.mondaq.com/Article/535008)
- **CallKit in China**: Apple told developers that MIIT "requested that CallKit functionality be deactivated in all apps available on the China App Store". VoIP itself is still allowed. The accepted workaround is to detect region and disable CallKit at runtime, though automated review may still reject and need an appeal. — [VentureBeat 2018](https://venturebeat.com/2018/05/21/apple-pulls-callkit-apps-from-chinas-app-store-again-appeasing-government); [CounterPath support](https://support.counterpath.com/hc/callkit-in-china); [Apple dev forums](https://developer.apple.com/forums/thread/103083)
- **2.5.12**: apps using CallKit for call blocking must block only confirmed spam and disclose criteria **[verified 2026-10]**. — [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)

### Inferences
- **E2EE vs 1.2 "filtering"**: the server cannot see content, so meet 1.2 with client-side controls: block, report with user-consented forwarding of the reported messages (the "message franking" approach, as Meta did for WhatsApp), mute, message requests from unknown contacts, and optional on-device media blur. Publish a contact or abuse email. App Review checks for visible block and report UI, so put them in the screenshots and reviewer notes.
- **Self-hosting complicates 1.2**: if the app connects to arbitrary third-party servers, Apple can still hold the app developer responsible. Mitigations: in-app report goes to the *server operator* (with a server-provided abuse contact), plus a project-level contact for app issues. A reviewer demo account on the project server is required for review anyway.
- **Avoid "random/anonymous chat" positioning**: no public user directory or "meet strangers" feature.
- **Account deletion for self-hosted**: deletion must work for the server the account is on. The Go server must implement a delete-account API that self-hosters cannot disable, or the client must at least delete local data and request deletion.
- Use only standard published primitives and protocols (Signal Protocol/X3DH/PQXDH, MLS RFC 9420, SRTP, AES-GCM, X25519) to stay outside "non-standard cryptography". Keep a dated export-compliance memo: ECCN determination, mass-market rationale, and the BIS report decision.
- **App Privacy "nutrition labels"** must reflect what the *project's* default server and any crash or analytics SDK collect, such as phone number, contacts if discovery uploads hashes, and identifiers. These labels are commonly wrong for E2EE apps, which still collect metadata.

### Gaps
- Whether China's CallKit ban is still enforced in 2026 (all sources are from 2018). No current Apple statement was found.
- The ANSSI declaration specifics (form, timeline) for a messaging app distributed in France were not fetched. Apple's page only refers to ANSSI.
- Whether mass-market 5D992.c apps must file the BIS annual report is a conflict between sources. Consult the EAR text or counsel.
- The tail of the App Review Guidelines page (characters 100K and later) and its "last updated" date were not read.

---

## Q3. Legal and regulatory obligations (EU/UK/US/India): operator vs. software-only

### Takeaway
Running default servers makes the project a GDPR controller and a hosting or interpersonal-communications service under DSA, UK OSA and India's IT Rules. Shipping only self-hostable software sharply reduces, but does not remove, the project's exposure (App Store 1.2 responsibilities, export control, CSAM reporting if it hosts anything). Self-hosters become controllers and operators themselves. The EU permanent CSAR (chat control) was **not adopted** as of Oct 2026. Trilogues continue, and E2EE appears to be excluded from detection, but risk-assessment, reporting and possible age-verification duties are likely. The UK OSA s.121 notices remain a latent threat to E2EE.

### Cited Findings
**EU: CSAR / "chat control" [verified 2026-10 via Wikipedia; Wikipedia flags LLM-generated text needing review]**
- Commission proposal 11 May 2022. The Council mandate of **26 Nov 2025** removed mandatory detection orders but kept mandatory **risk assessment and mitigation**, voluntary detection, and creation of the EU Centre. Parliament's 2023 position excluded E2EE communications from detection orders. Trilogues began 9 Dec 2025. By June 2026 compromises covered "almost the entire Regulation" except detection, and reporting indicates agreement that E2EE should be excluded from detection technologies. Messaging services must use **age verification if their risk assessment finds significant grooming risk**. Interpersonal-communication providers report to the EU Centre. Fines run **up to 6% of worldwide annual turnover**. — [Wikipedia: Chat Control](https://en.wikipedia.org/wiki/Chat_Control)
- **Interim regulation**: 2021/1232 ceased to apply **3 Apr 2026** after Parliament voted down an extension on 26 Mar 2026. The replacement **Regulation (EU) 2026/1881** was adopted 24 Jul 2026, published in the OJ 28 Jul 2026, and applies **until 3 Apr 2028**. It covers voluntary detection only. — [Wikipedia: Chat Control](https://en.wikipedia.org/wiki/Chat_Control)
- Trackers disagree on the next steps: a "sixth trilogue 29 September 2026" and a possible seventh trilogue on **8 Dec 2026**. These are advocacy sources, and some projections, such as July 2026 adoption, are already outdated. — [chat-control.eu](https://chat-control.eu/); [Patrick Breyer](https://www.patrick-breyer.de/en/posts/chat-control/); [Wire blog](https://wire.com/en/blog/chat-control-explained)

**UK: Online Safety Act**
- Under s.121 Ofcom can issue "Technology Notices" requiring "accredited technology" to identify CSEA or terrorism content. Critics say this is likely client-side scanning and that E2EE services could only comply by breaking E2EE. Ofcom's consultation "does not take a view" on technology feasibility in E2EE environments. — [Internet Society response to Ofcom](https://ofcom.org.uk/siteassets/resources/documents/consultations/category-1-10-weeks/consultation-technology-notices/responses/internet-society.pdf); [Index on Censorship, Nov 2025](https://www.indexoncensorship.org/2025/11/new-report-breaking-encryption-is-legally-and-practically-unworkable/)
- One 2026 opinion piece claims Ofcom issued a first s.121 notice in spring 2026. **Unverified**: no Ofcom or major-outlet corroboration was found. TechRadar reports that Ofcom plans to expand its CSAM focus in 2026. Apple's withdrawal of iCloud Advanced Data Protection in the UK was ordered under the **Investigatory Powers Act**, not the OSA. — [TechRadar](https://www.techradar.com/vpn/vpn-privacy-security/ofcom-wants-to-double-down-on-file-monitoring-in-2026)
- Separately, the OSA applies to user-to-user services with "links to the UK". Duties include illegal-content risk assessments, children's-access assessments and record-keeping, and they apply even to small services **[not re-verified]**. — [Ofcom: Online safety](https://www.ofcom.org.uk/online-safety)

**India**
- IT Rules 2021 Rule 4(2) requires "significant social media intermediaries" (over 5 million registered Indian users) to enable identification of the "first originator", known as traceability. WhatsApp challenged it in the Delhi High Court in May 2021, arguing it breaks E2EE. Notice was issued to MeitY in Aug 2021. **No 2026 status was found.** — [LiveLaw](https://www.livelaw.in/amp/news-updates/delhi-high-court-adjourns-whatsapps-plea-against-traceability-clause-under-new-it-rules-178461); [Tribune India](https://www.tribuneindia.com/news/nation/delhi-high-court-asks-centre-to-respond-to-pleas-by-fb-whatsapp-challenging-it-rules-302807)

**Licensing-adjacent legal precedent and CSAM (US)**
- US providers of electronic communication service must report apparent CSAM they become aware of to NCMEC's CyberTipline (18 U.S.C. §2258A). There is no duty to proactively scan. — [18 U.S.C. 2258A (LII)](https://www.law.cornell.edu/uscode/text/18/2258A) **[not re-verified]**

### Inferences
- **Operator of default servers (EU users)**: under GDPR the project is controller for account data, phone and email, IPs, push tokens, contact-discovery data and logs. It needs a privacy policy, a records-of-processing (Art. 30) entry, DPAs with every sub-processor (hosting, SMS, email, crash reporting), a lawful basis (contract and legitimate interest), retention limits, DSAR handling, 72-hour breach notification (Art. 33), and an EU representative (Art. 27) if not established in the EU. Fines reach the higher of €20M or 4% of turnover. **[GDPR articles not re-fetched: [GDPR text](https://eur-lex.europa.eu/eli/reg/2016/679/oj)]**
- **DSA**: a messaging service is a "mere conduit" or hosting service. Even micro and small enterprises must have a single point of contact, a legal representative if non-EU, ToS transparency and notice-and-action for hosted content such as profile names and pictures. Many Art. 15/24 reporting duties exempt micro and small enterprises **[not re-verified: [DSA text](https://eur-lex.europa.eu/eli/reg/2022/2065/oj)]**.
- **Software-only (self-hosting)**: each self-hoster is a controller and service provider. The project should ship operator documentation: a template privacy policy and ToS, an abuse contact field in server config surfaced in-app, default log-minimization, and a data-deletion API. The project still has App Store 1.2 duties for the client and export-control duties for distribution.
- **Lawful-intercept and data requests**: publish a law-enforcement guide saying what metadata exists (ideally almost nothing, Signal-style) and keep a transparency report. Minimizing stored metadata is the main mitigation, because data not held cannot be compelled. Do not promise "we will never comply"; promise "we hold X only".
- **India-based team**: the DPDP Act 2023 and its Rules, CERT-In's 6-hour cyber-incident reporting directions (Apr 2022), and the Telecommunications Act 2023 (possible OTT licensing debates) are relevant. All are **[not re-verified this session]** and should be checked by Indian counsel. Traceability only bites above 5M Indian users, but government blocking orders under IT Act s.69A apply to any intermediary.
- **DMCA**: low relevance for E2EE content. Registering a DMCA agent ($6 at the US Copyright Office) gives safe harbor for any hosted non-E2EE content (avatars, public channels) **[fee not re-verified]**.
- **ToS must-haves**: acceptable-use, age minimum (13+ or 16+ in some EU states; check minors' guidance under 5.1.4), abuse reporting, termination, no-warranty for self-hosted builds, export-control clause (no embargoed countries or SDN users), and governing law.

### Gaps
- No official EU source (Council or Parliament press release) for the trilogue status. Wikipedia carries an "LLM-generated text" warning.
- No official confirmation of any s.121 Technology Notice.
- 2026 status of the WhatsApp v. Union of India traceability case is unknown. Indian DPDP Rules commencement dates for specific duties were not verified.
- DSA micro and small-enterprise exemption specifics were not re-fetched.

---

## Q4. Open-source licensing strategy (AGPL dependencies, App Store compatibility)

### Takeaway
libsignal is AGPLv3 and "use outside of Signal is unsupported". Linking it into the iOS client effectively makes the client AGPL, and the full corresponding source must be published. Signal itself ships AGPL apps on the App Store because it owns the copyright (CLA). The VLC precedent shows that a third-party GPL copyright holder can force removal. For the Go server, AGPL deters closed SaaS forks, while Apache-2.0 maximizes adoption and allows commercial forks.

### Cited Findings
- libsignal: "Licensed under the GNU AGPLv3"; "Use outside of Signal is unsupported"; "All APIs and implementations are subject to change without notice"; a CLA is required for contributions **[verified 2026-10]**. — [libsignal GitHub](https://github.com/signalapp/libsignal)
- VLC was pulled from the iOS App Store in Jan 2011 after a VLC copyright holder (Rémi Denis-Courmont) complained that App Store terms (usage rules and DRM) conflict with GPLv2. VLC returned in 2013 after relicensing libVLC to LGPL/MPL-compatible terms. GNU Go was also pulled in 2010 after an FSF complaint, and one source states that App Store usage rules conflict with the GPL and AGPL. — [Engadget 2011](https://www.engadget.com/2011-01-09-the-gpl-the-app-store-and-you.html); [InformationWeek](https://www.informationweek.com/it-leadership/apple-pulls-vlc-player-from-app-store); [Fierce Network](https://www.fierce-network.com/developer/apple-removes-vlc-media-player-from-app-store)
- Signal-iOS is AGPLv3 and distributed via the App Store **[not re-verified]**. — [Signal-iOS GitHub](https://github.com/signalapp/Signal-iOS)

### Inferences
- **Who can complain matters.** App Store distribution of GPL or AGPL code is safe in practice only if every copyright holder consents. Using libsignal in your client means **Signal Messenger LLC holds copyright you don't control**. Signal has not been known to enforce against App Store forks (Molly is Android-only), but "unsupported" plus "change without notice" is an API and maintenance risk on top of the legal one.
- **Options**: (a) License the client AGPL or GPL-compatible, keep your own code under a CLA or DCO policy that grants the project App-Store-compatible rights, and accept libsignal's exposure. (b) Use permissively licensed E2EE libraries such as MLS implementations (OpenMLS is MIT) or vodozemac (Apache-2.0, Matrix), so the client can be Apache or MPL. (c) Dual-license your own code. Another researcher covers the library choice, so the licensing implication is the input here.
- **Server**: AGPL-3.0 (Signal-Server, Mastodon and Element's Synapse since 2023 use it) forces network-service modifications to be shared and deters a cloud provider from closed-forking. Apache-2.0 has a patent grant, is friendlier to enterprise self-hosters, and allows App-Store-friendly reuse. If libsignal is used *in the server*, the server is AGPL anyway. **Synapse/Element's move to AGPL is not re-verified.**
- **AI-written code licensing hygiene**: agents can reproduce GPL snippets verbatim. Run a license scanner (e.g., ScanCode, FOSSA or `go-licenses`) in CI and require DCO sign-off by a human for every commit. Also record that AI-assisted code may have uncertain copyrightability in the US: the Copyright Office's 2025 guidance requires human authorship **[not re-verified]**. This affects the ability to enforce your own license.

### Gaps
- No primary statement was found from Signal or the FSF on third-party App Store apps embedding libsignal.
- No 2026 case law on AGPL plus App Store was found.

---

## Q5. Operations: abuse/spam, verification, email, incident response, disclosure

### Takeaway
Open-signup servers attract spam, SMS-pumping fraud and abuse within days. Rate limits, invite or proof-of-work gates, abuse contacts and a published vulnerability-disclosure path are cheap to build before launch and expensive to retrofit.

### Cited Findings
- SMS verification costs: $0.05 per successful Twilio Verify plus $0.0083 per US SMS, and India programmable SMS at $0.0832 per segment (Oct 2026). — [Twilio Verify](https://www.twilio.com/en-us/verify/pricing); [Twilio SMS India](https://www.twilio.com/en-us/sms/pricing/in)
- Apple 1.2 requires "timely responses to concerns" on reports. Failure after notice can be treated as a Developer Code of Conduct violation and can lead to removal from the Developer Program. — [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- security.txt is standardized as RFC 9116 (`/.well-known/security.txt` with Contact and Expires fields) **[not re-verified]**. — [RFC 9116](https://www.rfc-editor.org/rfc/rfc9116)

### Inferences
- **Abuse**: per-IP, per-ASN and per-device rate limits on signup and on sends to non-contacts; "message requests" for strangers; optional invite codes or Privacy Pass / proof-of-work on open servers; App Attest/DeviceCheck to bind signups to real Apple devices on the project server; geo-blocking SMS to high-fraud prefixes to defeat SMS pumping; daily SMS spend caps with alerts.
- **Prefer email or username plus key over phone numbers** where possible. This removes SMS costs, India DLT complexity and much of the GDPR personal data. If phone numbers are used, contact discovery becomes a privacy liability (see Q7).
- **Email deliverability**: SPF, DKIM and DMARC on the sending domain; a reputable transactional ESP (with a DPA); separate subdomains for transactional and other mail. Self-hosters need a docs section, since self-hosted SMTP is the usual broken recovery path.
- **Incident response**: write an IR runbook before launch. Cover key compromise (signing keys, APNs keys, TLS), server breach, and client crypto bugs (forced upgrade or kill switch via minimum-version check). Map the clocks: GDPR 72h, India CERT-In 6h if applicable, and App Store expedited review requests for security fixes.
- **Vulnerability disclosure**: security.txt, a PGP or Signal contact, a safe-harbor statement and a 90-day coordinated-disclosure policy. Launch a bug bounty only once triage capacity exists (platforms such as HackerOne and Intigriti charge platform fees; a self-run program with modest bounties is common for small projects). Seek free audits via the Open Technology Fund Red Team Lab or Security Lab for internet-freedom tools. — [OTF Red Team Lab](https://www.opentech.fund/labs/red-team-lab/) (not detailed this session)

### Gaps
- Bug-bounty platform pricing and email-ESP pricing were not fetched.
- No published data was found on spam rates for small open-signup E2EE servers. Matrix and Mastodon spam waves are anecdotal.

---

## Q6. Risks specific to AI-written code (security, review, testing, alignment)

### Takeaway
Independent benchmarks still show about 45% of AI-generated code samples failing basic security tests (Veracode 2025), with **essentially no improvement in 2026** (56% average pass rate; best model 68%). For crypto, auth and key-management code in an E2EE app, AI output must be treated as untrusted: human expert review, property tests, fuzzing, and CI gates are required, not optional.

### Cited Findings
- Veracode 2025 GenAI Code Security Report (80 tasks mapped to MITRE CWEs): AI introduced vulnerabilities in **45%** of cases. Java exceeded a 70% failure rate. — [Veracode press release](https://www.veracode.com/press-release/ai-generated-code-poses-major-security-risks-in-nearly-half-of-all-development-tasks-veracode-research-reveals/); [Help Net Security](https://www.helpnetsecurity.com/2025/08/07/create-ai-code-security-risks/)
- Veracode 2026 report (released 28 Jul 2026): **average security pass rate 56%, "virtually unchanged"**. GPT-5.5 was best at 68% and Qwen3.7-max worst at 50%. Model size made little difference (large 53%, medium and small 51%). No security-specific prompting was used. — [SD Times](https://sdtimes.com/agentic-security/veracode-finds-ai-generated-code-security-has-barely-improved-since-last-year/)
- Earlier academic baseline: Pearce et al., "Asleep at the Keyboard?" (IEEE S&P 2022) found about 40% of Copilot completions in CWE-relevant scenarios vulnerable **[not re-verified]**. — [arXiv 2108.09293](https://arxiv.org/abs/2108.09293)
- Model churn: OpenAI retired GPT-5.5 from Codex sign-in on 2026-10-14. Anthropic lists several "legacy" models. Agent behavior shifts between versions. — [Codex pricing](https://learn.chatgpt.com/docs/pricing); [Claude pricing](https://claude.com/pricing)

### Inferences
- **Never let agents invent crypto.** Agents should only call vetted libraries through a thin, human-written and human-reviewed wrapper. Freeze that boundary with CODEOWNERS that require human approval for `crypto/`, `auth/`, `keys/` and the protocol state machine.
- **Testing strategy**: Go native fuzzing (`go test -fuzz`) on every parser and decoder (wire protocol, protobuf or JSON, SDP, push payloads); property-based tests for ratchet and state-machine invariants (rapid/gopter in Go, SwiftCheck or swift-testing parameterized tests); known-answer test vectors from RFCs; interop tests against a reference implementation; `go test -race`; Swift Thread Sanitizer. Mutation testing checks that agent-written tests actually test something, because agents often write tautological tests or edit tests to pass.
- **CI gates**: `govulncheck`, `gosec`, staticcheck, CodeQL or Semgrep; SBOM generation (Syft) plus vulnerability scanning (Grype or OSV-Scanner); license scanning; secret scanning; pinned dependencies with checksum verification (`go.sum`, SwiftPM `Package.resolved`); reproducible builds where feasible; Sigstore or cosign for server releases.
- **Supply chain**: agents hallucinate package names ("slopsquatting"). Block new dependencies without human approval via a CI dependency-diff check. Keep agents' network and tool permissions minimal. Never give agents production credentials, APNs keys or signing keys.
- **Keeping agents aligned**: a single authoritative spec (protocol doc, threat model, data-retention rules) referenced from `CLAUDE.md` and `AGENTS.md`, both pointing to the same files to avoid drift; explicit "never do" rules (no logging of plaintext, keys or phone numbers; no new deps; no disabling tests); small PRs; agent-written PRs reviewed by a human *and* a second, different model; ADRs recorded for decisions.
- **External review budget**: plan an independent crypto and protocol audit before any public "E2EE" claim. Marketing claims of E2EE that prove false create consumer-protection liability (e.g., the FTC's 2020 Zoom settlement over E2EE claims **[not re-verified]**: [FTC Zoom](https://www.ftc.gov/news-events/news/press-releases/2020/11/ftc-requires-zoom-enhance-its-security-practices-part-settlement)).

### Gaps
- No published study specifically measured vulnerability rates of AI agents writing *cryptographic protocol* code, or of Claude Code or Codex in agentic (multi-step, test-running) mode as opposed to single completions. Veracode's 2026 per-model results for Claude models were not in the retrieved snippet.

---

## Q7. Commonly missed product gaps

### Takeaway
The product gaps that most often sink small E2EE messengers are account recovery and multi-device key backup, contact-discovery privacy, privacy-safe crash reporting, accessibility for calling UI, and the migration and upgrade path for self-hosted servers. All of these are cheaper to design in than to retrofit.

### Cited Findings
- Apple requires a privacy policy that discloses third-party SDK data sharing, including analytics, and consent for usage-data collection (5.1.1(i)–(ii)) **[verified 2026-10]**. — [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- Apple requires recording indicators and consent for camera and mic use (2.5.14) **[verified 2026-10]**. — [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- Account deletion must also delete the user's shared UGC (5.1.1(v) FAQ) **[verified 2026-10]**. — [Apple: Account deletion](https://developer.apple.com/support/offering-account-deletion-in-your-app/)

### Inferences
- **Accessibility**: VoiceOver labels on call controls (mute, speaker, end, camera flip), Dynamic Type, CallKit integration (which gives free accessibility and Siri/CarPlay paths), captions or transcripts if feasible, haptics for call state, and Reduce Motion. Test with VoiceOver on a real device; agents rarely add accessibility traits unprompted, so add this to `AGENTS.md`.
- **Localization**: String Catalogs from day one, right-to-left layout (Arabic, Hebrew, Urdu), and Indic scripts (Hindi, Bengali, Tamil) if targeting India. Pluralization rules. Localized App Store metadata and privacy policy.
- **Crash reporting without leaks**: Apple's own crash reports via App Store Connect and Xcode Organizer plus MetricKit need no third-party SDK and do not affect privacy labels the same way. If using Sentry or a similar tool, self-host it, scrub breadcrumbs, never attach message content or keys, and make it opt-in.
- **Telemetry and analytics**: default off or aggregate-only; the consent prompt counts as "Data Used to Track You" if anything links across apps. Self-hosted servers should have telemetry off by default.
- **Contact discovery**: uploading hashed phone numbers is not private (phone-number hashes are trivially brute-forced). Options are no phone numbers (usernames and QR codes), Signal-style SGX/enclave discovery (complex), or opt-in per-contact discovery. Disclose whatever is done in the privacy label.
- **Account recovery and multi-device**: losing a phone means losing history unless there is an encrypted backup (PIN or passphrase-derived key, iCloud Keychain-synced key, or recovery code). Design the UX for "new phone" (device-to-device transfer), "lost phone" and "forgot PIN". Show safety-number or key-change UX that does not train users to click through.
- **Onboarding**: explain notifications permission (needed for calls), mic and camera permission timing, and what "self-hosted server" means. Server-address entry and verification (TOFU or pinned) for self-hosters. Apple reviewers need a working demo server and account in review notes.
- **Migration and upgrades**: versioned wire protocol and DB schema migrations for self-hosters running old servers; client and server version negotiation; a deprecation policy; data export (GDPR Art. 20 portability) and import from other messengers if desired.
- **Notification privacy**: notification previews and the content of push payloads. The push-gateway researcher covers the technical side, but the product decision (show sender or content on lock screen?) is commonly missed.

### Gaps
- No quantitative sources were fetched on accessibility rejection rates or on the cost of localization vendors.
- No 2026 source was found comparing crash-reporting SDKs' privacy-label impact.
