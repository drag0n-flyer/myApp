# [App] Android: Google Play Data safety draft

> **Draft — needs owner and lawyer review before use.**

This is a draft of the answers for the Play Console Data safety form. It lists only what the app does today. It follows the iPhone privacy manifest (`apps/apple/App/PrivacyInfo.xcprivacy`) and [the privacy policy](../legal/privacy-policy.md), section 2. Every owner decision is marked "OWNER TO CONFIRM". Play's wording changes, so match each row to the current form before you submit.

## 1. Overview questions

| Question | Draft answer |
|---|---|
| Does the app collect or share any of the required user data types? | Yes, it collects. OWNER TO CONFIRM |
| Is all collected data encrypted in transit? | Yes. All connections to the server use TLS. OWNER TO CONFIRM |
| Do you provide a way for users to request that their data be deleted? | Yes: in the app, and a web page. The web page is PLACEHOLDER, OWNER TO CONFIRM (see [android-play.md](android-play.md), section 7). |
| Is data shared with third parties? | See section 4. OWNER TO CONFIRM |
| Is any data used for tracking? | No. Nothing is used for tracking, advertising or analytics. |

## 2. Data collected

All items below are collected for App functionality only, and are not optional for the feature they serve, except where noted. "Linked" means linked to the person's account.

| Play data type | What it is here | Collected | Linked | Purpose | Required or optional |
|---|---|---|---|---|---|
| Personal info: Email address | Sign-in and recovery | Yes | Yes | App functionality, Account management | Required |
| Personal info: Name | Display name | Yes | Yes | App functionality | Required |
| Personal info: User IDs | Username and account ID | Yes | Yes | App functionality, Account management | Required |
| Personal info: Phone number | Only if the person adds one | Yes | Yes | App functionality | Optional |
| Messages: Other in-app messages | Message content in server-readable chats only. End-to-end encrypted content cannot be read by the server (privacy policy, section 3). Maps to "user content" in the iPhone manifest. | Yes | Yes | App functionality | Required for chats. OWNER TO CONFIRM how to declare encrypted content that the server stores but cannot read, and the sizes and timing the server sees. |
| Photos and videos / Audio files | Attachments and voice notes, encrypted on the device before upload | Yes (stored) | Yes | App functionality | Optional |
| Device or other IDs: Device or other IDs | The device ID the server assigns to each device. It is not an advertising ID. | Yes | Yes | App functionality, Account management | Required |
| App info and performance: Crash logs | Crash reports with no message content, contacts or other personal content | Yes | No, not linked to the account | App functionality (fixing crashes) | Required. Handling is PLACEHOLDER, OWNER TO CONFIRM (see [android-play.md](android-play.md), section 8). |

Not collected: location, contacts or address book, financial info, health, web browsing, calendar, advertising ID, installed apps, files and docs outside what the person attaches.

OWNER TO CONFIRM: how Play should treat camera and microphone. Calls and voice notes go between devices and are not stored (privacy policy, section 3), and the camera also reads QR codes on the device. Data that only stays on the device is not "collected".

## 3. Data not used for tracking or advertising

- No data is sold. No data is used for advertising or marketing. (Privacy policy, section 5: "We do not sell your data and do not use it for advertising", still marked "owner to confirm" there.)
- No data is combined with data from other companies.
- The crash report type is the only one that is not linked to the account.

## 4. Sharing

Play counts a transfer to a third party as sharing, unless it is to a service provider acting for the operator, or the person starts it.

| Recipient | What they see | Draft answer |
|---|---|---|
| Push: Firebase Cloud Messaging (Google), or a UnifiedPush distributor the person chose | An opaque push handle, the timing and size of each push, and a push token | OWNER TO CONFIRM: usually "service provider", not sharing. |
| The project push gateway | Which server sends to which handle, and when | OWNER TO CONFIRM: service provider. |
| Call relay operators, and Cloudflare if the administrator turns it on | IP addresses, call timing and volume | OWNER TO CONFIRM: service provider. |
| The email provider that sends sign-in codes | Email address and timing | OWNER TO CONFIRM: service provider. Provider name TO BE FILLED IN (privacy policy, section 5). |

## 5. Security practices

| Question | Draft answer |
|---|---|
| Data encrypted in transit | Yes |
| Data on the device | Encrypted at rest (database and keys). The app turns off Android backup (`allowBackup="false"`). |
| Account deletion | In the app, takes effect immediately; web page PLACEHOLDER, OWNER TO CONFIRM |
| Independent security review | No. Do not claim one. OWNER TO CONFIRM; an audit is planned (see `docs/security/audit-scope.md`). |

## 6. Third-party libraries that collect data

The Google Play services and Firebase libraries the app includes (`firebase-messaging`, Credentials Manager with Play services) collect data on their own terms. OWNER TO CONFIRM: read Google's current disclosure for each and add anything it requires to section 2. The full list is on Settings, Open-source licenses.

## 7. Open items

- OWNER TO CONFIRM: every row marked above.
- PLACEHOLDER: account-deletion web page URL.
- PLACEHOLDER: crash-report handling (transport, recipient, retention).
- The privacy policy is a draft and needs lawyer review. The form must match the final policy.
