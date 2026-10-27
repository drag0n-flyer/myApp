# [App] Export Compliance Memo

> **Draft — needs lawyer review before use.**

This memo and checklist records what the apps contain and what the owner must file before the beta reaches TestFlight or the App Store. It is not legal advice. Sources were read on 2026-10-27. Rules change: check each linked source, and the current eCFR text, before filing. Items marked 👤 need the owner or the lawyer.

## 1. What the apps contain

| Component | Algorithms | Provided by |
|---|---|---|
| Message encryption (MLS, through mls-rs) | AES-128-GCM, X25519, Ed25519, SHA-256 | The project's own build (not the OS) |
| Call encryption (DTLS-SRTP, inside the project's own WebRTC build) | Standard DTLS and SRTP suites, built on BoringSSL | The project's own build (not the OS) |
| Push payload encryption (HPKE) | Standard HPKE suite | CryptoKit (OS) |
| Network transport (TLS, through URLSession) | Standard TLS | The OS |

Every item uses standard, published algorithms (IETF RFCs). Nothing is proprietary. The first two rows ship inside the app and are not OS-provided; this is what makes the app use encryption beyond the OS ([Apple: overview of export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance)).

## 2. Apple

- Set `ITSAppUsesNonExemptEncryption = YES` in each app and extension target, because the apps ship encryption that the OS does not provide ([Apple: overview of export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance)).
- In App Store Connect's export compliance questions, answer that the app uses standard encryption algorithms that are not provided by Apple's OS (same source).
- For that answer, Apple asks for the French encryption declaration, and only if the app is distributed on the App Store in France. A CCATS is listed only for apps with proprietary algorithms, which does not apply here ([Apple: export compliance documentation for encryption](https://developer.apple.com/help/app-store-connect/reference/export-compliance-documentation-for-encryption/)).
- After Apple approves the documents, Apple issues an `ITSEncryptionExportComplianceCode`. Add it to `apps/apple/project.yml`. 👤
- **Pending:** adding `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption: YES` to `project.yml` for both app targets and both NSE targets. This needs the app UI branch and is not done in this change. Until it is, App Store Connect asks the question on every build.

## 3. United States (EAR)

- **Working classification:** ECCN 5D992.c (mass market software) under License Exception ENC, 15 CFR §740.17(b)(1). This is a working assumption for the lawyer to confirm ([eCFR: 15 CFR 740.17](https://www.ecfr.gov/current/title-15/subtitle-B/chapter-VII/subchapter-C/part-740/section-740.17)).
- The 2021 BIS rule (effective 2021-03-29) removed the §742.15(b) email notification for publicly available encryption source code, and narrowed the annual self-classification report ([Thompson Hine summary](https://www.thompsonhine.com/publications/bis-eliminates-most-mass-market-encryption-reporting-obligations); [Baker McKenzie](https://sanctionsnews.bakermckenzie.com/bis-updates-reporting-requirements-relating-to-mass-market-encryption-items-and-publicly-available-software-and-also-updates-certain-classifications/)).
- BIS states that the annual report covers exports under §740.17(b)(1) unless a CCATS was submitted for the item. It is due February 1 for the prior calendar year, in CSV format only, with 12 required headers, and none is needed in a year with no applicable exports ([BIS: annual self-classification](https://www.bis.gov/learn-support/encryption-controls/annual-self-classification)). The BIS page does not itself describe the 2021 changes, so whether this report applies to a mass-market binary is a lawyer question.

**Questions for the lawyer** 👤

- Does a published Apache-2.0 source tree plus an App Store binary fall under "publicly available" (§734.3(b)(3)) for the source, while the binary is mass market?
- Does any annual report apply to the binary?
- Is the open-source origin of the owner's own build relevant?

Verify everything in this section against the current eCFR text before filing.

## 4. France

- France requires a declaration for supplying a cryptology means (déclaration de fourniture d'un moyen de cryptologie) to the national security agency ANSSI ([Apple: export compliance documentation](https://developer.apple.com/help/app-store-connect/reference/export-compliance-documentation-for-encryption/)).
- The documents ANSSI expects, its timelines, and the exact process: TO BE RESEARCHED with the lawyer 👤. Developers report little guidance, so budget time for it ([Cryptomator's experience](https://cryptomator.org/blog/2016/06/16/indepth-french-app-store/), written in 2016, so check that it is still current).
- Once ANSSI approves, upload the approval to App Store Connect (same Apple source).
- **Fallback:** exclude France from the beta's availability until the declaration is approved. 👤

## 5. Other countries

The default is to limit beta availability to countries the lawyer clears. Checklist for the lawyer 👤:

- Countries with import or use rules for encryption apps.
- Countries that restrict VoIP or encrypted calling apps.
- China: App Store distribution and CallKit restrictions (flagged in GOAL §6).
- Countries where the project's server operator or the owner has a legal presence.
- For each cleared country, whether any registration or notice is needed.

## 6. Record

Fill in when filings are made. 👤

| Item | Entry |
|---|---|
| Date of App Store Connect answers | |
| Answers given | |
| Documents uploaded (French declaration, others) | |
| `ITSEncryptionExportComplianceCode` received (date, not the code in chat) | |
| US annual report decision and date | |
| Countries cleared for the beta | |
| Reviewer's name | |
