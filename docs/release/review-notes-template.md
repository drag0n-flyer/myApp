# [App] Review Notes Template

Copy this text into App Store Connect, in the App Review Information "Notes" field of the build. Replace each `<...>`. 👤 The owner fills it in. Do not write passwords in this file or anywhere in the repository: enter the demo account credentials in the "Sign-in information" fields of App Store Connect.

Use plain words. Reviewers are not cryptographers.

**Check every menu path below against the final app before you submit.** The screens for invites, blocking, reporting and account deletion were not all built when this template was written, so the button names are placeholders.

---

**What the app is.** [App] is a private messenger for friends and family. Messages and calls between people are end-to-end encrypted. Anyone can run their own server. The app connects to a server that its owner runs.

**Reviewer server.** The app connects to `https://<reviewer-server-host>`. It is already set up for you. Nothing needs installing.

**Demo accounts.** Two accounts are already created, `<demo username 1>` and `<demo username 2>`. Their passkey or sign-in details are in the "Sign-in information" fields. Sign-in uses a passkey. If a passkey does not work on your test device, use the email code on the sign-in screen. The code goes to `<reviewer mailbox>`, which is `<how to read it>`.

**How to try messaging.**

1. Sign in on your first device as `<demo username 1>`.
2. Open "Invite" and show the QR code or copy the invite link. Sign-up on this server needs an invite.
3. On a second device or second simulator, tap the invite link or scan the QR code, and sign in as `<demo username 2>`.
4. Send a message from either account. It shows up on the other.

**How to try a call.**

1. With both accounts signed in on two devices, open the chat between them.
2. Tap the phone button on one device.
3. The other device rings with the standard iPhone call screen. Answer it. Both people can hear each other.
4. Tap the red button to end the call.

**Why the app uses VoIP push, CallKit and the audio background mode.** The app makes voice and video calls. A call has to ring when the app is closed, so it uses Apple's VoIP push (PushKit). Every VoIP push the app receives is reported to CallKit right away, and the system shows the incoming call screen. The app never uses VoIP push for anything other than a call. The `audio` background mode keeps a call going when the app is not on screen. The `remote-notification` mode lets the app fetch new messages when a normal push arrives.

**Why the app asks for the microphone and camera.** The microphone is for calls and voice messages. The camera is for scanning invite and device-linking QR codes, and for video calls.

**Encryption.** The app uses standard encryption beyond what the operating system provides, so `ITSAppUsesNonExemptEncryption` is set to YES. The algorithms are standard and published (MLS, DTLS-SRTP, TLS, HPKE). The export compliance answers are `<paste the answers from the export compliance memo>`.

**User-generated content (Guideline 1.2).**

- Filter: a message from someone you have not chatted with arrives as a request. Nothing reaches you until you accept it.
- Block: open a chat, tap the person's name, and choose Block.
- Report: open a chat, tap the person's name, and choose Report. The operator answers reports within 24 hours.
- Contact: `<operator contact address>`, also shown in the app and in the privacy policy.

**Account deletion (Guideline 5.1.1(v)).** Go to Settings, then Account, then Delete Account. The account and its content are deleted right away.

**Privacy policy.** `https://<reviewer-server-host>/legal/privacy`

**Contact for questions during review.** `<owner name>`, `<phone>`, `<email>`.
